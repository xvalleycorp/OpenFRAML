//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.gate;

import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.INFO;
import static java.lang.System.Logger.Level.WARNING;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
import vn.io.dxa.openframl.model.Decision;
import vn.io.dxa.openframl.model.TransactionEvent;
import vn.io.dxa.openframl.pack.CountryPack;
import vn.io.dxa.rulestone.api.DecisionRecord;
import vn.io.dxa.rulestone.api.DecisionRef;
import vn.io.dxa.rulestone.api.DecisionResult;

/**
 * Decides what to do with a payment before it leaves the bank, within the caller's deadline.
 *
 * <p>The steps, in order:
 * <ol>
 *   <li>look the beneficiary up in the local {@link Watchlist}, before anything that can fail or time out;</li>
 *   <li>read the parties' profiles from the {@link ProfileStore};</li>
 *   <li>count the attempt and read the originator's counters from the {@link VelocityStore};</li>
 *   <li>score the payment with the {@link ScoreProvider}, if there is one and time is left; a late score is
 *       dropped;</li>
 *   <li>evaluate the decision table {@value #DECISION_ID}; when several rules fire, the most severe action
 *       wins.</li>
 * </ol>
 *
 * <p>If the deadline passes before the decision table runs, a store fails, or the table fails, the gate evaluates
 * the fallback table {@value #FALLBACK_ID} instead, with the facts gathered so far and the input {@code failure}.
 * The bank decides there which payments fail open and which fail closed. If even that table fails, the gate holds a
 * listed beneficiary and challenges everything else ({@value #FALLBACK_FAILED}).
 *
 * <p>Both tables form the gate's {@link GatePolicy}: the country pack's samples by default, or the bank's own. A
 * policy is checked when it is compiled, so a table that is missing, declares no {@code action} output, or names an
 * unknown action stops the build, not a payment. The business date that selects the table version in force is the
 * payment's {@code occurredAt} in the pack's zone, so a replayed payment is decided by the rules that were in force
 * when it was made.
 *
 * <p>The policy changes without a restart: {@link #activate} swaps in a new one and {@link #rollback} returns to the
 * previous one, each approved by two different people and recorded by the {@link PolicyAudit} before it takes
 * effect. A decision runs from start to end on the policy in force when it started.
 *
 * <p>Thread-safe. Every decision is handed to the {@link GateRecordSink} before {@link #decide} returns.
 */
public final class Gate implements AutoCloseable {

    /** The decision table that decides payments. */
    public static final String DECISION_ID = "gate_decision";

    /** The decision table that decides payments when the gate cannot run {@value #DECISION_ID}. */
    public static final String FALLBACK_ID = "gate_fallback";

    /** Reason code of the built-in last resort, used only when {@value #FALLBACK_ID} itself fails. */
    public static final String FALLBACK_FAILED = "GATE_FALLBACK_FAILED";

    private static final System.Logger LOG = System.getLogger(Gate.class.getName());

    private final CountryPack pack;
    private final PolicyAudit audit;
    private volatile Policies policies;
    private final ProfileStore profiles;
    private final VelocityStore velocity;
    private final Watchlist watchlist;
    private final ScoreProvider scorer;
    private final GateRecordSink sink;
    private final long modelBudgetNanos;
    private final long ruleReserveNanos;
    private final ExecutorService executor;
    private final boolean ownsExecutor;
    private final LongSupplier nanoTime;

    /** The policy in force and the one before it, swapped together. */
    private record Policies(GatePolicy current, GatePolicy previous) {}

    private Gate(Builder builder, GatePolicy policy) {
        this.pack = builder.pack;
        this.audit = builder.audit;
        this.policies = new Policies(policy, null);
        this.profiles = builder.profiles;
        this.velocity = builder.velocity;
        this.watchlist = builder.watchlist;
        this.scorer = builder.scorer;
        this.sink = builder.sink;
        this.modelBudgetNanos = builder.modelBudget.toNanos();
        this.ruleReserveNanos = builder.ruleReserve.toNanos();
        this.ownsExecutor = builder.executor == null;
        this.executor = ownsExecutor ? Executors.newVirtualThreadPerTaskExecutor() : builder.executor;
        this.nanoTime = builder.nanoTime;
    }

