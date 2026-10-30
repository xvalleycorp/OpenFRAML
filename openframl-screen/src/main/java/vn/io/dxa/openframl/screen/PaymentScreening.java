//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.screen;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import vn.io.dxa.openframl.cases.Alert;
import vn.io.dxa.openframl.model.TransactionEvent;

/**
 * Screens the beneficiary's name of each payment after it is made, and raises an alert for every listed entry it may
 * be. It runs beside the gate, not inside it: name screening takes longer than the gate's budget, and the account
 * lists the gate checks are the inline part.
 *
 * <p>The alert is about the paying customer, who sent money to someone who may be listed. Its id is made from the
 * event, the list and the entry, so screening the same payment again raises nothing new. Its evidence holds what the
 * analyst needs: the list and its version, the entry, the name on the payment and the listed name it came close to,
 * the score and its reasons; never an account number.
 *
 * <p>The same beneficiary names come back often, so results are cached per name, for as long as the lists in use do
 * not change.
 */
public final class PaymentScreening {

    /** The alert type of a beneficiary name close to a listed one. */
    public static final String ALERT_TYPE = "BENEFICIARY_NAME_MATCH";

    private final Supplier<Screener> screener;
    private final double threshold;
    private final Consumer<Alert> alerts;
    private final int cacheSize;
    private Screener cachedFor;
    private Map<String, List<ScreeningMatch>> cache;

    /**
     * @param screener  the screener of the lists in force, e.g. {@link ScreeningDirectory#current}
     * @param threshold the lowest score that raises an alert, e.g. 0.85
     * @param alerts    where alerts go, e.g. {@code caseService::raise}
     * @param cacheSize the most beneficiary names whose results are kept
     */
    public PaymentScreening(Supplier<Screener> screener, double threshold, Consumer<Alert> alerts, int cacheSize) {
        this.screener = Objects.requireNonNull(screener, "screener");
        this.threshold = threshold;
        this.alerts = Objects.requireNonNull(alerts, "alerts");
        this.cacheSize = cacheSize;
    }

    /**
     * Screens one payment.
     *
     * @return the alerts raised, none if the payment carries no beneficiary name or the name is on no list
     */
    public List<Alert> screen(TransactionEvent event) {
        String name = event.beneficiaryName();
        if (name == null) {
            return List.of();
        }
        List<Alert> raised = new ArrayList<>();
        for (ScreeningMatch match : matches(name)) {
            Map<String, String> evidence = new LinkedHashMap<>();
            evidence.put("list", match.source());
            evidence.put("listVersion", match.listVersion());
            evidence.put("entry", match.entry().id());
            evidence.put("entryKind", match.entry().kind().name());
            evidence.put("entryRemark", match.entry().remark());
            evidence.put("paymentName", name);
            evidence.put("listedName", match.matchedName());
            evidence.put("score", String.format(Locale.ROOT, "%.2f", match.score()));
            evidence.put("reasons", String.join("; ", match.reasons()));
            evidence.put("amount", event.amount().toString());
            Alert alert = new Alert(event.eventId() + ":" + match.source() + ":" + match.entry().id(), "screening",
                    ALERT_TYPE, event.customerId(), Optional.of(event.eventId()), Optional.of(match.score()),
                    evidence, event.occurredAt());
            alerts.accept(alert);
            raised.add(alert);
        }
        return raised;
    }

    /** Cached per normalised name; the screening itself runs outside the lock, so payments are screened in parallel. */
    private List<ScreeningMatch> matches(String name) {
        Screener current = screener.get();
        String key = Names.normalize(name);
        synchronized (this) {
            if (current != cachedFor) {
                cachedFor = current;
                cache = new LinkedHashMap<>(16, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, List<ScreeningMatch>> eldest) {
                        return size() > cacheSize;
                    }
                };
            }
            List<ScreeningMatch> cached = cache.get(key);
            if (cached != null) {
                return cached;
            }
        }
        List<ScreeningMatch> matches = current.screen(Subject.named(name), threshold);
        synchronized (this) {
            if (current == cachedFor) {
                cache.put(key, matches);
            }
        }
        return matches;
    }
}
