//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.stream;

import java.io.IOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import vn.io.dxa.openframl.cases.CaseFile;
import vn.io.dxa.openframl.cases.CaseStore;
import vn.io.dxa.openframl.pack.CountryPack;
import vn.io.dxa.openframl.report.DailyReporting;
import vn.io.dxa.openframl.report.ExchangeRates;
import vn.io.dxa.openframl.report.ReportJson;
import vn.io.dxa.openframl.report.ReportPolicy;
import vn.io.dxa.openframl.report.SuspiciousDeadlines;
import vn.io.dxa.openframl.report.SuspiciousTransactionReport;
import vn.io.dxa.openframl.report.TransactionReport;
import vn.io.dxa.openframl.report.WorkingDays;

/**
 * The Daily Report Job: after a business day closes, reads it back from the transaction topic, runs Regulatory
 * Reporting on it, and writes two files of JSON lines to the output directory, readable by the owner only:
 * {@code transaction-reports-<date>.jsonl} and {@code suspicious-reports-<date>.jsonl} (the cases concluded as
 * reported that day). Running it again for a day replaces its files with the same content. Configured from the
 * environment; see {@code docs/stream.md}.
 *
 * <p>Usage: {@code DailyReportJob [yyyy-mm-dd]}; without a date, the business day before today.
 */
public final class DailyReportJob {

    private static final System.Logger LOG = System.getLogger(DailyReportJob.class.getName());

    private DailyReportJob() {}

    public static void main(String[] args) throws IOException {
        Map<String, String> env = System.getenv();
        CountryPack pack = CountryPack.forCountry(env.getOrDefault("OPENFRAML_COUNTRY", "VN"));
        LocalDate day = args.length > 0 ? LocalDate.parse(args[0]) : LocalDate.now(pack.businessZone()).minusDays(1);
        Path out = Path.of(env.getOrDefault("OPENFRAML_REPORT_OUT_DIR", "."));
        Files.createDirectories(out);

        ReportPolicy policy = ReportPolicy.of(pack);
        WorkingDays workingDays = WorkingDays.mondayToFriday(holidays(env.get("OPENFRAML_REPORT_HOLIDAYS")));
        DailyReporting reporting = new DailyReporting(pack, policy, workingDays,
                rates(env.get("OPENFRAML_REPORT_RATES")));
        DailyReporting.Run run;
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(StreamService.consumerConfig(env, null))) {
            KafkaDayReader reader = new KafkaDayReader(consumer,
                    env.getOrDefault("OPENFRAML_STREAM_TOPIC", "openframl.transactions"), pack.businessZone(),
                    Duration.ofMinutes(Long.parseLong(env.getOrDefault("OPENFRAML_REPORT_MARGIN_MINUTES", "60"))));
            run = reporting.run(day, reader.day(day));
            if (reader.invalid() > 0) {
                LOG.log(System.Logger.Level.WARNING, "{0} record(s) of the topic were not events", reader.invalid());
            }
        }
        write(out.resolve("transaction-reports-" + day + ".jsonl"),
                run.reports().stream().map(ReportJson::write).toList());

        CaseStore cases = StreamService.caseStore(env);
        List<CaseFile> concluded = concludedOn(cases.withStatus(CaseFile.Status.CLOSED), day, pack);
        List<SuspiciousTransactionReport> suspicious = concluded.stream().map(SuspiciousTransactionReport::of)
                .toList();
        if (policy.has(ReportPolicy.SUSPICIOUS_ID)) {
            SuspiciousDeadlines deadlines = new SuspiciousDeadlines(pack, policy, workingDays,
                    SuspiciousDeadlines.Detection.valueOf(env.getOrDefault("OPENFRAML_REPORT_DETECTION", "APPROVAL")));
            List<String> lines = new ArrayList<>();
            for (CaseFile c : concluded) {
                lines.add(ReportJson.toJson(SuspiciousTransactionReport.of(c), deadlines.of(c)).toString());
                if (deadlines.concludedLate(c)) {
                    LOG.log(System.Logger.Level.WARNING, "case {0} was concluded after the deadline counted from its "
                            + "first transaction ({1})", c.id(), deadlines.of(c).dueFromTransaction());
                }
            }
            write(out.resolve("suspicious-reports-" + day + ".jsonl"), lines);
        } else {
            write(out.resolve("suspicious-reports-" + day + ".jsonl"),
                    suspicious.stream().map(ReportJson::write).toList());
        }

        LOG.log(System.Logger.Level.INFO, "{0}: {1} transaction(s), {2} threshold report(s), {3} suspicious "
                + "transaction report(s); {4} duplicate(s), {5} of other days, {6} without an exchange rate", day,
                run.transactions(), run.reports().size(), suspicious.size(), run.duplicates(), run.otherDays(),
                run.unconverted());
        if (run.unconverted() > 0) {
            LOG.log(System.Logger.Level.WARNING, "{0} foreign-currency transaction(s) had no rate and were compared "
                    + "with no threshold: set OPENFRAML_REPORT_RATES", run.unconverted());
        }
    }

    /** The cases concluded as reported on the business day, oldest conclusion first. */
    static List<CaseFile> concludedOn(List<CaseFile> closed, LocalDate day, CountryPack pack) {
        Set<String> ids = SuspiciousTransactionReport.fromCases(closed).stream()
                .filter(report -> LocalDate.ofInstant(report.concludedAt(), pack.businessZone()).equals(day))
                .map(SuspiciousTransactionReport::caseId).collect(Collectors.toSet());
        return closed.stream().filter(c -> ids.contains(c.id())).toList();
    }

    /** Written under a temporary name and moved into place, readable by the owner only where the file system can. */
    static void write(Path file, List<String> lines) throws IOException {
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.deleteIfExists(temporary);
        Files.createFile(temporary);
        try {
            Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot restrict the permissions of {0}", file);
        }
        try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
            for (String line : lines) {
                writer.write(line);
                writer.write('\n');
            }
        }
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** One ISO date per line; blank lines and lines starting with {@code #} are ignored. */
    static Set<LocalDate> holidays(String file) throws IOException {
        Set<LocalDate> days = new HashSet<>();
        if (file != null) {
            for (String line : Files.readAllLines(Path.of(file), StandardCharsets.UTF_8)) {
                String day = line.strip();
                if (!day.isEmpty() && !day.startsWith("#")) {
                    days.add(LocalDate.parse(day));
                }
            }
        }
        return days;
    }

    /**
     * {@code currency,rate} per line, the rate in national currency for one unit; the same rates for the whole day.
     * Blank lines and lines starting with {@code #} are ignored.
     */
    static ExchangeRates rates(String file) throws IOException {
        if (file == null) {
            return ExchangeRates.none();
        }
        Map<String, BigDecimal> rates = new HashMap<>();
        int row = 0;
        for (String line : Files.readAllLines(Path.of(file), StandardCharsets.UTF_8)) {
            row++;
            String text = line.strip();
            if (text.isEmpty() || text.startsWith("#")) {
                continue;
            }
            String[] fields = text.split(",");
            if (fields.length != 2 || !fields[0].strip().matches("[A-Z]{3}")) {
                throw new IllegalArgumentException(file + " row " + row + ": expected currency,rate");
            }
            rates.put(fields[0].strip(), new BigDecimal(fields[1].strip()));
        }
        return (currency, date) -> Optional.ofNullable(rates.get(currency));
    }
}