    public static Builder builder(CountryPack pack) {
        return new Builder(pack);
    }

    /**
     * Decides one payment.
     *
     * @param event  the payment, before it is sent to the switch
     * @param budget how long the caller can wait, measured from now; usually the remaining gRPC deadline less the
     *               network time back to the caller
     */
    public GateOutcome decide(TransactionEvent event, Duration budget) {
        Objects.requireNonNull(event, "event");
        Evaluation evaluation = new Evaluation(event, budget.toNanos());
        GateOutcome outcome = evaluation.decide();
        GateRecord record = evaluation.toRecord(outcome);
        try {
            sink.accept(record);
        } catch (RuntimeException e) {
            LOG.log(WARNING, "record sink failed for event {0}: {1}", event.eventId(), e.getClass().getName());
        }
        return outcome;
    }

    /** The country pack the gate's tables come from. */
    public CountryPack countryPack() {
        return pack;
    }

    /** The policy in force. */
    public GatePolicy policy() {
        return policies.current();
    }

    /**
     * Puts a new policy in force for every decision that starts from now on. Decisions already running finish on the
     * policy they started with.
     *
     * @throws IllegalArgumentException if {@code next} is already in force
     * @throws RuntimeException         the audit's exception if it cannot record the change; the change is not
     *                                  applied
     */
    public synchronized PolicyChange activate(GatePolicy next, Approval approval) {
        Objects.requireNonNull(next, "next");
        GatePolicy current = policies.current();
        if (current.id().equals(next.id())) {
            throw new IllegalArgumentException("policy " + next.id() + " is already in force");
        }
        PolicyChange change = new PolicyChange(PolicyChange.Kind.ACTIVATE, current.id(), next.id(), approval);
        audit.record(change);
        policies = new Policies(next, current);
        LOG.log(INFO, "policy {0} in force (was {1}), approved by {2}", next.id(), current.id(), approval.checker());
        return change;
    }

    /**
     * Returns to the policy in force before the last activation: one step back, not more.
     *
     * @throws IllegalStateException if there is nothing to go back to
     */
    public synchronized PolicyChange rollback(Approval approval) {
        Policies now = policies;
        if (now.previous() == null) {
            throw new IllegalStateException("no previous policy to roll back to");
        }
        PolicyChange change = new PolicyChange(PolicyChange.Kind.ROLLBACK, now.current().id(), now.previous().id(),
                approval);
        audit.record(change);
        policies = new Policies(now.previous(), null);
        LOG.log(INFO, "policy {0} in force again (rolled back from {1}), approved by {2}", now.previous().id(),
                now.current().id(), approval.checker());
        return change;
    }

    /** Stops the gate's own scoring threads; an executor given to the builder is left running. */
    @Override
    public void close() {
        if (ownsExecutor) {
            executor.shutdownNow();
        }
    }

    /** The state of one decision. */
    private final class Evaluation {

        private final TransactionEvent event;
        private final GatePolicy policy = policies.current();
        private final LocalDate businessDate;
        private final GateFacts facts;
        private final long deadline;
        private final Map<String, Long> stepNanos = new LinkedHashMap<>();
        private final List<DecisionRecord> records = new ArrayList<>(2);
        private long stepStart;
        private Optional<WatchlistHit> hit = Optional.empty();
        private Optional<Score> score = Optional.empty();

        Evaluation(TransactionEvent event, long budgetNanos) {
            this.stepStart = nanoTime.getAsLong();
            this.deadline = stepStart + budgetNanos;
            this.event = event;
            this.businessDate = LocalDate.ofInstant(event.occurredAt(), pack.businessZone());
            this.facts = new GateFacts(event, businessDate);
        }

