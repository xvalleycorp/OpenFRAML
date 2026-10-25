//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.benchmark;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.SplittableRandom;
import vn.io.dxa.openframl.gate.AccountProfile;
import vn.io.dxa.openframl.gate.WatchlistHit;
import vn.io.dxa.openframl.gate.memory.InMemoryProfileStore;
import vn.io.dxa.openframl.gate.memory.InMemoryWatchlist;
import vn.io.dxa.openframl.model.AccountRef;
import vn.io.dxa.openframl.model.Amount;
import vn.io.dxa.openframl.model.Channel;
import vn.io.dxa.openframl.model.SessionSignals;
import vn.io.dxa.openframl.model.TransactionEvent;
import vn.io.dxa.openframl.model.TransactionType;

/**
 * A deterministic synthetic bank for benchmarks: customers with profiles, known beneficiaries and devices, a share
 * of mule accounts, and a watchlist. The same size and seed always give the same bank and the same payments.
 *
 * <p>The payment mix follows the typologies of the Vietnam pack, so a run exercises every rule and both the common
 * path (most payments are ordinary) and the rarer ones. Amounts are in VND; bank codes are the invented
 * {@code 999901} (this bank) and {@code 999902} (another bank).
 */
public final class SyntheticBank {

    static final String CURRENCY = "VND";
    static final LocalDate TODAY = LocalDate.of(2026, 10, 25);
    static final Instant START = Instant.parse("2026-10-25T01:00:00Z");

    /**
     * Simulated time between two payments of one feed: about a million payments a simulated day, so each customer
     * makes a realistic handful a day and the velocity windows stay a realistic size.
     */
    static final Duration SPACING = Duration.ofNanos(86_400_000_000_000L / 1_000_000);

    private static final int KNOWN_BENEFICIARIES = 4;

    private final AccountRef[] customers;
    private final AccountRef[][] knownBeneficiaries;
    private final AccountRef[] mules;
    private final AccountRef[] listed;
    private final InMemoryProfileStore profiles = new InMemoryProfileStore();
    private final InMemoryWatchlist watchlist = new InMemoryWatchlist();

    /**
     * @param size number of customers; mules are 1% of it and a tenth of the mules are listed
     */
    public SyntheticBank(int size, long seed) {
        SplittableRandom random = new SplittableRandom(seed);
        customers = new AccountRef[size];
        knownBeneficiaries = new AccountRef[size][KNOWN_BENEFICIARIES];
        for (int i = 0; i < size; i++) {
            customers[i] = new AccountRef("999901", "1" + "%09d".formatted(i));
            profiles.account(customers[i], new AccountProfile(TODAY.minusDays(30 + random.nextInt(4000)),
                    random.nextInt(100) < 3 ? "HIGH" : "LOW"));
            profiles.knownDevice(customerId(i), deviceId(i));
            for (int j = 0; j < KNOWN_BENEFICIARIES; j++) {
                knownBeneficiaries[i][j] = new AccountRef("999902", "2" + "%09d".formatted(random.nextInt(1 << 30)));
                profiles.knownBeneficiary(customers[i], knownBeneficiaries[i][j]);
            }
        }
        mules = new AccountRef[Math.max(10, size / 100)];
        Map<AccountRef, WatchlistHit> hits = new HashMap<>();
        for (int i = 0; i < mules.length; i++) {
            mules[i] = new AccountRef("999902", "9" + "%09d".formatted(i));
            profiles.account(mules[i], new AccountProfile(TODAY.minusDays(random.nextInt(20)), null));
            if (i % 10 == 0) {
                hits.put(mules[i], new WatchlistHit("SUSPICIOUS_ACCOUNTS", "synthetic-1"));
            }
        }
        listed = hits.keySet().toArray(AccountRef[]::new);
        java.util.Arrays.sort(listed, java.util.Comparator.comparing(AccountRef::key));
        watchlist.replace(hits);
    }

    public InMemoryProfileStore profiles() {
        return profiles;
    }

    public InMemoryWatchlist watchlist() {
        return watchlist;
    }

