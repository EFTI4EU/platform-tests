package eu.efti.tst.core;

import eu.efti.tst.core.TestCaseResult.Outcome;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class ReportTest {

    private static final TestCatalogue CATALOGUE = TestCatalogue.load();
    private static final MessageRecord REQUEST = new MessageRecord("req-1@gate", Fixtures.T0,
            MessageRecord.Direction.TO_PLATFORM, "gate", "platform", "T-RUN-04-conv-1", null, "<uilQuery requestId=\"r&1\"/>");
    private static final MessageRecord RESPONSE = new MessageRecord("msg-42@platform", Fixtures.T0.plusSeconds(2),
            MessageRecord.Direction.FROM_PLATFORM, "platform", "gate", null, REQUEST.as4MessageId(), "<uilResponse/>");
    private static final Finding FINDING = new Finding("T-DAT-02", "msg-42@platform", "Status <500> without description");

    private static final List<RunReport.Dataset> DATASETS = List.of(
            new RunReport.Dataset("DS1", "Road (basic road, P01)", "ds1-road.xml", RunReport.Dataset.Status.USED),
            new RunReport.Dataset("DS2", "Rail (basic rail, P02)", "ds2-rail.xml", RunReport.Dataset.Status.NOT_REQUESTED));

    private static RunReport.Meta meta(String platformUnderTest, List<RunReport.Dataset> datasets) {
        return new RunReport.Meta(platformUnderTest, "Musterfracht GmbH", "DE-PF-TEST-0042", "DE-GATE-TEST",
                "tc-efti-platform-conformance v1.0.0", "ri-v0.5", "Basic road (P01)", datasets, Duration.ofSeconds(60),
                Fixtures.T0, Fixtures.T0.plusSeconds(3600));
    }

    private static RunReport report(List<TestCaseResult> results) {
        return new RunReport(meta("TransFreight Cloud, v4.2.1", DATASETS), results, List.of(REQUEST, RESPONSE), null);
    }

    @Test
    void catalogueContainsAllTestCasesOfTheTestCaseDescription() throws IOException {
        assertThat(CATALOGUE.sections()).extracting(TestCatalogue.Section::id).containsExactly("2.1", "2.2", "2.3", "2.4");
        assertThat(CATALOGUE.sections()).extracting(TestCatalogue.Section::perDataset).containsExactly(true, true, false, false);
        TestCatalogue.TestCase first = CATALOGUE.sections().getFirst().testCases().getFirst();
        assertThat(first.title()).isEqualTo("Create data set - draft not visible to authorities");
        assertThat(first.choreographed()).isTrue();
        assertThat(CATALOGUE.sections().get(3).testCases().getFirst().group()).isEqualTo("Connection and transport security");

        String description = Files.readString(Path.of("../docs/testcase-description.md"));
        List<String> describedIds = Pattern.compile("^\\| (T-[A-Z]+-\\d+) \\|", Pattern.MULTILINE).matcher(description)
                .results().map(match -> match.group(1)).toList();
        assertThat(CATALOGUE.sections().stream().flatMap(section -> section.testCases().stream()).map(TestCatalogue.TestCase::id))
                .hasSize(26).containsExactlyElementsOf(describedIds);
    }

    @Test
    void jsonRoundTripPreservesTheReport() {
        RunReport report = report(List.of(
                new TestCaseResult("T-DAT-02", "DS1", Outcome.FAILED, null, List.of(FINDING)),
                new TestCaseResult("T-ALL-04", null, Outcome.PASSED, "max 2.4 s", List.of())));
        RunReport incomplete = new RunReport(report.meta(), report.results(), report.transcript(), "the run was interrupted");

        String json = report.toJson();

        assertThat(json).contains("\"start\" : \"2026-09-15T07:00:00Z\"", "\"outcome\" : \"FAILED\"");
        assertThat(RunReport.fromJson(json)).isEqualTo(report);
        assertThat(RunReport.fromJson(incomplete.toJson())).isEqualTo(incomplete);
    }

    @Test
    void passedOnlyIfCompleteWithoutFailedResults() {
        List<TestCaseResult> passed = List.of(new TestCaseResult("T-ALL-01", null, Outcome.PASSED, null, List.of()));
        assertThat(report(passed).passed()).isTrue();
        assertThat(report(List.of()).passed()).isTrue();
        assertThat(report(List.of(new TestCaseResult("T-ALL-01", null, Outcome.FAILED, null, List.of()))).passed()).isFalse();
        assertThat(new RunReport(report(passed).meta(), passed, List.of(), "the run was interrupted").passed()).isFalse();
    }

    @Test
    void htmlShowsResultsPerDataSetFindingsAndTranscript() {
        RunReport report = report(List.of(
                new TestCaseResult("T-DAT-02", "DS1", Outcome.FAILED, null, List.of(FINDING)),
                new TestCaseResult("T-RUN-01", "DS1", Outcome.PASSED, "draft <rejected>", List.of()),
                new TestCaseResult("T-ALL-04", null, Outcome.PASSED, "max 2.4 s", List.of()),
                new TestCaseResult("T-XYZ-99", null, Outcome.FAILED, null, List.of())));

        String html = HtmlReport.render(report, CATALOGUE, Fixtures.FORMAT);

        // 12 per-data-set cases x 1 requested data set + 14 run-wide cases + 1 uncatalogued result
        assertThat(html).contains("<div class=\"box pass\"><div class=\"n\">2</div>",
                "<div class=\"box fail\"><div class=\"n\">2</div>", "<div class=\"box na\"><div class=\"n\">23</div>");
        assertThat(html).contains("<span class=\"k\">DS1</span><span class=\"badge na\">N/A</span></div></td>",
                "<span class=\"badge fail\">FAILED</span> &mdash; at least one test case failed",
                "<label>Test data sets used</label><div class=\"val\">DS1 &mdash; ds1-road.xml; see section 1.1</div>",
                "<label>Response time limit (T-ALL-04)</label><div class=\"val\">60 s</div>",
                "DS1: Status &lt;500&gt; without description<span class=\"ref\">AS4 message msg-42@platform</span>",
                "Create data set - draft not visible to authorities</b><div class=\"ref\">draft &lt;rejected&gt;</div>",
                "Response time</b><div class=\"ref\">max 2.4 s</div>",
                "<div class=\"tch\"><b>Data request: wrong platform or Gate identifier</b>"
                        + "<span class=\"tag\">informative</span></div>",
                "<h4>Follow-up communication</h4>", "T-XYZ-99",
                "&lt;uilQuery requestId=&quot;r&amp;1&quot;/&gt;", "TransFreight Cloud, v4.2.1",
                "<dt>Refers to</dt><dd>" + REQUEST.as4MessageId() + "</dd>",
                "Gate &rarr; Platform</td><td class=\"mono\">T-RUN-04</td>", "Platform &rarr; Gate</td><td class=\"mono\"></td>");
        // a data set that was not requested only shows in section 1.1
        assertThat(html).doesNotContain("Status <500>", "Incomplete run", "NOT DETERMINED", "<span class=\"k\">DS2</span>");
        assertThat(html.chars()).allMatch(c -> c < 128);
    }

    @Test
    void htmlShowsANoteOnceForAllDataSetsThatShareIt() {
        List<RunReport.Dataset> datasets = Stream.of("DS1", "DS2", "DS3")
                .map(key -> new RunReport.Dataset(key, key, key + ".xml", RunReport.Dataset.Status.USED)).toList();
        RunReport shared = new RunReport(meta("Cloud", datasets), datasets.stream()
                .map(dataset -> new TestCaseResult("T-DAT-03", dataset.key(), Outcome.PASSED, "same note", List.of())).toList(),
                List.of(), null);
        RunReport differing = new RunReport(meta("Cloud", datasets), List.of(
                new TestCaseResult("T-DAT-03", "DS1", Outcome.PASSED, "same note", List.of()),
                new TestCaseResult("T-DAT-03", "DS2", Outcome.PASSED, "same note", List.of()),
                new TestCaseResult("T-DAT-03", "DS3", Outcome.PASSED, "other note", List.of())), List.of(), null);

        assertThat(HtmlReport.render(shared, CATALOGUE, Fixtures.FORMAT)).containsOnlyOnce("same note")
                .contains("<div class=\"ref\">same note</div>");
        assertThat(HtmlReport.render(differing, CATALOGUE, Fixtures.FORMAT)).containsOnlyOnce("same note")
                .contains("<div class=\"ref\">DS1, DS2: same note</div>", "<div class=\"ref\">DS3: other note</div>");
    }

    @Test
    void htmlEscapesMetadataNotesAndTheIncompleteReason() {
        RunReport report = new RunReport(meta("<b>Cloud</b> & Co", List.of(new RunReport.Dataset("D<S>", "T&T", "<x>", RunReport.Dataset.Status.USED))),
                List.of(new TestCaseResult("T-ALL-04", null, Outcome.NOT_APPLICABLE, "note <i>", List.of())),
                List.of(), "failed: <script>");

        String html = HtmlReport.render(report, CATALOGUE, Fixtures.FORMAT);

        assertThat(html).contains("&lt;b&gt;Cloud&lt;/b&gt; &amp; Co", "D&lt;S&gt; &mdash; &lt;x&gt;", "<b>T&amp;T</b>",
                "<div class=\"ref\">note &lt;i&gt;</div>",
                "<div class=\"warn\"><b>Incomplete run:</b> failed: &lt;script&gt;</div>");
        assertThat(html).doesNotContain("<script>", "<i>", "<x>", "<S>");
    }

    @Test
    void dataSetTableSummarisesTheResultsOfEachDataSetAndIgnoresThoseOfTheRun() {
        RunReport.Dataset passed = new RunReport.Dataset("P", "Passed", "p.xml", RunReport.Dataset.Status.USED);
        RunReport.Dataset failed = new RunReport.Dataset("F", "Failed", "f.xml", RunReport.Dataset.Status.USED);
        RunReport.Dataset stopped = new RunReport.Dataset("S", "Stopped", "s.xml", RunReport.Dataset.Status.NOT_COMPLETED);
        RunReport.Dataset stoppedFailed = new RunReport.Dataset("X", "Stopped failed", "x.xml", RunReport.Dataset.Status.NOT_COMPLETED);
        RunReport.Dataset notRequested = new RunReport.Dataset("N", "Not requested", "n.xml", RunReport.Dataset.Status.NOT_REQUESTED);
        RunReport.Dataset noTestData = new RunReport.Dataset("W", "Waste", null, RunReport.Dataset.Status.NOT_REQUESTED);
        RunReport report = new RunReport(meta("Platform", List.of(passed, failed, stopped, stoppedFailed, notRequested, noTestData)), List.of(
                new TestCaseResult("T-RUN-01", "P", Outcome.PASSED, null, List.of()),
                new TestCaseResult("T-RUN-05", "P", Outcome.NOT_APPLICABLE, "no attachments", List.of()),
                new TestCaseResult("T-DAT-02", "F", Outcome.FAILED, null, List.of(FINDING)),
                new TestCaseResult("T-RUN-01", "S", Outcome.PASSED, null, List.of()),
                new TestCaseResult("T-RUN-01", "X", Outcome.FAILED, null, List.of()),
                new TestCaseResult("T-ALL-03", null, Outcome.FAILED, null, List.of())), List.of(), "the run was interrupted");

        String html = HtmlReport.render(report, CATALOGUE, Fixtures.FORMAT);

        assertThat(html).contains("<th>Requested</th><th>Result</th>",
                "<td class=\"id\">P</td><td class=\"ref\">p.xml</td><td><b>Passed</b></td><td class=\"res\">yes</td>"
                        + "<td class=\"res\"><span class=\"badge pass\">PASSED</span></td>",
                "<b>Failed</b></td><td class=\"res\">yes</td><td class=\"res\"><span class=\"badge fail\">FAILED</span></td>",
                "<b>Stopped</b></td><td class=\"res\">yes, not completed</td><td class=\"res\"><span class=\"badge open\">NOT DETERMINED</span></td>",
                "<b>Stopped failed</b></td><td class=\"res\">yes, not completed</td><td class=\"res\"><span class=\"badge fail\">FAILED</span></td>",
                "<td class=\"ref\">n.xml</td><td><b>Not requested</b></td><td class=\"res\">no</td><td class=\"res\"><span class=\"badge na\">N/A</span></td>",
                "<td class=\"id\">W</td><td class=\"ref\">-</td><td><b>Waste</b></td><td class=\"res\">no</td><td class=\"res\"><span class=\"badge na\">N/A</span></td>");
    }

    @Test
    void htmlOfAnIncompleteRunDoesNotShowAnOverallResult() {
        RunReport report = new RunReport(meta("TransFreight Cloud, v4.2.1", DATASETS),
                List.of(new TestCaseResult("T-RUN-01", "DS1", Outcome.PASSED, null, List.of())), List.of(),
                "the run was interrupted");

        String html = HtmlReport.render(report, CATALOGUE, Fixtures.FORMAT);

        assertThat(html).contains("<span class=\"badge open\">NOT DETERMINED</span> &mdash; the run is incomplete",
                "Incomplete run:</b> the run was interrupted");
        assertThat(html).doesNotContain("no applicable test case failed");
    }

    @Test
    void htmlOfAnIncompleteRunWithAFailedTestCaseShowsFailed() {
        RunReport report = new RunReport(meta("TransFreight Cloud, v4.2.1", DATASETS),
                List.of(new TestCaseResult("T-RUN-01", "DS1", Outcome.FAILED, null, List.of(FINDING))), List.of(),
                "the run was interrupted");

        String html = HtmlReport.render(report, CATALOGUE, Fixtures.FORMAT);

        assertThat(html).contains("<span class=\"badge fail\">FAILED</span> &mdash; at least one test case failed",
                "Incomplete run:</b> the run was interrupted");
        assertThat(html).doesNotContain("NOT DETERMINED");
    }

    @Test
    void htmlOfAnEmptyRunMarksEverythingNotApplicable() {
        String html = HtmlReport.render(new RunReport(new RunReport.Meta(null, null, null, null, null, null, null,
                List.of(), null, Instant.EPOCH, Instant.EPOCH), List.of(), List.of(), null), CATALOGUE, Fixtures.FORMAT);

        assertThat(html).contains("<div class=\"box\"><div class=\"n\">0</div><div class=\"l\">failed</div>",
                "<div class=\"box na\"><div class=\"n\">14</div>",
                "<span class=\"badge pass\">PASSED</span> &mdash; no applicable test case failed",
                "<label>Test data sets used</label><div class=\"val\">none</div>", "No messages.");
    }
}
