package eu.efti.tst.runner;

import eu.efti.tst.core.ConformanceContext;
import eu.efti.tst.core.RunReport;
import eu.efti.tst.core.TestCaseIds;
import eu.efti.tst.core.TestCaseResult;
import eu.efti.tst.core.TestCatalogue;
import eu.efti.tst.domibus.DomibusStub;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static eu.efti.tst.core.RunReport.Dataset.Status.NOT_COMPLETED;
import static eu.efti.tst.core.RunReport.Dataset.Status.NOT_REQUESTED;
import static eu.efti.tst.core.RunReport.Dataset.Status.USED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

class MainTest {

    private final ByteArrayOutputStream output = new ByteArrayOutputStream();

    private int run(String... args) {
        return Main.run(args, new BufferedReader(new StringReader("")), new PrintStream(output, true, StandardCharsets.UTF_8));
    }

    @Test
    void wrongArgumentsAreATechnicalError() {
        assertThat(run()).isEqualTo(Main.TECHNICAL_ERROR);
        assertThat(output.toString(StandardCharsets.UTF_8)).contains("Usage");
    }

    /** The example configuration in the folder, with the Domibus WS plugin at the given URL. */
    private static Path config(Path dir, String domibusUrl) throws IOException {
        return Files.writeString(dir.resolve("tst.yml"), Files.readString(Path.of("tst.example.yml"))
                .replace("http://localhost:18080/domibus/services/wsplugin", domibusUrl)
                .replace("../formats", Path.of("..", "formats").toAbsolutePath().toString().replace('\\', '/')));
    }

    @Test
    void unreachableDomibusIsATechnicalErrorWithoutReport(@TempDir Path dir) throws IOException {
        assertThat(run("--config", config(dir, "http://127.0.0.1:9/domibus/services/wsplugin").toString()))
                .isEqualTo(Main.TECHNICAL_ERROR);
        assertThat(output.toString(StandardCharsets.UTF_8)).contains("Technical error");
        assertThat(dir.resolve("target")).doesNotExist();
    }

    @Test
    void closedConsoleIsATechnicalErrorThatLeavesTheEvidence(@TempDir Path dir) throws IOException {
        try (DomibusStub domibus = DomibusStub.start()) {
            assertThat(run("--config", config(dir, domibus.url().toString()).toString())).isEqualTo(Main.TECHNICAL_ERROR);
        }

        assertThat(output.toString(StandardCharsets.UTF_8)).contains("Technical error, run stopped: No operator input")
                .doesNotContain("Overall result");
        Path report = dir.resolve("target").resolve("tst-report");
        RunReport incomplete = RunReport.fromJson(Files.readString(report.resolve("run.json")));
        assertThat(incomplete.incomplete()).startsWith("technical error: No operator input")
                .endsWith("requested but not completed: DS1");
        assertThat(incomplete.passed()).isFalse();
        assertThat(incomplete.results()).allSatisfy(result -> assertThat(result.outcome()).isEqualTo(TestCaseResult.Outcome.NOT_APPLICABLE));
        assertThat(Files.readString(report.resolve("report.html")))
                .contains("Incomplete run:</b> technical error", "NOT DETERMINED");
    }

    @Test
    void runWhoseReceiptStatusesCannotBeQueriedIsIncompleteAndCannotPass(@TempDir Path dir) throws IOException {
        SimulatedPlatform platform = new SimulatedPlatform();
        platform.statusQueriesFail = true;

        assertThat(execute(platform, dir, Fixtures.DS1_PLAN)).isEqualTo(Main.TECHNICAL_ERROR);

        RunReport incomplete = RunReport.fromJson(Files.readString(dir.resolve("run.json")));
        assertThat(incomplete.passed()).isFalse();
        assertThat(incomplete.incomplete()).startsWith("technical error: The AS4 status of ")
                .endsWith("messages sent to the platform could not be queried, so their receipt is unknown");
        assertThat(incomplete.results()).filteredOn(result -> result.outcome() == TestCaseResult.Outcome.FAILED).isEmpty();
        assertThat(incomplete.results()).filteredOn(result -> List.of("T-ALL-01", "T-ALL-02").contains(result.testCaseId()))
                .extracting(TestCaseResult::outcome).containsOnly(TestCaseResult.Outcome.NOT_APPLICABLE);
    }

    /** Runs the scenario against the simulated platform with the example configuration, reporting to the folder. */
    private int execute(SimulatedPlatform platform, Path outputDir, DatasetPlan... plans) {
        Duration timeout = Duration.ofMillis(300);
        RunnerConfig example = RunnerConfig.load(Path.of("tst.example.yml"));
        RunnerConfig config = new RunnerConfig(example.formatDir(), outputDir, example.domibus(), example.gate(),
                example.platform(), example.report(), example.datasets());
        GateEngine engine = new GateEngine(Fixtures.FORMAT, platform, new ConformanceContext(Fixtures.GATE, Fixtures.PLATFORM,
                timeout, ConformanceContext.Mode.TEST_RUN), Duration.ofMillis(10), timeout, message -> { });
        return Main.execute(config, Fixtures.CATALOGUE, List.of(plans), engine, new Scenario(engine, platform, timeout, Duration.ZERO, Duration.ofSeconds(5)),
                new PrintStream(output, true, StandardCharsets.UTF_8));
    }