        GateOutcome decide() {
            try {
                hit = event.beneficiary() == null ? Optional.empty() : watchlist.find(event.beneficiary());
                facts.watchlist(hit);
            } catch (RuntimeException e) {
                return failed("watchlist", e, GateFailure.STORE_UNAVAILABLE);
            }
            step("watchlist");
            if (expired()) {
                return fallback(GateFailure.TIMEOUT);
            }

            ProfileSnapshot profileSnapshot;
            try {
                profileSnapshot = profiles.lookup(event);
                facts.profiles(event, profileSnapshot);
            } catch (RuntimeException e) {
                return failed("profiles", e, GateFailure.STORE_UNAVAILABLE);
            }
            step("profiles");

            VelocitySnapshot velocitySnapshot;
            try {
                velocitySnapshot = velocity.recordAttempt(event);
                facts.velocity(velocitySnapshot);
            } catch (RuntimeException e) {
                return failed("velocity", e, GateFailure.STORE_UNAVAILABLE);
            }
            step("velocity");

            if (scorer != null) {
                score(profileSnapshot, velocitySnapshot);
                step("model");
            }
            // Workaround: RuleStone cannot yet bound an evaluation once started, so the deadline is checked only
            // before it (https://github.com/xvalleycorp/RuleStone/issues/7, tracked in OpenFRAML #3).
            if (expired()) {
                return fallback(GateFailure.TIMEOUT);
            }

            try {
                DecisionResult result = evaluate(DECISION_ID);
                Decision decision = action(result);
                step("rules");
                return new GateOutcome(decision, result.reasonCodes().stream().distinct().toList(),
                        DECISION_ID + "@" + result.ruleVersion(), Optional.empty(), false);
            } catch (RuntimeException e) {
                return failed("rules", e, GateFailure.POLICY_ERROR);
            }
        }

        private void score(ProfileSnapshot profileSnapshot, VelocitySnapshot velocitySnapshot) {
            long allowed = Math.min(modelBudgetNanos, deadline - nanoTime.getAsLong() - ruleReserveNanos);
            if (allowed <= 0) {
                LOG.log(DEBUG, "no time left to score event {0}", event.eventId());
                return;
            }
            Future<Score> pending;
            try {
                pending = executor.submit(() -> scorer.score(event, profileSnapshot, velocitySnapshot));
            } catch (RejectedExecutionException e) {
                LOG.log(WARNING, "scoring unavailable for event {0}: gate is closed", event.eventId());
                return;
            }
            try {
                Score scored = pending.get(allowed, TimeUnit.NANOSECONDS);
                facts.score(scored);
                score = Optional.of(scored);
            } catch (TimeoutException e) {
                pending.cancel(true);
                LOG.log(DEBUG, "score for event {0} was late; deciding without it", event.eventId());
            } catch (ExecutionException e) {
                LOG.log(WARNING, "scoring failed for event {0}: {1}", event.eventId(),
                        e.getCause().getClass().getName());
            } catch (InterruptedException e) {
                pending.cancel(true);
                Thread.currentThread().interrupt();
            }
        }

        private GateOutcome failed(String stepName, RuntimeException e, GateFailure failure) {
            step(stepName);
            // The class only: a store's message may carry an account number.
            LOG.log(WARNING, "{0} failed for event {1}: {2}", stepName, event.eventId(), e.getClass().getName());
            return fallback(failure);
        }

        private GateOutcome fallback(GateFailure failure) {
            facts.failure(failure);
            try {
                DecisionResult result = evaluate(FALLBACK_ID);
                Decision decision = action(result);
                boolean reviewLater = result.output().get("reviewLater") instanceof Boolean review && review;
                step("fallback");
                return new GateOutcome(decision, result.reasonCodes().stream().distinct().toList(),
                        FALLBACK_ID + "@" + result.ruleVersion(), Optional.of(failure), reviewLater);
            } catch (RuntimeException e) {
                step("fallback");
                LOG.log(WARNING, "fallback table failed for event {0}: {1}", event.eventId(),
                        e.getClass().getName());
                Decision decision = facts.watchlisted() ? Decision.HOLD : Decision.CHALLENGE;
                return new GateOutcome(decision, List.of(FALLBACK_FAILED), "built-in", Optional.of(failure), false);
            }
        }

        private DecisionResult evaluate(String decisionId) {
            DecisionResult result = policy.engine().evaluate(
                    DecisionRef.of(decisionId).asOf(businessDate).correlationId(event.eventId()), facts.toFacts());
            records.add(result.record());
            return result;
        }

        private boolean expired() {
            return nanoTime.getAsLong() - deadline >= 0;
        }

        private void step(String name) {
            long now = nanoTime.getAsLong();
            stepNanos.merge(name, now - stepStart, Long::sum);
            stepStart = now;
        }

