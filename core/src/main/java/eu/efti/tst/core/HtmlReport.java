package eu.efti.tst.core;

import eu.efti.tst.core.TestCaseResult.Outcome;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Renders a {@link RunReport} as a self-contained HTML page structured like the test case description:
 * metadata, summary, the test data sets, one table per section of the {@link TestCatalogue} with the ID, title, and
 * results of each test case (per data set where the section says so), the findings of failed test cases, and the
 * message transcript as an appendix.
 */
public final class HtmlReport {

    private static final String CSS = Resources.read("report.css") + MessageTable.CSS;

    private HtmlReport() {
    }

    private static String esc(String text) {
        return text == null ? "" : Xml.escape(text);
    }

    private static final String NOT_DETERMINED = "<span class=\"badge open\">NOT DETERMINED</span>";

    private static String badge(Outcome outcome) {
        return switch (outcome) {
            case PASSED -> "<span class=\"badge pass\">PASSED</span>";
            case FAILED -> "<span class=\"badge fail\">FAILED</span>";
            case NOT_APPLICABLE -> "<span class=\"badge na\">N/A</span>";
        };
    }

    private static String note(TestCaseResult result) {
        return result.note() == null ? "" : "<div class=\"ref\">" + esc(result.note()) + "</div>";
    }

    private static String metaLine(String label, String valueHtml) {
        return "  <label>" + label + "</label><div class=\"val\">" + valueHtml + "</div>\n";
    }

    private static String metaBlock(RunReport report) {
        RunReport.Meta meta = report.meta();
        boolean failed = report.results().stream().anyMatch(result -> result.outcome() == Outcome.FAILED);
        String overall = failed
                ? badge(Outcome.FAILED) + " &mdash; at least one test case failed"
                : report.incomplete() != null
                ? NOT_DETERMINED + " &mdash; the run is incomplete"
                : badge(Outcome.PASSED) + " &mdash; no applicable test case failed";
        String datasetsUsed = meta.datasets().stream()
                .filter(RunReport.Dataset::requested)
                .map(dataset -> esc(dataset.key()) + " &mdash; " + esc(dataset.testData()))
                .collect(Collectors.collectingAndThen(Collectors.joining(", "),
                        used -> used.isEmpty() ? "none" : used + "; see section 1.1"));
        String responseTimeLimit = meta.responseTimeLimit() == null ? "" : meta.responseTimeLimit().toSeconds() + " s";
        return metaLine("eFTI platform under test (product name, version)", esc(meta.platformUnderTest()))
                + metaLine("Company (eFTI service provider / economic operator)", esc(meta.company()))
                + metaLine("Platform identifier (test environment)", esc(meta.platformId()))
                + metaLine("Gate identifier (test environment)", esc(meta.gateId()))
                + metaLine("Test suite / version", esc(meta.suiteVersion()))
                + metaLine("Message format", esc(meta.formatVersion()))
                + metaLine("Test data sets used", datasetsUsed)
                + metaLine("Profile tested", esc(meta.profile()))
                + metaLine("Response time limit (T-ALL-04)", responseTimeLimit)
                + metaLine("Test run start / end (UTC)",
                        esc(String.valueOf(meta.start())) + " &mdash; " + esc(String.valueOf(meta.end())))
                + metaLine("Overall result", overall);
    }

    private static String findings(List<TestCaseResult> results, boolean perDataset) {
        String items = results.stream()
                .flatMap(result -> result.findings().stream().map(finding -> "<li>"
                        + (perDataset ? esc(result.datasetKey()) + ": " : "") + esc(finding.detail())
                        + "<span class=\"ref\">AS4 message " + esc(Objects.requireNonNullElse(finding.as4MessageId(), "-"))
                        + "</span></li>"))
                .collect(Collectors.joining("\n"));
        return items.isEmpty() ? "" : "<ul class=\"findings\">\n" + items + "\n</ul>";
    }

    /** Every distinct note once, naming its data sets unless all results of the test case share it. */
    private static String notes(List<TestCaseResult> results, boolean perDataset) {
        Map<String, List<String>> datasetsByNote = results.stream()
                .filter(result -> result.note() != null)
                .collect(Collectors.groupingBy(TestCaseResult::note, LinkedHashMap::new,
                        Collectors.mapping(result -> String.valueOf(result.datasetKey()), Collectors.toList())));
        return datasetsByNote.entrySet().stream()
                .map(entry -> "<div class=\"ref\">"
                        + (perDataset && entry.getValue().size() < results.size() ? esc(String.join(", ", entry.getValue())) + ": " : "")
                        + esc(entry.getKey()) + "</div>")
                .collect(Collectors.joining("\n"));
    }

