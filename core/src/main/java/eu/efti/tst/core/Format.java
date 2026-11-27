package eu.efti.tst.core;

import net.sf.saxon.s9api.Processor;
import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.Serializer;
import net.sf.saxon.s9api.XPathCompiler;
import net.sf.saxon.s9api.XPathExecutable;
import net.sf.saxon.s9api.XPathSelector;
import net.sf.saxon.s9api.XdmItem;
import net.sf.saxon.s9api.XdmNode;
import net.sf.saxon.s9api.streams.Predicates;
import net.sf.saxon.s9api.streams.Steps;
import org.w3c.dom.Attr;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A message format version, loaded from a format folder ({@code formats/<version>/}) that holds everything
 * format-specific: {@code format.properties} (namespaces, root element per message kind, XPath expressions of the
 * fields), the XSDs, the Schematron rules and the message templates. The code only knows format-neutral concepts
 * ({@link MessageKind}, {@link ParsedMessage}, {@link IdentifierType}), so a new format version only needs a new
 * folder. Instances are immutable and thread-safe.
 */
public final class Format {

    private static final Pattern TEMPLATE_NAME = Pattern.compile("[a-z0-9-]+");
    private static final Pattern PLACEHOLDER = Pattern.compile(
            "\\{\\{#(\\w+)}}(.*?)\\{\\{/\\1}}|\\{\\{\\{(\\w+)}}}|\\{\\{(\\w+)}}", Pattern.DOTALL);
    private static final Pattern XML_DECLARATION = Pattern.compile("^\\s*<\\?xml[^>]*\\?>");
    private static final List<String> FIELDS = List.of("requestId", "referencedRequestId", "gateId", "platformId",
            "datasetId", "status", "description", "message", "subsetIds", "dataset", "identifiers");
    private static final Set<String> XML_FIELDS = Set.of("dataset", "identifiers");
    private static final String LIMITATION_PREFIX = "limitation.";
    private static final Map<String, Function<ParsedMessage, Optional<String>>> ECHOABLE_FIELDS = Map.of(
            "requestId", ParsedMessage::requestId, "referencedRequestId", ParsedMessage::referencedRequestId);

    private final String version;
    private final Path templates;
    private final Path testData;
    private final String roadModeCode;
    private final Processor processor;
    private final Map<QName, MessageKind> kindByRoot;
    private final Map<String, XPathExecutable> fields;
    private final Map<IdentifierType, XPathExecutable> identifierPaths;
    private final Set<String> successStatus;
    private final String invalidRequestStatus;
    private final String notFoundStatus;
    private final List<Function<ParsedMessage, Optional<String>>> followUpAckEchoes;
    private final int maxFollowUpTextLength;
    private final Schema schema;
    private final Schematron schematron;
    private final SubsetSchema datasetSchema;
    private final SubsetSchema identifierSchema;
    private final Set<String> roundTripIgnored;
    private final Map<String, String> limitations;

    private static QName qName(Map<String, String> namespaces, String prefixedName) {
        String[] parts = prefixedName.split(":", 2);
        if (parts.length != 2 || !namespaces.containsKey(parts[0])) {
            throw new IllegalArgumentException("Root element must be prefix:localName with a declared prefix: " + prefixedName);
        }
        return new QName(namespaces.get(parts[0]), parts[1]);
    }

    private static XPathExecutable xpath(XPathCompiler compiler, String expression) {
        try {
            return compiler.compile(expression);
        } catch (SaxonApiException e) {
            throw new IllegalArgumentException("Invalid XPath " + expression + ": " + e.getMessage(), e);
        }
    }

