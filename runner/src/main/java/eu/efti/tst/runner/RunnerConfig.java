package eu.efti.tst.runner;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Run configuration, read from a YAML file. Relative paths are resolved against the folder of that file.
 *
 * @param formatDir folder of the message format ({@code formats/<version>})
 * @param outputDir folder the report files are written to
 * @param datasets  keys of the data sets to run, in this order, unless the command line overrides them
 */
public record RunnerConfig(Path formatDir, Path outputDir, Domibus domibus, Gate gate, Platform platform,
                           Report report, List<String> datasets) {

    /** WS plugin of the gate's Domibus domain. */
    public record Domibus(URI url, String user, String password, String partyId) {
        public Domibus {
            require(url, "domibus.url");
            require(user, "domibus.user");
            require(password, "domibus.password");
            require(partyId, "domibus.partyId");
        }
    }

    /** @param id gate identifier used in UILs */
    public record Gate(String id) {
        public Gate {
            require(id, "gate.id");
        }
    }

    /**
     * @param partyId AS4 party ID of the platform's access point
     * @param id      registered platform identifier used in UILs
     */
    public record Platform(String partyId, String id) {
        public Platform {
            require(partyId, "platform.partyId");
            require(id, "platform.id");
        }
    }

    /** Metadata shown in the report. */
    public record Report(String platformName, String platformVersion, String company) {
        public Report {
            require(platformName, "report.platformName");
            require(platformVersion, "report.platformVersion");
            require(company, "report.company");
        }
    }

    public RunnerConfig {
        require(formatDir, "formatDir");
        outputDir = Objects.requireNonNullElse(outputDir, Path.of("tst-report"));
        require(domibus, "domibus");
        require(gate, "gate");
        require(platform, "platform");
        require(report, "report");
        if (datasets == null || datasets.isEmpty()) {
            throw new IllegalArgumentException("datasets must list at least one data set");
        }
        datasets = List.copyOf(datasets);
    }

    private static void require(Object value, String name) {
        if (value == null || value instanceof String text && text.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    /**
     * @throws IllegalArgumentException      if a required value is missing or invalid
     * @throws tools.jackson.core.JacksonException if the file cannot be read or is not valid YAML of this structure
     */
    public static RunnerConfig load(Path file) {
        RunnerConfig config = YAMLMapper.builder()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build()
                .readValue(file.toFile(), RunnerConfig.class);
        Path base = file.toAbsolutePath().getParent();
        return new RunnerConfig(base.resolve(config.formatDir), base.resolve(config.outputDir), config.domibus,
                config.gate, config.platform, config.report, config.datasets);
    }
}