    private static String row(TestCatalogue.TestCase testCase, List<TestCaseResult> results, boolean perDataset) {
        String title = (testCase.choreographed() ? "<span class=\"choreo\">&#9670;</span> " : "") + esc(testCase.title());
        String heading = testCase.tag() == null ? "<b>" + title + "</b>"
                : "<div class=\"tch\"><b>" + title + "</b><span class=\"tag\">" + esc(testCase.tag()) + "</span></div>";
        String outcomes = results.stream()
                .map(result -> perDataset
                        ? "<div class=\"dsres\"><span class=\"k\">" + esc(result.datasetKey()) + "</span>"
                        + badge(result.outcome()) + "</div>"
                        : badge(result.outcome()))
                .collect(Collectors.joining("\n"));
        return "<tr><td class=\"id\">" + esc(testCase.id()) + "</td><td>" + heading
                + notes(results, perDataset) + findings(results, perDataset) + "</td>\n<td class=\"res\">" + outcomes + "</td></tr>\n";
    }

    /**
     * The results of a test case for a data set (or run-wide), a not-applicable placeholder if there is none.
     *
     * @param datasetKey the data set, null for run-wide results
     */
    private static List<TestCaseResult> resultsFor(RunReport report, String testCaseId, String datasetKey) {
        List<TestCaseResult> results = report.results().stream()
                .filter(result -> result.testCaseId().equals(testCaseId) && Objects.equals(result.datasetKey(), datasetKey))
                .toList();
        return results.isEmpty()
                ? List.of(new TestCaseResult(testCaseId, datasetKey, Outcome.NOT_APPLICABLE, null, List.of()))
                : results;
    }

    /** The results of a test case per requested data set or run-wide, as the section prescribes. */
    private static List<TestCaseResult> resultsFor(RunReport report, TestCatalogue.Section section, TestCatalogue.TestCase testCase) {
        return section.perDataset()
                ? report.meta().datasets().stream().filter(RunReport.Dataset::requested)
                        .flatMap(dataset -> resultsFor(report, testCase.id(), dataset.key()).stream()).toList()
                : resultsFor(report, testCase.id(), null);
    }

    /** The summary of the data set's results; these are the ones of the sections reported per data set. */
    private static String datasetResult(RunReport.Dataset dataset, List<TestCaseResult> results) {
        if (!dataset.requested()) {
            return badge(Outcome.NOT_APPLICABLE);
        }
        if (results.stream().anyMatch(result -> dataset.key().equals(result.datasetKey()) && result.outcome() == Outcome.FAILED)) {
            return badge(Outcome.FAILED);
        }
        return dataset.status() == RunReport.Dataset.Status.NOT_COMPLETED ? NOT_DETERMINED : badge(Outcome.PASSED);
    }

    private static String datasets(List<RunReport.Dataset> datasets, List<TestCaseResult> results) {
        return datasets.stream()
                .map(dataset -> "<tr><td class=\"id\">" + esc(dataset.key()) + "</td><td class=\"ref\">"
                        + (dataset.testData() == null ? "-" : esc(dataset.testData()))
                        + "</td><td><b>" + esc(dataset.title()) + "</b></td><td class=\"res\">" + switch (dataset.status()) {
                            case USED -> "yes";
                            case NOT_COMPLETED -> "yes, not completed";
                            case NOT_REQUESTED -> "no";
                        } + "</td><td class=\"res\">" + datasetResult(dataset, results) + "</td></tr>\n")
                .collect(Collectors.joining());
    }

    private static String section(TestCatalogue.Section section, RunReport report) {
        String header = section.perDataset()
                ? "<table class=\"tc par\">\n<tr><th>ID</th><th>Test case</th><th>Data set / result</th></tr>\n"
                : "<table class=\"tc\">\n<tr><th>ID</th><th>Test case</th><th>Result</th></tr>\n";
        StringBuilder html = new StringBuilder("<h3><span class=\"num\">" + esc(section.id()) + "</span>"
                + esc(section.title()) + "</h3>\n");
        String group = null;
        boolean tableOpen = false;
        for (TestCatalogue.TestCase testCase : section.testCases()) {
            if (!tableOpen || !Objects.equals(group, testCase.group())) {
                if (tableOpen) {
                    html.append("</table>\n");
                }
                group = testCase.group();
                if (group != null) {
                    html.append("<h4>").append(esc(group)).append("</h4>\n");
                }
                html.append(header);
                tableOpen = true;
            }
            html.append(row(testCase, resultsFor(report, section, testCase), section.perDataset()));
        }
        if (tableOpen) {
            html.append("</table>\n");
        }
        return html.toString();
    }