    public int size() {
        return customers.length;
    }

    /**
     * Payments of the customers in one slice, in simulated time order. Feeds of different slices share no
     * customer, as requests routed by account reach different nodes.
     *
     * @param slice  this feed's slice, from 0 to {@code slices - 1}
     * @param slices number of feeds
     */
    public Feed feed(int slice, int slices, long seed) {
        if (slice < 0 || slice >= slices || slices > customers.length) {
            throw new IllegalArgumentException("slice " + slice + " of " + slices);
        }
        return new Feed(slice, slices, seed);
    }

    private static String customerId(int index) {
        return "cust-" + index;
    }

    private static String deviceId(int index) {
        return "device-" + index;
    }

    /** Payment scenarios and their share of the mix, in percent. */
    enum Scenario {
        ORDINARY(80),
        NEW_BENEFICIARY_SMALL(8),
        NEW_BENEFICIARY_LARGE(4),
        SCAM_CALL(3),
        NEW_DEVICE(2),
        LISTED_BENEFICIARY(2),
        REMOTE_ACCESS(1);

        final int percent;

        Scenario(int percent) {
            this.percent = percent;
        }

        static Scenario pick(int roll) {
            int bound = 0;
            for (Scenario scenario : values()) {
                bound += scenario.percent;
                if (roll < bound) {
                    return scenario;
                }
            }
            throw new IllegalStateException("shares must add up to 100");
        }
    }

    /** An endless, single-threaded source of payments. */
    public final class Feed {

        private final SplittableRandom random;
        private final int slice;
        private final int slices;
        private final String prefix;
        private long sequence;

        private Feed(int slice, int slices, long seed) {
            this.random = new SplittableRandom(seed ^ slice);
            this.slice = slice;
            this.slices = slices;
            this.prefix = "s" + slice + "-";
        }

        public TransactionEvent next() {
            int perSlice = (customers.length - slice + slices - 1) / slices;
            int customer = slice + slices * random.nextInt(perSlice);
            Scenario scenario = Scenario.pick(random.nextInt(100));
            AccountRef beneficiary = knownBeneficiaries[customer][random.nextInt(KNOWN_BENEFICIARIES)];
            long amount = between(50_000, 5_000_000);
            String device = deviceId(customer);
            boolean call = false;
            boolean remote = false;
            switch (scenario) {
                case ORDINARY -> { }
                case NEW_BENEFICIARY_SMALL -> {
                    beneficiary = stranger();
                    amount = between(100_000, 20_000_000);
                }
                case NEW_BENEFICIARY_LARGE -> {
                    beneficiary = stranger();
                    amount = between(50_000_000, 300_000_000);
                }
                case SCAM_CALL -> {
                    beneficiary = mules[random.nextInt(mules.length)];
                    amount = between(20_000_000, 200_000_000);
                    call = true;
                }
                case NEW_DEVICE -> {
                    device = "device-new-" + random.nextInt(1 << 20);
                    amount = between(10_000_000, 50_000_000);
                }
                case LISTED_BENEFICIARY -> beneficiary = listed[random.nextInt(listed.length)];
                case REMOTE_ACCESS -> remote = true;
            }
            Instant at = START.plus(SPACING.multipliedBy(sequence));
            return TransactionEvent.builder().eventId(prefix + sequence++).occurredAt(at).channel(Channel.MOBILE)
                    .type(TransactionType.TRANSFER_OUT).amount(Amount.of(amount, CURRENCY))
                    .customerId(customerId(customer)).originator(customers[customer]).beneficiary(beneficiary)
                    .session(new SessionSignals(device, "192.0.2." + (customer % 250), remote, call, "BIOMETRIC"))
                    .build();
        }

        private AccountRef stranger() {
            return new AccountRef("999902", "3" + "%09d".formatted(random.nextInt(1 << 30)));
        }

        /** A round amount from {@code min} to {@code max}, in steps of 1,000 VND. */
        private long between(long min, long max) {
            return (min + random.nextLong(max - min + 1)) / 1_000 * 1_000;
        }
    }
}