    private static Schema loadSchema(Path xsd) {
        try {
            SchemaFactory factory = SchemaFactory.newDefaultInstance();
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "file");
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            return factory.newSchema(xsd.toFile());
        } catch (SAXException e) {
            throw new IllegalArgumentException("Cannot load XSD " + xsd + ": " + e.getMessage(), e);
        }
    }

    private static String substitute(MatchResult match, Function<String, String> value) {
        if (match.group(1) != null) {
            return Arrays.stream(value.apply(match.group(1)).strip().split("\\s+"))
                    .filter(item -> !item.isEmpty())
                    .map(item -> match.group(2).replace("{{.}}", Xml.escape(item)))
                    .collect(Collectors.joining());
        }
        if (match.group(3) != null) {
            return XML_DECLARATION.matcher(value.apply(match.group(3))).replaceFirst("");
        }
        return Xml.escape(value.apply(match.group(4)));
    }

    /** Moves the element and its descendants from one namespace to another, dropping declarations of the old one. */
    private static void moveToNamespace(Element element, String from, String to) {
        NamedNodeMap attributes = element.getAttributes();
        for (int i = attributes.getLength() - 1; i >= 0; i--) {
            Node attribute = attributes.item(i);
            if (XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attribute.getNamespaceURI()) && from.equals(attribute.getNodeValue())) {
                element.removeAttributeNode((Attr) attribute);
            }
        }
        Xml.childElements(element).forEach(child -> moveToNamespace(child, from, to));
        if (from.equals(element.getNamespaceURI())) {
            element.getOwnerDocument().renameNode(element, to, element.getLocalName());
        }
    }

    private static Map<String, SubsetSchema.Status> statusByCode(Function<String, String> property) {
        Map<String, SubsetSchema.Status> statusByCode = new HashMap<>();
        for (SubsetSchema.Status status : SubsetSchema.Status.values()) {
            for (String code : property.apply("subsetStatus." + status).split("\\s+")) {
                if (statusByCode.put(code, status) != null) {
                    throw new IllegalArgumentException("Subset status code " + code + " has more than one meaning");
                }
            }
        }
        return statusByCode;
    }

    private static SubsetSchema.Annotations annotations(Function<String, String> property) {
        return new SubsetSchema.Annotations(property.apply("annotation.element"), property.apply("annotation.id"),
                property.apply("annotation.parentId"), property.apply("annotation.subset"),
                property.apply("annotation.subsetId"), property.apply("annotation.status"), statusByCode(property));
    }

    private static List<Function<ParsedMessage, Optional<String>>> echoableFields(String fieldNames) {
        return Arrays.stream(fieldNames.split("\\s+"))
                .map(name -> Optional.ofNullable(ECHOABLE_FIELDS.get(name)).orElseThrow(() -> new IllegalArgumentException(
                        "followUpAck.echoes may only list " + ECHOABLE_FIELDS.keySet() + ", not " + name)))
                .toList();
    }

    private static int positiveInt(String key, String value) {
        try {
            int number = Integer.parseInt(value);
            if (number > 0) {
                return number;
            }
        } catch (NumberFormatException e) {
            // reported below
        }
        throw new IllegalArgumentException(key + " must be a positive integer: " + value);
    }

    private Format(Path dir, Properties properties) {
        Function<String, String> property = key -> Optional.ofNullable(properties.getProperty(key))
                .map(String::strip)
                .orElseThrow(() -> new IllegalArgumentException(dir + "/format.properties lacks " + key));
        Map<String, String> namespaces = properties.stringPropertyNames().stream()
                .filter(key -> key.startsWith("namespace."))
                .collect(Collectors.toMap(key -> key.substring("namespace.".length()), property));
        processor = new Processor(false);
        XPathCompiler compiler = processor.newXPathCompiler();
        namespaces.forEach(compiler::declareNamespace);

        version = property.apply("format.version");
        templates = dir.resolve("templates");
        testData = dir.resolve("testdata");
        roadModeCode = property.apply("modeOfTransport.road");
        kindByRoot = new HashMap<>();
        Arrays.stream(MessageKind.values())
                .filter(kind -> properties.containsKey("kind." + kind))
                .forEach(kind -> kindByRoot.put(qName(namespaces, property.apply("kind." + kind)), kind));
        fields = FIELDS.stream()
                .filter(field -> properties.containsKey("field." + field))
                .collect(Collectors.toMap(field -> field, field -> xpath(compiler, property.apply("field." + field))));
        identifierPaths = new EnumMap<>(IdentifierType.class);
        Arrays.stream(IdentifierType.values())
                .forEach(type -> identifierPaths.put(type, xpath(compiler, property.apply("identifier." + type))));
        successStatus = Set.of(property.apply("status.success").split("\\s+"));
        invalidRequestStatus = property.apply("status.invalidRequest");
        notFoundStatus = property.apply("status.notFound");
        followUpAckEchoes = echoableFields(property.apply("followUpAck.echoes"));
        maxFollowUpTextLength = positiveInt("followUp.maxTextLength", property.apply("followUp.maxTextLength"));
        schema = loadSchema(dir.resolve(property.apply("validation.xsd")));
        Set<String> testCaseIds = TestCatalogue.load().sections().stream()
                .flatMap(section -> section.testCases().stream())
                .map(TestCatalogue.TestCase::id)
                .collect(Collectors.toUnmodifiableSet());
        schematron = Schematron.compile(processor, dir.resolve(property.apply("validation.schematron")), successStatus,
                testCaseIds);
        SubsetSchema.Annotations annotations = annotations(property);
        datasetSchema = SubsetSchema.load(dir.resolve(property.apply("dataset.xsd")), property.apply("dataset.element"), annotations);
        identifierSchema = SubsetSchema.load(dir.resolve(property.apply("identifiers.xsd")), property.apply("identifiers.element"), annotations);
        roundTripIgnored = Arrays.stream(properties.getProperty("roundtrip.ignore", "").split(","))
                .map(String::strip)
                .filter(path -> !path.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        limitations = properties.stringPropertyNames().stream()
                .filter(key -> key.startsWith(LIMITATION_PREFIX))
                .collect(Collectors.toUnmodifiableMap(key -> key.substring(LIMITATION_PREFIX.length()), property));
        List<String> unknown = limitations.keySet().stream().filter(id -> !testCaseIds.contains(id)).sorted().toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(dir + "/format.properties states limitations of unknown test cases: " + unknown);
        }
    }

    /**
     * @param formatDir folder containing {@code format.properties}
     * @throws IllegalArgumentException if the descriptor is incomplete or invalid, or one of the files it references
     *                                  (XSD, Schematron) is invalid
     * @throws UncheckedIOException     if {@code format.properties} cannot be read
     */
    public static Format load(Path formatDir) {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(formatDir.resolve("format.properties"))) {
            properties.load(reader);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read format descriptor in " + formatDir, e);
        }
        return new Format(formatDir, properties);
    }

    private XdmNode build(String xml) {
        try {
            return processor.newDocumentBuilder().build(Xml.saxSource(xml));
        } catch (SaxonApiException e) {
            throw new IllegalArgumentException("Not well-formed XML: " + e.getMessage(), e);
        }
    }

    private String serialize(XdmNode node) throws SaxonApiException {
        Serializer serializer = processor.newSerializer();
        serializer.setOutputProperty(Serializer.Property.OMIT_XML_DECLARATION, "yes");
        return serializer.serializeNodeToString(node);
    }

    /** String values of the selected items, or their serialized XML if asXml. */
    private List<String> values(XPathExecutable path, XdmItem context, boolean asXml) {
        try {
            XPathSelector selector = path.load();
            selector.setContextItem(context);
            List<String> values = new ArrayList<>();
            for (XdmItem item : selector.evaluate()) {
                values.add(asXml && item instanceof XdmNode node
                        ? serialize(node)
                        : item.getStringValue().strip());
            }
            return values;
        } catch (SaxonApiException e) {
            throw new IllegalArgumentException("Cannot evaluate format expression: " + e.getMessage(), e);
        }
    }

    private List<String> all(String field, XdmNode document) {
        XPathExecutable path = fields.get(field);
        return path == null ? List.of() : values(path, document, XML_FIELDS.contains(field));
    }

    private Optional<String> first(String field, XdmNode document) {
        return all(field, document).stream().filter(value -> !value.isBlank()).findFirst();
    }

    private MessageKind kindOf(XdmNode document) {
        return document.select(Steps.child().where(Predicates.isElement())).findFirst()
                .map(root -> kindByRoot.getOrDefault(root.getNodeName(), MessageKind.UNKNOWN))
                .orElse(MessageKind.UNKNOWN);
    }

    private Element datasetRoot(String datasetXml) {
        Element root = Xml.parse(datasetXml).getDocumentElement();
        if (!datasetSchema.isRoot(root)) {
            throw new IllegalArgumentException("Not a data set: root element is {" + root.getNamespaceURI() + "}"
                    + root.getLocalName());
        }
        return root;
    }

    public String version() {
        return version;
    }

    /** What the test case cannot evaluate in this format, to be stated with its result; empty if nothing. */
    public Optional<String> limitation(String testCaseId) {
        return Optional.ofNullable(limitations.get(testCaseId));
    }

    /** Status code of an error response rejecting a request as invalid (including a rejected identifier upload). */
    public String invalidRequestStatus() {
        return invalidRequestStatus;
    }

    /** Status code of an error response to a request for an unknown data set. */
    public String notFoundStatus() {
        return notFoundStatus;
    }

    /**
     * Maximum length of the text of a follow-up the platform must accept. The format may not define one in its
     * schema; then this is the limit of the gate the format comes from.
     */
    public int maxFollowUpTextLength() {
        return maxFollowUpTextLength;
    }

    /**
     * The test data set {@code testdata/<name>.xml} of the format, as the operator creates it.
     *
     * @throws IllegalArgumentException if the format has no such test data set
     * @throws UncheckedIOException     if the file cannot be read
     */
    public String testData(String name) {
        if (!TEMPLATE_NAME.matcher(name).matches() || !Files.isRegularFile(testData.resolve(name + ".xml"))) {
            throw new IllegalArgumentException("Format " + version + " has no test data set " + name);
        }
        try {
            return Files.readString(testData.resolve(name + ".xml"));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read test data set " + name + " of format " + version, e);
        }
    }

    /** The mode of transport identifier value of road transport. */
    public String roadModeCode() {
        return roadModeCode;
    }

    /** The data set elements not compared in the round trip because the platform sets them. */
    Set<String> roundTripIgnored() {
        return roundTripIgnored;
    }

    /** Never throws: a payload that is not well-formed yields a message of kind UNKNOWN with its malformation. */
    public ParsedMessage parse(MessageRecord record) {
        try {
            XdmNode document = build(record.payload());
            Optional<String> status = first("status", document);
            return new ParsedMessage(record, kindOf(document), Optional.empty(),
                    first("requestId", document), first("referencedRequestId", document), first("gateId", document),
                    first("platformId", document), first("datasetId", document), status,
                    status.filter(successStatus::contains).isPresent(), first("description", document),
                    first("message", document), all("subsetIds", document), first("dataset", document), first("identifiers", document));
        } catch (RuntimeException e) {
            return ParsedMessage.malformed(record, Objects.requireNonNullElse(e.getMessage(), e.toString()));
        }
    }

    /**
     * Renders {@code templates/<templateName>.xml}. Placeholders: {@code {{name}}} is replaced by the XML-escaped
     * value, {@code {{{name}}}} by the raw XML fragment (without XML declaration), and
     * {@code {{#name}}..{{.}}..{{/name}}} repeats its body for each whitespace-separated value.
     *
     * @throws IllegalArgumentException if the template does not exist or a placeholder has no value
     */
    public String render(String templateName, Map<String, String> values) {
        if (!TEMPLATE_NAME.matcher(templateName).matches()) {
            throw new IllegalArgumentException("Invalid template name: " + templateName);
        }
        String template;
        try {
            template = Files.readString(templates.resolve(templateName + ".xml"));
        } catch (IOException e) {
            throw new IllegalArgumentException("Unknown template " + templateName + " in format " + version, e);
        }
        Function<String, String> value = name -> Optional.ofNullable(values.get(name)).orElseThrow(
                () -> new IllegalArgumentException("Template " + templateName + " needs a value for " + name));
        return PLACEHOLDER.matcher(template).replaceAll(match -> Matcher.quoteReplacement(substitute(match, value)));
    }

    /** XSD validation errors (with line and column), empty if the message is valid. */
    public List<String> validateSchema(String xml) {
        List<String> errors = new ArrayList<>();
        Validator validator = schema.newValidator();
        validator.setErrorHandler(new ErrorHandler() {
            @Override
            public void warning(SAXParseException e) {
            }

            @Override
            public void error(SAXParseException e) {
                errors.add(e.getLineNumber() + ":" + e.getColumnNumber() + ": " + e.getMessage());
            }

            @Override
            public void fatalError(SAXParseException e) {
                error(e);
            }
        });
        try {
            validator.validate(Xml.saxSource(xml));
        } catch (SAXException e) {
            if (errors.isEmpty()) {
                errors.add(e.getMessage());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return errors;
    }

    /**
     * Failed assertions of the format's Schematron rules.
     *
     * @throws IllegalArgumentException if the XML is not well-formed
     */
    public List<RuleViolation> validateRules(String xml) {
        return schematron.validate(build(xml));
    }

    /** The data subsets of this format, as defined by the subset annotations of the data set XSD. */
    public Set<String> subsets() {
        return datasetSchema.subsets();
    }

    /**
     * The data set restricted to the elements of the given subsets, as defined by the subset annotations of the
     * data set XSD.
     *
     * @throws IllegalArgumentException if the XML is not a data set of this format
     */
    public String filterBySubsets(String datasetXml, Collection<String> subsetIds) {
        Element root = datasetRoot(datasetXml);
        datasetSchema.filter(root, Set.copyOf(subsetIds));
        return Xml.serialize(root);
    }

    /**
     * The identifier consignment of a data set, as uploaded with an identifier upload: the data set reduced to
     * the elements of the identifier XSD, in its namespace.
     *
     * @throws IllegalArgumentException if the XML is not a data set of this format
     */
    public String deriveIdentifiers(String datasetXml) {
        Element root = datasetRoot(datasetXml);
        identifierSchema.dropUndeclared(root);
        moveToNamespace(root, root.getNamespaceURI(), identifierSchema.namespace());
        root.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns", identifierSchema.namespace());
        return Xml.serialize(root);
    }

    /**
     * The identifier values of a consignment, which is either the identifier consignment of an upload or a data set.
     *
     * @throws IllegalArgumentException if the XML is not well-formed or a value cannot be read
     */
    public IdentifierSet identifiers(String consignmentXml) {
        XdmNode root = build(consignmentXml).select(Steps.child().where(Predicates.isElement())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No consignment element"));
        Map<IdentifierType, List<String>> values = new EnumMap<>(IdentifierType.class);
        identifierPaths.forEach((type, path) -> values.put(type, values(path, root, false).stream().sorted().toList()));
        return new IdentifierSet(values);
    }

    /**
     * The identifier values of an upload that a response restricted to the subsets must contain: those of elements
     * that belong to the subsets and are not optional in all of them. The identifier consignment is a part of the data
     * set structure, so the annotations of the data set XSD apply to it.
     *
     * @throws IllegalArgumentException if the XML is not well-formed or a value cannot be read
     */
    IdentifierSet identifiersNotOmissible(String identifierConsignmentXml, Set<String> subsetIds) {
        Element root = Xml.parse(identifierConsignmentXml).getDocumentElement();
        datasetSchema.retainNotOmissible(root, subsetIds);
        return identifiers(Xml.serialize(root));
    }

    private List<Function<ParsedMessage, Optional<String>>> echoableFields(MessageKind requestKind) {
        return requestKind == MessageKind.FOLLOW_UP ? followUpAckEchoes : List.of(ParsedMessage::requestId);
    }

    /** The value a response should echo as request ID when it answers this request (the preferred accepted echo). */
    public Optional<String> correlationKey(ParsedMessage request) {
        return echoableFields(request.kind()).getFirst().apply(request);
    }

    /**
     * The values a response may echo as request ID when it answers this request, empty if the request carries
     * none of them. A follow-up acknowledgement may echo the fields the format lists, all other responses echo the
     * request ID of the request.
     */
    public List<String> acceptedEchoes(ParsedMessage request) {
        return echoableFields(request.kind()).stream()
                .map(field -> field.apply(request))
                .flatMap(Optional::stream)
                .distinct()
                .toList();
    }

    /**
     * Paths (local names, starting with the root element) of the elements of the data set that belong to none of the subsets, as defined
     * by the subset annotations of the data set XSD; empty if the data set is restricted to the subsets.
     *
     * @throws IllegalArgumentException if the XML is not a data set of this format
     */
    public List<String> elementsOutsideSubsets(String datasetXml, Collection<String> subsetIds) {
        return datasetSchema.elementsOutside(datasetRoot(datasetXml), Set.copyOf(subsetIds));
    }

    /** See {@link SubsetSchema#compositionViolations}. */
    List<String> compositionViolations(String datasetXml, Set<String> subsetIds) {
        return datasetSchema.compositionViolations(datasetRoot(datasetXml), subsetIds);
    }

    /** Compares the actual data set with the expected one restricted to the subsets. */
    RoundTrip.Comparison roundTrip(String expectedDatasetXml, String actualDatasetXml, Set<String> subsetIds) {
        Element expected = datasetRoot(expectedDatasetXml);
        datasetSchema.filter(expected, subsetIds);
        return RoundTrip.compare(expected, datasetRoot(actualDatasetXml), datasetSchema, subsetIds, roundTripIgnored);
    }
}