    /** @param format the message format of the run, to tell the type and status of the messages in the transcript */
    public static String render(RunReport report, TestCatalogue catalogue, Format format) {
        RunReport.Meta meta = report.meta();
        StringBuilder sections = new StringBuilder();
        catalogue.sections().forEach(section -> sections.append(section(section, report)));
        Set<String> catalogued = catalogue.sections().stream()
                .flatMap(section -> section.testCases().stream())
                .map(TestCatalogue.TestCase::id)
                .collect(Collectors.toSet());
        List<TestCaseResult> uncatalogued = report.results().stream()
                .filter(result -> !catalogued.contains(result.testCaseId()))
                .toList();
        if (!uncatalogued.isEmpty()) {
            sections.append("<h3>Results of test cases not in the catalogue</h3>\n<table class=\"tc\">\n")
                    .append("<tr><th>ID</th><th>Findings</th><th>Data set</th><th>Result</th></tr>\n");
            uncatalogued.forEach(result -> sections.append("<tr><td class=\"id\">").append(esc(result.testCaseId()))
                    .append("</td><td>").append(findings(List.of(result), false)).append("</td><td class=\"ref\">")
                    .append(esc(Objects.requireNonNullElse(result.datasetKey(), ""))).append("</td><td class=\"res\">")
                    .append(badge(result.outcome())).append(note(result)).append("</td></tr>\n"));
            sections.append("</table>\n");
        }
        String incomplete = report.incomplete() == null ? ""
                : "<div class=\"warn\"><b>Incomplete run:</b> " + esc(report.incomplete()) + "</div>\n";
        Map<Outcome, Long> counts = new EnumMap<>(Outcome.class);
        Stream.concat(catalogue.sections().stream().flatMap(section -> section.testCases().stream()
                        .flatMap(testCase -> resultsFor(report, section, testCase).stream())), uncatalogued.stream())
                .forEach(result -> counts.merge(result.outcome(), 1L, Long::sum));
        long failed = counts.getOrDefault(Outcome.FAILED, 0L);

        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>Test Report &mdash; Automated Test Suite for eFTI Platforms</title>
                <style>
                %s</style>
                </head>
                <body>
                <div class="page">
                <h1>Test Report &mdash; Automated Test Suite for eFTI Platforms</h1>
                <p class="subtitle">Automated testing of the machine-to-machine interface (eDelivery/AS4) of the eFTI
                platform identified below by a test runner that plays the eFTI Gate. The test cases and their legal references are described
                in the test case description.</p>
                %s<div class="meta">
                %s</div>
                <div class="summary">
                  <div class="box pass"><div class="n">%d</div><div class="l">passed</div></div>
                  <div class="box%s"><div class="n">%d</div><div class="l">failed</div></div>
                  <div class="box na"><div class="n">%d</div><div class="l">not applicable</div></div>
                </div>
                <h2><span class="num">1</span>Test setup</h2>
                <h3><span class="num">1.1</span>Test data sets</h3>
                <p>Each requested data set is run once through the lifecycle scenario of section 2.1. Section 2 shows
                the requested data sets only. The result of a data set covers its tests in sections 2.1 and 2.2; the
                tests of sections 2.3 and 2.4 count towards the overall result only.</p>
                <table class="tc na">
                <tr><th>ID</th><th>Test data</th><th>Data set</th><th>Requested</th><th>Result</th></tr>
                %s</table>
                <h2><span class="num">2</span>Test cases and results</h2>
                %s
                <h2><span class="num">A</span>Message transcript</h2>
                %s                <footer>Report automatically generated by %s &middot; message format %s</footer>
                </div>
                </body>
                </html>
                """.formatted(CSS, incomplete, metaBlock(report), counts.getOrDefault(Outcome.PASSED, 0L),
                failed > 0 ? " fail" : "", failed, counts.getOrDefault(Outcome.NOT_APPLICABLE, 0L),
                datasets(meta.datasets(), report.results()), sections,
                MessageTable.render(report.transcript().stream().map(format::parse).toList()), esc(meta.suiteVersion()), esc(meta.formatVersion()));
    }
}
