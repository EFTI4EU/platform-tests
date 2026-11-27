package eu.efti.tst.platform;

import eu.efti.tst.core.Format;
import eu.efti.tst.core.IdentifierType;
import eu.efti.tst.core.MessageKind;
import eu.efti.tst.core.MessageRecord;
import eu.efti.tst.core.ParsedMessage;
import net.sf.saxon.s9api.Processor;
import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.XdmAtomicValue;
import net.sf.saxon.s9api.XsltExecutable;
import net.sf.saxon.s9api.Xslt30Transformer;

import javax.xml.transform.stream.StreamSource;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * What the pseudo platform does with data sets and gate messages, as functions of a message format: all
 * format-specific knowledge comes from the format folder ({@code format.properties}, templates, XSDs and the
 * pseudo platform's own XSLT {@code platform/consignee-signature.xsl}). Immutable and thread-safe.
 */
public final class PlatformBehaviour {

    private static final Pattern LEADING_LINE_NUMBER = Pattern.compile("^(\\d+):");
    private static final String DATASET_MARKER = "<!--dataset-->";

    private final Format format;
    private final Processor processor;
    private final XsltExecutable consigneeSignature;

    private PlatformBehaviour(Path formatDir) {
        format = Format.load(formatDir);
        processor = new Processor(false);
        Path xslt = formatDir.resolve("platform/consignee-signature.xsl");
        try {
            consigneeSignature = processor.newXsltCompiler().compile(new StreamSource(xslt.toFile()));
        } catch (SaxonApiException e) {
            throw new IllegalArgumentException("Cannot compile " + xslt + ": " + e.getMessage(), e);
        }
    }

    private static long newlines(String text) {
        return text.chars().filter(c -> c == '\n').count();
    }

    /**
     * @param formatDir format folder with {@code format.properties} and {@code platform/consignee-signature.xsl}
     * @throws IllegalArgumentException if the format is incomplete or invalid
     */
    public static PlatformBehaviour load(Path formatDir) {
        return new PlatformBehaviour(formatDir);
    }

    public String formatVersion() {
        return format.version();
    }

    public ParsedMessage parse(MessageRecord record) {
        return format.parse(record);
    }

    /**
     * Problems that prevent the platform from managing the data set, empty if there are none. Schema errors are
     * prefixed with line:column of the data set.
     */
    public List<String> validateDataset(String datasetXml) {
        // The format validates messages only, so the data set is validated as the content of a data response.
        String requestId = UUID.randomUUID().toString();
        String marked = format.render("data-response-success", Map.of("requestId", requestId, "dataset", DATASET_MARKER));
        String rendered = format.render("data-response-success", Map.of("requestId", requestId, "dataset", datasetXml));
        // Rendering drops the data set's XML declaration with any whitespace before it, so these lines are added back.
        long droppedLines = newlines(datasetXml) - (newlines(rendered) - newlines(marked));
        long lineOffset = newlines(marked.substring(0, marked.indexOf(DATASET_MARKER))) - droppedLines;
        List<String> errors = format.validateSchema(rendered)
                .stream()
                .map(error -> LEADING_LINE_NUMBER.matcher(error)
                        .replaceFirst(line -> (Long.parseLong(line.group(1)) - lineOffset) + ":"))
                .toList();
        if (!errors.isEmpty()) {
            return errors;
        }
        try {
            format.deriveIdentifiers(datasetXml);
            return List.of();
        } catch (IllegalArgumentException e) {
            return List.of(e.getMessage());
        }
    }

    /** The identifier upload (create or full replacement of the registry record) for the data set. */
    public String identifierUpload(String requestId, DataSet dataSet) {
        return format.render("identifier-upload", Map.of("requestId", requestId, "datasetId", dataSet.id(),
                "identifiers", format.deriveIdentifiers(dataSet.xml())));
    }

    public boolean isRoadTransport(String datasetXml) {
        return format.identifiers(datasetXml).get(IdentifierType.MODE_OF_TRANSPORT).contains(format.roadModeCode());
    }

    /** The data set as changed by the consignee signature at the given time (e.g. the actual delivery date). */
    public String signAsConsignee(String datasetXml, OffsetDateTime signatureTime) {
        try {
            Xslt30Transformer transformer = consigneeSignature.load30();
            transformer.setStylesheetParameters(Map.of(new QName("signatureTime"), new XdmAtomicValue(signatureTime)));
            StringWriter output = new StringWriter();
            transformer.transform(new StreamSource(new StringReader(datasetXml)), processor.newSerializer(output));
            return output.toString();
        } catch (SaxonApiException e) {
            throw new IllegalArgumentException("Consignee signature failed: " + e.getMessage(), e);
        }
    }

    /** Whether the messages include a data request with the request ID for the data set. */
    private static boolean isLodged(String requestId, Optional<String> datasetId, List<ParsedMessage> messages) {
        return messages.stream().anyMatch(message -> message.kind() == MessageKind.DATA_REQUEST
                && message.requestId().equals(Optional.of(requestId)) && message.datasetId().equals(datasetId));
    }

    /**
     * The payload answering a message from the gate, empty if the platform does not answer it: responses and
     * unknown messages are not answered, nor are requests lacking the data set, gate or platform ID. A request without
     * request ID is answered with an error that echoes none, which the gate pairs by its AS4 RefToMessageId.
     *
     * @param dataSets data sets by ID
     * @param messages the messages exchanged so far, in which a follow-up must find the data request it refers to
     */
    public Optional<String> answer(ParsedMessage message, Map<String, DataSet> dataSets, List<ParsedMessage> messages,
                                   FaultToggles toggles) {
        return switch (message.kind()) {
            case DATA_REQUEST -> toggles.ignoreDataRequests()
                    ? Optional.empty()
                    : answer(message, "data-response", dataSets, messages,
                            (requestId, dataSet) -> Map.of("requestId", requestId, "dataset",
                                    toggles.ignoreSubsetFilter()
                                            ? dataSet.xml()
                                            : format.filterBySubsets(dataSet.xml(), message.subsetIds())));
            case FOLLOW_UP -> answer(message, "follow-up-ack", dataSets, messages,
                    (requestId, dataSet) -> Map.of("requestId", requestId));
            default -> Optional.empty();
        };
    }

    /**
     * @param templatePrefix  templates {@code <templatePrefix>-success} and {@code <templatePrefix>-error}
     * @param successValues   placeholder values of the success template, given the echoed request ID and data set
     */
    private Optional<String> answer(ParsedMessage message, String templatePrefix, Map<String, DataSet> dataSets,
                                    List<ParsedMessage> messages,
                                    BiFunction<String, DataSet, Map<String, String>> successValues) {
        if (Stream.of(message.datasetId(), message.gateId(), message.platformId()).anyMatch(Optional::isEmpty)) {
            return Optional.empty();
        }
        Optional<String> echoedRequestId = format.correlationKey(message);
        if (echoedRequestId.isEmpty()) {
            return Optional.of(error(templatePrefix, "", format.invalidRequestStatus(), "Missing request ID"));
        }
        String requestId = echoedRequestId.get();
        String datasetId = message.datasetId().get();
        List<String> schemaErrors = format.validateSchema(message.record().payload());
        if (!schemaErrors.isEmpty()) {
            return Optional.of(error(templatePrefix, requestId, format.invalidRequestStatus(),
                    "Invalid message: " + schemaErrors.getFirst()));
        }
        DataSet dataSet = dataSets.get(datasetId);
        if (dataSet == null || !dataSet.availableOnline()) {
            // Data sets not available online are not visible to authorities, so they are reported like unknown data sets
            return Optional.of(error(templatePrefix, requestId, format.notFoundStatus(), "Unknown data set " + datasetId));
        }
        if (!format.subsets().containsAll(message.subsetIds())) {
            return Optional.of(error(templatePrefix, requestId, format.invalidRequestStatus(),
                    "Unknown data subset among " + message.subsetIds()));
        }
        Optional<String> unknownRequest = message.referencedRequestId().filter(id -> !isLodged(id, message.datasetId(), messages));
        if (unknownRequest.isPresent()) {
            return Optional.of(error(templatePrefix, requestId, format.notFoundStatus(),
                    "No data request " + unknownRequest.get() + " for data set " + datasetId));
        }
        return Optional.of(format.render(templatePrefix + "-success", successValues.apply(requestId, dataSet)));
    }

    private String error(String templatePrefix, String requestId, String status, String description) {
        return format.render(templatePrefix + "-error",
                Map.of("requestId", requestId, "status", status, "description", description));
    }
}