    @Test
    void passedRunExitsWithZeroAndWritesTheReport(@TempDir Path dir) throws IOException {
        assertThat(execute(new SimulatedPlatform(), dir, Fixtures.DS1_PLAN)).isEqualTo(Main.PASSED);

        assertThat(output.toString(StandardCharsets.UTF_8)).contains("Overall result: PASSED");
        RunReport report = RunReport.fromJson(Files.readString(dir.resolve("run.json")));
        assertThat(report.passed()).isTrue();
        assertThat(report.incomplete()).isNull();
        assertThat(report.meta().responseTimeLimit()).isEqualTo(Duration.ofMillis(300));
        // all data sets of the catalogue with their test data, only the requested one as used
        assertThat(report.meta().datasets()).extracting(RunReport.Dataset::key, RunReport.Dataset::testData, RunReport.Dataset::status)
                .startsWith(tuple("DS1", "ds1-road", USED), tuple("DS1C", "ds1c-road-cabotage", NOT_REQUESTED))
                .endsWith(tuple("DS4", "ds4-air", NOT_REQUESTED), tuple("W", null, NOT_REQUESTED));
        assertThat(report.meta().profile()).isEqualTo("Basic road (P01)");
        assertThat(Files.readString(dir.resolve("report.html"))).contains("no applicable test case failed");
    }

    @Test
    void failedRunExitsWithOne(@TempDir Path dir) throws IOException {
        assertThat(execute(new SimulatedPlatform(SimulatedPlatform.Fault.IGNORES_SUBSETS), dir, Fixtures.DS1_PLAN)).isEqualTo(Main.FAILED);

        assertThat(output.toString(StandardCharsets.UTF_8)).contains("Overall result: FAILED");
        assertThat(RunReport.fromJson(Files.readString(dir.resolve("run.json"))).passed()).isFalse();
        assertThat(Files.readString(dir.resolve("report.html"))).contains("at least one test case failed");
    }

    @Test
    void runStoppedBetweenDataSetsReportsTheFirstAndNamesTheOthers(@TempDir Path dir) throws IOException {
        SimulatedPlatform platform = new SimulatedPlatform().holding(Fixtures.DS1_PLAN, Fixtures.plan("DS2"), Fixtures.plan("DS4"));
        platform.stopsAtDataSet = 2;

        assertThat(execute(platform, dir, Fixtures.DS1_PLAN, Fixtures.plan("DS2"), Fixtures.plan("DS4"))).isEqualTo(Main.TECHNICAL_ERROR);

        RunReport report = RunReport.fromJson(Files.readString(dir.resolve("run.json")));
        assertThat(report.incomplete()).endsWith("requested but not completed: DS2, DS4");
        assertThat(report.passed()).isFalse();
        assertThat(report.results()).noneMatch(result -> result.outcome() == TestCaseResult.Outcome.FAILED);
        assertThat(report.results()).filteredOn(result -> "DS1".equals(result.datasetKey()))
                .filteredOn(result -> result.testCaseId().startsWith("T-RUN"))
                .allSatisfy(result -> assertThat(result.outcome()).isEqualTo(TestCaseResult.Outcome.PASSED));
        assertThat(report.results()).filteredOn(result -> "DS4".equals(result.datasetKey())).isNotEmpty()
                .allSatisfy(result -> assertThat(result.outcome()).isEqualTo(TestCaseResult.Outcome.NOT_APPLICABLE));
        assertThat(report.results()).filteredOn(result -> result.testCaseId().equals("T-NEG-03"))
                .singleElement().satisfies(result -> assertThat(result.outcome()).isEqualTo(TestCaseResult.Outcome.PASSED));
        assertThat(report.meta().datasets()).extracting(RunReport.Dataset::key, RunReport.Dataset::status)
                .contains(tuple("DS1", USED), tuple("DS2", NOT_COMPLETED), tuple("DS4", NOT_COMPLETED), tuple("DS3", NOT_REQUESTED));
        assertThat(report.meta().profile()).isEqualTo("Basic road (P01), Basic rail (P02), Basic aviation (P04)");
        assertThat(Files.readString(dir.resolve("report.html"))).contains("NOT DETERMINED", "yes, not completed");
    }

    @Test
    void failureInAFinishedDataSetFailsAStoppedRun(@TempDir Path dir) throws IOException {
        SimulatedPlatform platform = new SimulatedPlatform(SimulatedPlatform.Fault.IGNORES_SUBSETS).holding(Fixtures.DS1_PLAN, Fixtures.plan("DS2"));
        platform.stopsAtDataSet = 2;

        assertThat(execute(platform, dir, Fixtures.DS1_PLAN, Fixtures.plan("DS2"))).isEqualTo(Main.TECHNICAL_ERROR);

        assertThat(Files.readString(dir.resolve("report.html"))).contains("at least one test case failed");
    }

    @Test
    void everyTestCaseIdOfTheRunnerIsInTheCatalogue() {
        var catalogued = TestCatalogue.load().sections().stream().flatMap(section -> section.testCases().stream())
                .map(TestCatalogue.TestCase::id).toList();

        assertThat(Arrays.stream(TestCaseIds.class.getDeclaredFields())
                .filter(field -> Modifier.isStatic(field.getModifiers()) && field.getType() == String.class)
                .map(field -> {
                    try {
                        return (String) field.get(null);
                    } catch (IllegalAccessException e) {
                        throw new IllegalStateException(e);
                    }
                }))
                .isNotEmpty()
                .allMatch(catalogued::contains);
        assertThat(catalogued).containsAll(TestCaseIds.MONITORED).containsAll(TestCaseIds.LIFECYCLE)
                .containsAll(TestCaseIds.NEGATIVE_EXECUTED);
    }
}
