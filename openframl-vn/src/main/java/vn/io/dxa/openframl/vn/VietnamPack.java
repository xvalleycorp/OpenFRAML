//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.vn;

import java.net.URI;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import vn.io.dxa.openframl.pack.ListSource;
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
    public static final String VERSION = "2026.10.3";

    public VietnamPack() {
        super("VN", VERSION, ZoneId.of("Asia/Ho_Chi_Minh"), "VND", VietnamPack.class,
                List.of("rules/gate_decision.v1.stone.json", "rules/gate_fallback.v1.stone.json"),
                List.of("rules/monitor_scenarios.v1.stone.json"),
                List.of("rules/report_transaction.v1.stone.json", "rules/report_daily.v1.stone.json"), "reasons");
    }

    /**
     * The lists of the Anti-Money Laundering Law 14/2022/QH15: the blacklist, in two parts, and the State Bank's
     * warning list, beside the UN Security Council list Vietnam implements. The national lists are published as
     * decisions, not in a form a program reads, so the bank converts each to the open CSV form. Which article defines
     * each list is to be confirmed against the official gazette: published texts number them differently.
     */
    @Override
    public List<ListSource> screeningLists() {
        String law = "Anti-Money Laundering Law 14/2022/QH15, definitions (to be confirmed: art. 3 or 4)";
        return List.of(
                new ListSource("UN-SC", "UN Security Council Consolidated List", "UN Security Council",
                        ListSource.Format.UN_SC_XML,
                        Optional.of(URI.create("https://scsanctions.un.org/resources/xml/en/consolidated.xml")),
                        "UN Security Council resolutions, as implemented under " + law),
                new ListSource("VN-BLACKLIST-TERRORISM",
                        "Blacklist: organisations and individuals linked to terrorism and terrorist financing",
                        "Ministry of Public Security", ListSource.Format.OPENFRAML_CSV, Optional.empty(),
                        law + "; transactions of listed parties are delayed (art. 44)"),
                new ListSource("VN-BLACKLIST-PROLIFERATION",
                        "Blacklist: designated organisations and individuals linked to proliferation financing",
                        "Ministry of National Defence", ListSource.Format.OPENFRAML_CSV, Optional.empty(),
                        law + "; transactions of listed parties are delayed (art. 44)"),
                new ListSource("VN-WARNING", "Warning list: organisations and individuals at high risk of money "
                        + "laundering", "State Bank of Vietnam", ListSource.Format.OPENFRAML_CSV, Optional.empty(),
                        law));
    }
}
