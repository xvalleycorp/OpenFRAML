//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.vn;

import java.time.ZoneId;
import java.util.List;
import vn.io.dxa.openframl.pack.ResourceCountryPack;

/**
 * The Vietnam country pack.
 *
 * <p>The gate tables are samples: their thresholds illustrate the typologies seen in Vietnam (authorised push
 * payment scams, remote-control apps, rented mule accounts) and are not legal thresholds. Each bank tunes its own
 * copy, which it keeps confidential. Legal reporting thresholds belong to the reporting tables, with the effective
 * date of the regulation that sets them; each table says what is still to be confirmed against the official
 * gazette.
 */
public final class VietnamPack extends ResourceCountryPack {

    /** Version of the pack's content. */
    public static final String VERSION = "2026.10.2";

    public VietnamPack() {
        super("VN", VERSION, ZoneId.of("Asia/Ho_Chi_Minh"), "VND", VietnamPack.class,
                List.of("rules/gate_decision.v1.stone.json", "rules/gate_fallback.v1.stone.json"),
                List.of("rules/monitor_scenarios.v1.stone.json"),
                List.of("rules/report_transaction.v1.stone.json", "rules/report_daily.v1.stone.json"), "reasons");
    }
}
