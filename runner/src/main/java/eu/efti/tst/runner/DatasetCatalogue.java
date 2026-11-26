package eu.efti.tst.runner;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.nio.file.Path;
import java.util.List;

/**
 * The test data sets, read from {@code testdata/datasets.yml}: what the runner and the report need to know about each,
 * independent of the message format.
 *
 * @param datasets in report order
 */
public record DatasetCatalogue(List<Dataset> datasets) {

    /**
     * @param key                     data set key of the report, e.g. DS1
     * @param title                   what the data set covers, e.g. "Road (basic road, P01)"
     * @param profile                 profile of the draft delegated act the data set covers, null if none
     * @param testData                name of the test data set of the format ({@code testdata/<name>.xml} of the format
     *                                folder, as created by the operator, with the typo); null if there are no test data
     *                                yet, so the data set cannot be requested
     * @param subsets                 the subsets it holds, requested in T-RUN-04 (a); the first one is requested alone
     *                                in T-RUN-04 (b)
     * @param transportMeansTypo      identifier of the transport means as created, contained exactly once in the test
     *                                data set
     * @param transportMeansCorrected identifier of the transport means after the correction step
     */
    public record Dataset(String key, String title, String profile, String testData, List<String> subsets,
                          String transportMeansTypo, String transportMeansCorrected) {
        public Dataset {
            require(key, "datasets[].key");
            require(title, key + ".title");
            if (testData != null) {
                require(transportMeansTypo, key + ".transportMeansTypo");
                require(transportMeansCorrected, key + ".transportMeansCorrected");
                if (subsets == null || subsets.isEmpty()) {
                    throw new IllegalArgumentException(key + ".subsets must list at least one subset");
                }
            }
            subsets = subsets == null ? List.of() : List.copyOf(subsets);
        }
    }

    public DatasetCatalogue {
        if (datasets == null || datasets.isEmpty()) {
            throw new IllegalArgumentException("the data set catalogue lists no data set");
        }
        datasets = List.copyOf(datasets);
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    /**
     * The catalogue of the repository the format folder belongs to: {@code formats/<version>} and {@code testdata/} are
     * siblings, also in the runner's container.
     *
     * @throws tools.jackson.core.JacksonException if the file cannot be read or is not valid YAML of this structure
     */
    public static DatasetCatalogue ofFormat(Path formatDir) {
        return YAMLMapper.builder()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build()
                .readValue(formatDir.resolve("../../testdata/datasets.yml").toFile(), DatasetCatalogue.class);
    }

    /**
     * The data sets with the keys, in the given order.
     *
     * @throws IllegalArgumentException if a key is repeated, unknown or stands for a data set without test data
     */
    public List<Dataset> select(List<String> keys) {
        if (keys.stream().distinct().count() != keys.size()) {
            throw new IllegalArgumentException("a data set is requested more than once: " + keys);
        }
        return keys.stream().map(key -> datasets.stream()
                .filter(dataset -> dataset.key().equals(key) && dataset.testData() != null)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("data set " + key + " cannot be requested, available: "
                        + datasets.stream().filter(dataset -> dataset.testData() != null).map(Dataset::key).toList())))
                .toList();
    }
}
