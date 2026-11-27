package eu.efti.tst.runner;

import eu.efti.tst.core.ConformanceContext;
import eu.efti.tst.core.Format;
import eu.efti.tst.core.HtmlReport;
import eu.efti.tst.core.RunReport;
import eu.efti.tst.core.TestCaseResult;
import eu.efti.tst.core.TestCatalogue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Command line entry point: {@code java -jar runner.jar --config tst.yml [--datasets DS1,DS2]}. Exit code 0 if the
 * platform passed, 1 if a test case failed, 2 on a technical error (invalid configuration, Domibus unreachable, no
 * operator input, run interrupted, ...). A run that started but stopped reports the data sets that went through.
 */
public final class Main {

    static final int PASSED = 0;
    static final int FAILED = 1;
    static final int TECHNICAL_ERROR = 2;

    /** Responses are timestamped when retrieved, so this bounds how much a measured response time overstates. */
    private static final Duration POLL_INTERVAL = Duration.ofMillis(250);
    private static final Duration OUTAGE_LIMIT = Duration.ofMinutes(2);
    /** Domibus retries a message for up to a minute (receptionAwareness of the PMode), so its receipt may come that late. */
    private static final Duration RECEIPT_GRACE = Duration.ofMinutes(1);
    /** A second response to the last request follows the first one closely, so a few seconds show it (T-ALL-03). */
    private static final Duration CLOSING_WAIT = Duration.ofSeconds(5);
    /** So that a run the operator forgot about does not go on forever. */
    private static final Duration OPERATOR_LIMIT = Duration.ofMinutes(15);

    private Main() {
    }

