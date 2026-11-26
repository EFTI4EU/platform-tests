package eu.efti.tst.core;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import org.w3c.dom.Attr;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the runner and the checks rely on in every format folder ({@code ../formats/*} and the synthetic test format),
 * independent of its element names.
 */
class FormatContractTest {

    private static final Path TEST_DATA_DIR = Path.of("..", "testdata");
    private static final Map<Path, Format> LOADED = new ConcurrentHashMap<>();
    private static final Map<String, MessageKind> REGULAR_TEMPLATES = Map.of(
            "data-request", MessageKind.DATA_REQUEST, "follow-up", MessageKind.FOLLOW_UP,
            "upload-status-success", MessageKind.UPLOAD_STATUS, "upload-status-error", MessageKind.UPLOAD_STATUS,
            "identifier-upload", MessageKind.IDENTIFIER_UPLOAD, "data-response-success", MessageKind.DATA_RESPONSE,
            "data-response-error", MessageKind.DATA_RESPONSE, "follow-up-ack-success", MessageKind.FOLLOW_UP_ACK,
            "follow-up-ack-error", MessageKind.FOLLOW_UP_ACK);

    static Stream<Path> formats() throws IOException {
        try (Stream<Path> folders = Files.list(Path.of("..", "formats"))) {
            return Stream.concat(folders.filter(folder -> Files.exists(folder.resolve("format.properties"))).toList().stream(),
                    Stream.of(Fixtures.FORMAT_DIR));
        }
    }

    private static List<Path> testDataFiles(Path formatDir) {
        try (Stream<Path> files = Files.list(formatDir.resolve("testdata"))) {
            return files.filter(file -> file.toString().endsWith(".xml")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String nameOf(Path testData) {
        return testData.getFileName().toString().replaceFirst("\\.xml$", "");
    }

    /** The subsets per test data set of the catalogue, which the synthetic format has no part in. */
    private static Map<String, Set<String>> catalogued() {
        JsonNode datasets = YAMLMapper.builder().build().readTree(TEST_DATA_DIR.resolve("datasets.yml").toFile()).get("datasets");
        return datasets.valueStream().filter(dataset -> dataset.has("testData")).collect(Collectors.toMap(
                dataset -> dataset.get("testData").asString(),
                dataset -> dataset.get("subsets").valueStream().map(JsonNode::asString).collect(Collectors.toSet())));
    }

    private static Set<String> subsetsOf(Path formatDir, Path testData) {
        return formatDir.equals(Fixtures.FORMAT_DIR) ? Set.of("S1", "S2") : catalogued().get(nameOf(testData));
    }

    /** Every test data set of every format, with the subsets it is designed for. */
    static Stream<Arguments> testData() throws IOException {
        return formats().flatMap(dir -> testDataFiles(dir).stream().map(file -> Arguments.of(dir, file, subsetsOf(dir, file))));
    }

    /** The test data sets of the real formats, each with the operator's values. */
    static Stream<Arguments> realTestData() throws IOException {
        return formats().filter(dir -> !dir.equals(Fixtures.FORMAT_DIR))
                .flatMap(dir -> testDataFiles(dir).stream().map(file -> Arguments.of(dir, file)));
    }

    /** The test data sets holding a signature or stamp statement code, which the platform assigns itself. */
    static Stream<Arguments> testDataWithStatementCodes() throws IOException {
        return testData().filter(arguments -> Fixtures.read((Path) arguments.get()[1]).contains("<statementCode>"));
    }

    private static List<Path> operatorValues() throws IOException {
        try (Stream<Path> files = Files.list(TEST_DATA_DIR)) {
            return files.filter(file -> file.toString().endsWith(".md") && !file.endsWith("README.md")).sorted().toList();
        }
    }

    /** The markdown of a test data set: the one its file name starts with, e.g. ds1-road.md for ds1-road-dg.xml. */
    private static List<Path> operatorValuesOf(Path testData) throws IOException {
        String name = nameOf(testData);
        return operatorValues().stream().filter(file -> {
            String stem = file.getFileName().toString().replaceFirst("\\.md$", "");
            return name.equals(stem) || name.startsWith(stem + "-");
        }).toList();
    }

    /** The values of the element and its descendants, with the path of local names below the root of each. */
    private static Stream<Map.Entry<String, String>> leafValues(Element element, String path) {
        Stream<Map.Entry<String, String>> attributes = IntStream.range(0, element.getAttributes().getLength())
                .mapToObj(i -> (Attr) element.getAttributes().item(i))
                .filter(attribute -> !attribute.getName().startsWith("xmlns"))
                .map(attribute -> Map.entry(path, attribute.getValue()));
        NodeList children = element.getChildNodes();
        List<Element> elements = IntStream.range(0, children.getLength()).mapToObj(children::item)
                .filter(Element.class::isInstance).map(Element.class::cast).toList();
        return Stream.concat(attributes, elements.isEmpty()
                ? Stream.of(Map.entry(path, element.getTextContent().strip()))
                : elements.stream().flatMap(child -> leafValues(child, (path.isEmpty() ? "" : path + "/") + child.getLocalName())));
    }

    private static Element rootOf(Path xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(xml.toFile()).getDocumentElement();
    }

    private static boolean containsToken(String text, String value) {
        return Pattern.compile("(?<![\\p{L}\\p{N}-])" + Pattern.quote(value) + "(?![\\p{L}\\p{N}-])").matcher(text).find();
    }

    private static Format format(Path dir) {
        return LOADED.computeIfAbsent(dir, Format::load);
    }

    private static Map<String, String> allValues(Path dir) {
        Format format = format(dir);
        Path testData = testDataFiles(dir).getFirst();
        String dataset = Fixtures.read(testData);
        return Map.ofEntries(
                Map.entry("requestId", "req-1"), Map.entry("referencedRequestId", "req-0"),
                Map.entry("gateId", Fixtures.GATE), Map.entry("platformId", Fixtures.PLATFORM),
                Map.entry("datasetId", Fixtures.DATASET_ID),
                Map.entry("subsetIds", String.join(" ", subsetsOf(dir, testData))),
                Map.entry("message", "Please check"), Map.entry("status", format.notFoundStatus()),
                Map.entry("description", "Not found"), Map.entry("dataset", dataset),
                Map.entry("identifiers", format.deriveIdentifiers(dataset)));
    }

    private static ParsedMessage parse(Format format, String xml) {
        return format.parse(Fixtures.toPlatform(xml, Instant.now()));
    }

    @ParameterizedTest
    @MethodSource("formats")
    void regularTemplatesRenderValidMessagesOfTheirKind(Path dir) {
        Format format = format(dir);
        Map<String, String> values = allValues(dir);

        REGULAR_TEMPLATES.forEach((template, kind) -> {
            String xml = format.render(template, values);
            assertThat(parse(format, xml).kind()).as(template).isEqualTo(kind);
            assertThat(format.validateSchema(xml)).as(template).isEmpty();
            assertThat(format.validateRules(xml)).as(template).isEmpty();
        });
    }

    @ParameterizedTest
    @MethodSource("formats")
    void successTemplatesAndErrorStatusCodesAgreeWithTheSuccessCodes(Path dir) {
        Format format = format(dir);
        Map<String, String> values = new HashMap<>(allValues(dir));

        for (String prefix : List.of("upload-status", "data-response", "follow-up-ack")) {
            assertThat(parse(format, format.render(prefix + "-success", values)).success()).as(prefix).isTrue();
            for (String status : List.of(format.invalidRequestStatus(), format.notFoundStatus())) {
                values.put("status", status);
                assertThat(parse(format, format.render(prefix + "-error", values)).success()).as(prefix).isFalse();
            }
        }
    }

    @ParameterizedTest
    @MethodSource("formats")
    void invalidFaultTemplatesKeepAllReferencesButViolateTheSchema(Path dir) {
        Format format = format(dir);

        for (String template : List.of("data-request-invalid", "follow-up-invalid")) {
            ParsedMessage message = parse(format, format.render(template, allValues(dir)));
            assertThat(format.validateSchema(message.record().payload())).as(template).isNotEmpty();
            assertThat(message.requestId()).as(template).contains("req-1");
            assertThat(message.gateId()).as(template).contains(Fixtures.GATE);
            assertThat(message.platformId()).as(template).contains(Fixtures.PLATFORM);
            assertThat(message.datasetId()).as(template).contains(Fixtures.DATASET_ID);
        }
    }

    @ParameterizedTest
    @MethodSource("formats")
    void faultTemplatesWithoutRequestIdLackEveryValueAResponseMayEcho(Path dir) {
        Format format = format(dir);

        for (String template : List.of("data-request-missing-request-id", "follow-up-missing-request-id")) {
            ParsedMessage message = parse(format, format.render(template, allValues(dir)));
            assertThat(format.validateSchema(message.record().payload())).as(template).isNotEmpty();
            assertThat(message.requestId()).as(template).isEmpty();
            // otherwise the platform could build a correlatable response, which T-NEG-02/06 then require to be an error
            assertThat(format.acceptedEchoes(message)).as(template).isEmpty();
            assertThat(message.datasetId()).as(template).contains(Fixtures.DATASET_ID);
        }
    }

    @ParameterizedTest
    @MethodSource("formats")
    void unknownSubsetFaultTemplateIsValidAndAddsOneSubsetTheFormatDoesNotKnow(Path dir) {
        Format format = format(dir);
        Map<String, String> values = allValues(dir);

        ParsedMessage message = parse(format, format.render("data-request-unknown-subset", values));

        assertThat(format.validateSchema(message.record().payload())).isEmpty();
        assertThat(format.validateRules(message.record().payload())).isEmpty();
        assertThat(message.subsetIds()).containsAll(List.of(values.get("subsetIds").split(" ")));
        assertThat(format.subsets()).containsAll(List.of(values.get("subsetIds").split(" ")));
        assertThat(message.subsetIds()).filteredOn(id -> !format.subsets().contains(id)).hasSize(1);
    }

    @ParameterizedTest
    @MethodSource("realTestData")
    void operatorValuesHoldEveryValueOfTheTestDataThatThePlatformDoesNotSet(Path dir, Path file) throws Exception {
        assertThat(operatorValuesOf(file)).as("markdown of " + file).hasSize(1);
        String markdown = Files.readString(operatorValuesOf(file).getFirst());
        String values = markdown.substring(Math.max(markdown.indexOf("## Values"), 0));
        Set<String> ignored = format(dir).roundTripIgnored();

        assertThat(leafValues(rootOf(file), ""))
                .filteredOn(leaf -> ignored.stream().noneMatch(path -> leaf.getKey().equals(path) || leaf.getKey().startsWith(path + "/")))
                .isNotEmpty()
                .allSatisfy(leaf -> assertThat(containsToken(values, leaf.getValue())).as(leaf.getKey() + " = " + leaf.getValue()).isTrue());
    }

    @ParameterizedTest
    @MethodSource("realTestData")
    void responseWithoutTheValuesHoldingNonAsciiCharactersFailsTheRoundTrip(Path dir, Path file) {
        Format format = format(dir);
        Set<String> subsets = subsetsOf(dir, file);
        String dataset = Fixtures.read(file);
        String withoutNonAscii = dataset.replaceAll("\\s*<(\\w+)[^>]*>[^<]*[^\\x00-\\x7F<][^<]*</\\1>", "");

        assertThat(format.roundTrip(dataset, format.filterBySubsets(withoutNonAscii, subsets), subsets).differences()).isNotEmpty();
    }

    @ParameterizedTest
    @MethodSource("formats")
    void everyDataSetOfTheCatalogueHasTestDataInTheFormatAndViceVersa(Path dir) {
        if (!dir.equals(Fixtures.FORMAT_DIR)) {
            assertThat(testDataFiles(dir)).extracting(FormatContractTest::nameOf).containsExactlyInAnyOrderElementsOf(catalogued().keySet());
        }
    }

    @ParameterizedTest
    @MethodSource("testData")
    void testDataIsValidAndCompleteForItsSubsets(Path dir, Path file, Set<String> subsets) {
        Format format = format(dir);
        String dataset = Fixtures.read(file);
        String filtered = format.filterBySubsets(dataset, subsets);
        String response = format.render("data-response-success", Map.of("requestId", "req-1", "dataset", filtered));

        assertThat(format.validateSchema(format.render("data-response-success", Map.of("requestId", "req-1",
                "dataset", dataset)))).isEmpty();
        assertThat(format.validateSchema(response)).isEmpty();
        assertThat(format.validateRules(response)).isEmpty();
        assertThat(format.compositionViolations(filtered, subsets)).isEmpty();
        assertThat(format.roundTrip(dataset, filtered, subsets).differences()).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("testDataWithStatementCodes")
    void responseWithoutTheStatementCodesThePlatformAssignsPasses(Path dir, Path file, Set<String> subsets) {
        Format format = format(dir);
        String dataset = Fixtures.read(file);
        String filtered = format.filterBySubsets(dataset.replaceAll("\\s*<confirmedDocumentAuthentication>\\s*"
                + "<statementCode>[^<]*</statementCode>\\s*</confirmedDocumentAuthentication>", ""), subsets);

        assertThat(filtered).doesNotContain("<statementCode>");
        assertThat(format.validateSchema(format.render("data-response-success", Map.of("requestId", "req-1",
                "dataset", filtered)))).isEmpty();
        assertThat(format.compositionViolations(filtered, subsets)).isEmpty();
        assertThat(format.roundTrip(dataset, filtered, subsets).differences()).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("formats")
    void errorAcknowledgementWithoutDescriptionPassesTheRules(Path dir) {
        Format format = format(dir);
        Map<String, String> values = new HashMap<>(allValues(dir));
        values.put("description", "");

        assertThat(format.validateRules(format.render("follow-up-ack-error", values))).isEmpty();
        assertThat(format.validateRules(format.render("data-response-error", values))).singleElement()
                .satisfies(violation -> assertThat(violation.toString()).contains("does not carry an error description"));
    }

    @ParameterizedTest
    @MethodSource("testData")
    void identifiersOfTheDerivedUploadEqualThoseOfTheTestData(Path dir, Path file, Set<String> subsets) {
        Format format = format(dir);
        String dataset = Fixtures.read(file);
        String upload = format.render("identifier-upload", Map.of("requestId", "u-1", "datasetId", Fixtures.DATASET_ID,
                "identifiers", format.deriveIdentifiers(dataset)));
        IdentifierSet fromDataset = format.identifiers(dataset);

        assertThat(format.validateSchema(upload)).isEmpty();
        assertThat(format.validateRules(upload)).isEmpty();
        assertThat(format.identifiers(parse(format, upload).identifiers().orElseThrow())).isEqualTo(fromDataset);
        assertThat(fromDataset.values()).containsOnlyKeys(Arrays.asList(IdentifierType.values()));
        assertThat(fromDataset.get(IdentifierType.MODE_OF_TRANSPORT)).isNotEmpty();
        assertThat(fromDataset.get(IdentifierType.DANGEROUS_GOODS)).isNotEmpty();
        assertThat(format.identifiers(format.filterBySubsets(dataset, subsets)).values())
                .containsOnlyKeys(Arrays.asList(IdentifierType.values()));
    }
}