        GateRecord toRecord(GateOutcome outcome) {
            return new GateRecord(event.eventId(), event.occurredAt(), outcome, score.map(Score::modelVersion),
                    score.map(Score::fraud), hit, stepNanos, records);
        }
    }

    /**
     * The action a table chose: its {@code action} output, or with hit policy {@code COLLECT} the most severe of the
     * actions of the rules that fired.
     */
    private static Decision action(DecisionResult result) {
        Object action = result.output().get("action");
        if (action instanceof String single) {
            return Decision.valueOf(single);
        }
        if (action instanceof List<?> actions && !actions.isEmpty()) {
            return actions.stream().map(each -> Decision.valueOf((String) each)).reduce(Decision.ALLOW, Decision::max);
        }
        throw new IllegalStateException(result.decisionId() + " " + result.ruleVersion() + " chose no action");
    }

    /** Builds a {@link Gate}. A profile store and a velocity store are required. */
    public static final class Builder {

        private final CountryPack pack;
        private ProfileStore profiles;
        private VelocityStore velocity;
        private Watchlist watchlist = Watchlist.NONE;
        private ScoreProvider scorer;
        private GateRecordSink sink = GateRecordSink.NONE;
        private Duration modelBudget = Duration.ofMillis(5);
        private Duration ruleReserve = Duration.ofMillis(2);
        private ExecutorService executor;
        private LongSupplier nanoTime = System::nanoTime;
        private GatePolicy policy;
        private PolicyAudit audit = change -> LOG.log(INFO, "policy change {0}", change);

        private Builder(CountryPack pack) {
            this.pack = Objects.requireNonNull(pack, "pack");
        }

        public Builder profiles(ProfileStore profiles) {
            this.profiles = Objects.requireNonNull(profiles, "profiles");
            return this;
        }

        public Builder velocity(VelocityStore velocity) {
            this.velocity = Objects.requireNonNull(velocity, "velocity");
            return this;
        }

        public Builder watchlist(Watchlist watchlist) {
            this.watchlist = Objects.requireNonNull(watchlist, "watchlist");
            return this;
        }

        /** Scores payments with a model; without one, every decision takes the rules-only branch. */
        public Builder scoreProvider(ScoreProvider scorer) {
            this.scorer = Objects.requireNonNull(scorer, "scorer");
            return this;
        }

        public Builder recordSink(GateRecordSink sink) {
            this.sink = Objects.requireNonNull(sink, "sink");
            return this;
        }

        /** The longest the gate waits for a score (default 5 ms). */
        public Builder modelBudget(Duration modelBudget) {
            this.modelBudget = positive(modelBudget, "modelBudget");
            return this;
        }

        /** Time kept back for the decision table after scoring (default 2 ms). */
        public Builder ruleReserve(Duration ruleReserve) {
            this.ruleReserve = positive(ruleReserve, "ruleReserve");
            return this;
        }

        /** Runs scoring on this executor instead of the gate's own virtual threads. The gate does not close it. */
        public Builder executor(ExecutorService executor) {
            this.executor = Objects.requireNonNull(executor, "executor");
            return this;
        }

        /** The policy to start with, e.g. the bank's own tables; by default the country pack's samples. */
        public Builder policy(GatePolicy policy) {
            this.policy = Objects.requireNonNull(policy, "policy");
            return this;
        }

        /** Records every policy change before it takes effect; by default the change is only logged. */
        public Builder policyAudit(PolicyAudit audit) {
            this.audit = Objects.requireNonNull(audit, "audit");
            return this;
        }

        /** The time source; tests replace it to step through deadlines. */
        Builder nanoTime(LongSupplier nanoTime) {
            this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
            return this;
        }

        /**
         * @throws IllegalStateException if a store is missing, or the pack's tables do not fit the gate
         */
        public Gate build() {
            if (profiles == null || velocity == null) {
                throw new IllegalStateException("a gate needs a profile store and a velocity store");
            }
            return new Gate(this, policy != null ? policy : GatePolicy.of(pack));
        }

        private static Duration positive(Duration duration, String name) {
            if (duration.isNegative() || duration.isZero()) {
                throw new IllegalArgumentException(name + " must be positive: " + duration);
            }
            return duration;
        }
    }
}