    public static void main(String[] args) {
        System.exit(run(args, new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)), System.out));
    }

    /**
     * @param requested the data sets of the run; the others of the catalogue are reported as not requested
     * @param finished  the keys of the requested data sets whose run ended
     */
    static RunReport.Meta meta(RunnerConfig.Report report, DatasetCatalogue catalogue, List<DatasetCatalogue.Dataset> requested,
                               List<String> finished, ConformanceContext context, String formatVersion, Instant start, Instant end) {
        List<RunReport.Dataset> reported = catalogue.datasets().stream()
                .map(dataset -> new RunReport.Dataset(dataset.key(), dataset.title(), dataset.testData(),
                        !requested.contains(dataset) ? RunReport.Dataset.Status.NOT_REQUESTED
                                : finished.contains(dataset.key()) ? RunReport.Dataset.Status.USED
                                : RunReport.Dataset.Status.NOT_COMPLETED))
                .toList();
        String profiles = requested.stream().map(DatasetCatalogue.Dataset::profile).filter(Objects::nonNull).distinct()
                .collect(Collectors.joining(", "));
        return new RunReport.Meta(report.platformName() + ", " + report.platformVersion(), report.company(),
                context.platformId(), context.gateId(),
                "Test runner " + Objects.requireNonNullElse(Main.class.getPackage().getImplementationVersion(), "dev"),
                formatVersion, profiles.isEmpty() ? "none" : profiles, reported, context.responseTimeout(), start, end);
    }

    /** Writes report.html and run.json to the output folder. */
    static void write(RunReport report, Format format, Path outputDir) throws IOException {
        Files.createDirectories(outputDir);
        Files.writeString(outputDir.resolve("report.html"), HtmlReport.render(report, TestCatalogue.load(), format));
        Files.writeString(outputDir.resolve("run.json"), report.toJson());
    }

    /** Writes the report of a started run exactly once: the final one, or the one of the run that stopped. */
    private static final class Reporter {
        /** The report from the results and the reason why the run stopped (null if it completed). */
        private final BiFunction<List<TestCaseResult>, String, RunReport> report;
        private final Format format;
        private final Path outputDir;
        private final PrintStream out;
        private boolean written;

        Reporter(BiFunction<List<TestCaseResult>, String, RunReport> report, Format format, Path outputDir, PrintStream out) {
            this.report = report;
            this.format = format;
            this.outputDir = outputDir;
            this.out = out;
        }

        synchronized RunReport complete(List<TestCaseResult> results) throws IOException {
            RunReport complete = report.apply(results, null);
            write(complete, format, outputDir);
            written = true;
            return complete;
        }

        synchronized void stopped(Supplier<List<TestCaseResult>> resultsSoFar, String reason) {
            if (written) {
                return;
            }
            written = true;
            try {
                write(report.apply(resultsSoFar.get(), reason), format, outputDir);
                out.println("Report of the data sets so far: " + outputDir.resolve("report.html"));
            } catch (IOException | RuntimeException e) {
                out.println("Writing the report of the stopped run failed: " + e.getMessage());
            }
        }
    }

    private static int technicalError(PrintStream out, Exception e) {
        out.println("Technical error, no test result: " + Objects.requireNonNullElse(e.getMessage(), e.toString()));
        return TECHNICAL_ERROR;
    }

    private static boolean isUsage(String[] args) {
        return (args.length == 2 || args.length == 4 && args[2].equals("--datasets")) && args[0].equals("--config");
    }

    static int run(String[] args, BufferedReader in, PrintStream out) {
        if (!isUsage(args)) {
            out.println("Usage: java -jar runner.jar --config tst.yml [--datasets DS1,DS2]");
            return TECHNICAL_ERROR;
        }
        RunnerConfig config;
        DatasetCatalogue catalogue;
        GateEngine engine;
        Scenario scenario;
        List<DatasetPlan> plans;
        try {
            config = RunnerConfig.load(Path.of(args[1]));
            Format format = Format.load(config.formatDir());
            catalogue = DatasetCatalogue.ofFormat(config.formatDir());
            List<String> keys = args.length == 4 ? List.of(args[3].split(",")) : config.datasets();
            plans = catalogue.select(keys).stream().map(dataset -> DatasetPlan.load(dataset, format)).toList();
            Operator operator = new ConsoleOperator(in, out);
            ConformanceContext context = new ConformanceContext(config.gate().id(), config.platform().id(),
                    ConformanceContext.LEGAL_RESPONSE_TIMEOUT, ConformanceContext.Mode.TEST_RUN);
            engine = new GateEngine(format, new DomibusTransport(config.domibus(), config.platform().partyId(), operator::progress),
                    context, POLL_INTERVAL, OUTAGE_LIMIT, operator::progress);
            scenario = new Scenario(engine, operator, RECEIPT_GRACE, CLOSING_WAIT, OPERATOR_LIMIT);
            engine.discardPending();
        } catch (RuntimeException e) {
            return technicalError(out, e);
        }
        return execute(config, catalogue, plans, engine, scenario, out);
    }

    /** The requested data sets whose run did not end. */
    private static List<String> unfinished(List<DatasetPlan> plans, Scenario scenario) {
        List<String> finished = scenario.finishedKeys();
        return plans.stream().map(DatasetPlan::key).filter(key -> !finished.contains(key)).toList();
    }

    /**
     * Runs the scenario of a started run and writes its report, also if the run stops early.
     *
     * @return the exit code
     */
    static int execute(RunnerConfig config, DatasetCatalogue catalogue, List<DatasetPlan> plans, GateEngine engine,
                       Scenario scenario, PrintStream out) {
        Instant start = Instant.now();
        List<DatasetCatalogue.Dataset> requested = plans.stream().map(DatasetPlan::config).toList();
        Reporter reporter = new Reporter((results, incomplete) -> new RunReport(meta(config.report(), catalogue, requested,
                scenario.finishedKeys(), engine.context(), engine.format().version(), start, Instant.now()), results,
                engine.history().records(), incomplete), engine.format(), config.outputDir(), out);
        Supplier<List<TestCaseResult>> resultsSoFar = () -> Results.stopped(TestCatalogue.load(), scenario.evidenceSoFar(),
                unfinished(plans, scenario), scenario.stepsSoFar());
        Function<String, String> stoppedBecause = reason -> unfinished(plans, scenario).isEmpty() ? reason
                : reason + "; requested but not completed: " + String.join(", ", unfinished(plans, scenario));
        Thread onInterrupt = new Thread(() -> {
            out.println();
            out.println("Interrupted.");
            reporter.stopped(resultsSoFar, stoppedBecause.apply("the run was interrupted"));
            // Exit code of a technical error instead of the one of the signal
            Runtime.getRuntime().halt(TECHNICAL_ERROR);
        });
        Runtime.getRuntime().addShutdownHook(onInterrupt);
        try {
            List<TestCaseResult> results = Results.aggregate(TestCatalogue.load(), scenario.run(plans));
            RunReport report = reporter.complete(results);
            long failed = results.stream().filter(result -> result.outcome() == TestCaseResult.Outcome.FAILED).count();
            out.println();
            out.println("Overall result: " + (report.passed() ? "PASSED" : "FAILED (" + failed + " failed results)"));
            out.println("Report: " + config.outputDir().resolve("report.html"));
            return report.passed() ? PASSED : FAILED;
        } catch (IOException | RuntimeException e) {
            String message = Objects.requireNonNullElse(e.getMessage(), e.toString());
            out.println("Technical error, run stopped: " + message);
            reporter.stopped(resultsSoFar, stoppedBecause.apply("technical error: " + message));
            return TECHNICAL_ERROR;
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(onInterrupt);
            } catch (IllegalStateException e) {
                // the JVM is shutting down, the hook runs anyway
            }
        }
    }
}
