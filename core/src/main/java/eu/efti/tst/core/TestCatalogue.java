package eu.efti.tst.core;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Objects;

/**
 * The test cases of the test suite (ID and title only), grouped by report section as in the test case
 * description ({@code docs/testcase-description.md}), which holds their descriptions and legal
 * references. Loaded from the format-neutral resource {@code test-cases.json}.
 *
 * @param sections report sections in report order
 */
public record TestCatalogue(List<Section> sections) {

    /**
     * @param perDataset whether the test cases of this section are reported once per data set
     */
    public record Section(String id, String title, boolean perDataset, List<TestCase> testCases) {
    }

    /**
     * @param group          subheading within the section, null if none
     * @param tag            scope label such as "all messages" or "informative", null if none
     * @param choreographed  whether the step needs an action of the platform operator
     */
    public record TestCase(String id, String title, String group, String tag, boolean choreographed) {
    }

    public static TestCatalogue load() {
        try (InputStream json = Objects.requireNonNull(TestCatalogue.class.getResourceAsStream("test-cases.json"))) {
            return JsonMapper.builder().build().readValue(json, TestCatalogue.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
