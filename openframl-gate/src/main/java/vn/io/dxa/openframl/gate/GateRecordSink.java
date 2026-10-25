//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.gate;

/**
 * Receives the record of every decision, e.g. to publish it to Kafka.
 *
 * <p>Called on the decision path after the outcome is known: implementations must hand the record off (to a queue
 * or an asynchronous producer) and return at once. A sink that throws is logged and does not change the decision.
 */
@FunctionalInterface
public interface GateRecordSink {

    /** Drops every record. */
    GateRecordSink NONE = record -> {};

    void accept(GateRecord record);
}
