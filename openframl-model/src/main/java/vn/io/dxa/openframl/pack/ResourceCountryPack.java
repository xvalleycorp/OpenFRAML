//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.pack;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A country pack whose files are class path resources next to a class of the pack.
 *
 * <p>Reason messages are UTF-8 properties files named {@code <messages>_<language>.properties}, e.g.
 * {@code reasons_vi.properties}. English ({@code _en}) is required and is the fallback for every other language.
 * Name rules for screening, if the pack has any, are in {@code names.properties}.
 */
public abstract class ResourceCountryPack implements CountryPack {

    private final String countryCode;
    private final String version;
    private final ZoneId businessZone;
    private final String currency;
    private final List<PackResource> decisionTables;
    private final List<PackResource> monitoringTables;
    private final List<PackResource> reportingTables;
    private final Class<?> anchor;
    private final String messages;
    private final Map<String, Properties> messagesByLanguage = new ConcurrentHashMap<>();

    /**
     * @param anchor         resource names are relative to this class's package
     * @param decisionTables the decision table files
     * @param messages       base name of the reason message files
     */
    protected ResourceCountryPack(String countryCode, String version, ZoneId businessZone, String currency,
                                  Class<?> anchor, List<String> decisionTables, String messages) {
        this(countryCode, version, businessZone, currency, anchor, decisionTables, List.of(), messages);
    }

    /**
     * @param anchor           resource names are relative to this class's package
     * @param decisionTables   the gate's decision table files
     * @param monitoringTables Transaction Monitoring's scenario table files
     * @param messages         base name of the reason message files
     */
    protected ResourceCountryPack(String countryCode, String version, ZoneId businessZone, String currency,
                                  Class<?> anchor, List<String> decisionTables, List<String> monitoringTables,
                                  String messages) {
        this(countryCode, version, businessZone, currency, anchor, decisionTables, monitoringTables, List.of(),
                messages);
    }

    /**
     * @param anchor           resource names are relative to this class's package
     * @param decisionTables   the gate's decision table files
     * @param monitoringTables Transaction Monitoring's scenario table files
     * @param reportingTables  Regulatory Reporting's threshold table files
     * @param messages         base name of the reason message files
     */
    protected ResourceCountryPack(String countryCode, String version, ZoneId businessZone, String currency,
                                  Class<?> anchor, List<String> decisionTables, List<String> monitoringTables,
                                  List<String> reportingTables, String messages) {
        this.countryCode = Objects.requireNonNull(countryCode, "countryCode");
        this.version = Objects.requireNonNull(version, "version");
        this.businessZone = Objects.requireNonNull(businessZone, "businessZone");
        this.currency = Objects.requireNonNull(currency, "currency");
        this.anchor = Objects.requireNonNull(anchor, "anchor");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.decisionTables = decisionTables.stream().map(name -> new PackResource(name, resource(name))).toList();
        this.monitoringTables = monitoringTables.stream().map(name -> new PackResource(name, resource(name)))
                .toList();
        this.reportingTables = reportingTables.stream().map(name -> new PackResource(name, resource(name)))
                .toList();
        load("en").orElseThrow(() -> new IllegalStateException(
                "country pack " + countryCode + " has no " + messages + "_en.properties"));
    }

    @Override
    public String countryCode() {
        return countryCode;
    }

    @Override
    public String version() {
        return version;
    }

    @Override
    public ZoneId businessZone() {
        return businessZone;
    }

    @Override
    public String currency() {
        return currency;
    }

    @Override
    public List<PackResource> decisionTables() {
        return decisionTables;
    }

    @Override
    public List<PackResource> monitoringTables() {
        return monitoringTables;
    }

    @Override
    public List<PackResource> reportingTables() {
        return reportingTables;
    }

    @Override
    public Optional<PackResource> nameRules() {
        URL url = anchor.getResource("names.properties");
        return url == null ? Optional.empty() : Optional.of(new PackResource("names.properties", url));
    }

    @Override
    public Optional<String> reasonMessage(String reasonCode, Locale locale) {
        return load(locale.getLanguage()).map(props -> props.getProperty(reasonCode))
                .or(() -> load("en").map(props -> props.getProperty(reasonCode)));
    }

    private Optional<Properties> load(String language) {
        if (!language.matches("[a-z]{2,3}")) {
            return Optional.empty();
        }
        Properties loaded = messagesByLanguage.computeIfAbsent(language, lang -> {
            URL url = anchor.getResource(messages + "_" + lang + ".properties");
            Properties props = new Properties();
            if (url == null) {
                return props;
            }
            try (InputStream in = url.openStream()) {
                props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read " + url, e);
            }
            return props;
        });
        return loaded.isEmpty() ? Optional.empty() : Optional.of(loaded);
    }

    private URL resource(String name) {
        URL url = anchor.getResource(name);
        if (url == null) {
            throw new IllegalStateException("country pack " + countryCode + " is missing " + name);
        }
        return url;
    }
}
