//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.benchmark;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/** Latency samples in nanoseconds, added from any thread, up to a fixed capacity. */
final class Samples {

    private final long[] values;
    private final AtomicInteger size = new AtomicInteger();

    Samples(int capacity) {
        values = new long[capacity];
    }

    void add(long nanos) {
        int index = size.getAndIncrement();
        if (index < values.length) {
            values[index] = nanos;
        }
    }

    int count() {
        return Math.min(size.get(), values.length);
    }

    /** The given percentiles (0 to 100), nearest-rank, plus the maximum last. */
    long[] percentiles(double... percents) {
        long[] sorted = Arrays.copyOf(values, count());
        Arrays.sort(sorted);
        long[] result = new long[percents.length + 1];
        for (int i = 0; i < percents.length; i++) {
            int rank = (int) Math.ceil(percents[i] / 100 * sorted.length);
            result[i] = sorted.length == 0 ? 0 : sorted[Math.max(0, rank - 1)];
        }
        result[percents.length] = sorted.length == 0 ? 0 : sorted[sorted.length - 1];
        return result;
    }
}
