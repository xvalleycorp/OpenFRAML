//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.gate;

import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.WARNING;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import vn.io.dxa.openframl.model.Decision;
import vn.io.dxa.openframl.model.TransactionEvent;
import vn.io.dxa.openframl.pack.CountryPack;
import vn.io.dxa.openframl.pack.PackResource;
import vn.io.dxa.rulestone.api.DecisionRecord;
import vn.io.dxa.rulestone.api.DecisionRef;
import vn.io.dxa.rulestone.api.DecisionResult;
import vn.io.dxa.rulestone.api.RuleStoneEngine;
import vn.io.dxa.rulestone.cel.ValueType;
import vn.io.dxa.rulestone.core.DecisionTable;
import vn.io.dxa.rulestone.core.DecisionTableJson;
import vn.io.dxa.rulestone.core.RuleStone;
import vn.io.dxa.rulestone.core.Rule;

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
 * <p>Both tables come from the {@link CountryPack} and are checked when the gate is built: a table that is missing,
 * declares no {@code action} output, or names an unknown action stops the build, not a payment. The business date
 * that selects the table version in force is the payment's {@code occurredAt} in the pack's zone, so a replayed
 * payment is decided by the rules that were in force when it was made.
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

    /** A quoted literal, the usual form of an {@code action} expression, e.g. {@code 'HOLD'}. */
    private static final Pattern ACTION_LITERAL = Pattern.compile("'([A-Za-z_]+)'");

    private final CountryPack pack;
    private final RuleStoneEngine engine;
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

    private Gate(Builder builder, List<DecisionTable> tables) {
        this.pack = builder.pack;
        this.engine = RuleStone.builder().clock(Clock.system(pack.businessZone())).decisions(tables).build();
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
            DecisionResult result = engine.evaluate(
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
            List<DecisionTable> tables = readTables(pack);
            checkActions(tables, DECISION_ID);
            checkActions(tables, FALLBACK_ID);
            return new Gate(this, tables);
        }

        private static Duration positive(Duration duration, String name) {
            if (duration.isNegative() || duration.isZero()) {
                throw new IllegalArgumentException(name + " must be positive: " + duration);
            }
            return duration;
        }
    }

    private static List<DecisionTable> readTables(CountryPack pack) {
        List<DecisionTable> tables = new ArrayList<>();
        for (PackResource resource : pack.decisionTables()) {
            try (InputStream in = resource.open()) {
                tables.add(DecisionTableJson.read(in));
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read " + resource.name() + " from country pack "
                        + pack.countryCode(), e);
            }
        }
        return tables;
    }

    private static void checkActions(List<DecisionTable> tables, String decisionId) {
        List<DecisionTable> versions = tables.stream().filter(table -> table.id().equals(decisionId)).toList();
        if (versions.isEmpty()) {
            throw new IllegalStateException("the country pack has no decision table " + decisionId);
        }
        for (DecisionTable table : versions) {
            if (!ValueType.STRING.equals(table.outputs().get("action"))) {
                throw new IllegalStateException(table.ref() + " must declare the string output 'action'");
            }
            for (Rule rule : table.rules()) {
                String expression = rule.then().get("action");
                Matcher literal = expression == null ? null : ACTION_LITERAL.matcher(expression.strip());
                if (literal != null && literal.matches() && !isDecision(literal.group(1))) {
                    throw new IllegalStateException("%s rule %s sets unknown action %s; expected one of %s"
                            .formatted(table.ref(), rule.id(), literal.group(1), List.of(Decision.values())));
                }
            }
        }
    }

    private static boolean isDecision(String name) {
        for (Decision decision : Decision.values()) {
            if (decision.name().equals(name)) {
                return true;
            }
        }
        return false;
    }
}
