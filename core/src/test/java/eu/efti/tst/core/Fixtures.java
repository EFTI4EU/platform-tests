package eu.efti.tst.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

/**
 * Shared test data: the synthetic format {@code mini} (see its XSDs for what it covers), its test data set and
 * message builders. Tests of the check logic use it instead of a real format, so that they do not depend on the
 * element names of a format version.
 */
final class Fixtures {

    static final Path FORMAT_DIR = Path.of("src", "test", "resources", "formats", "mini");
    static final Format FORMAT = Format.load(FORMAT_DIR);
    /** Holds every mandatory element of the subsets S1 and S2, plus elements of S3 only. */
    static final String DATASET = read(FORMAT_DIR.resolve(Path.of("testdata", "shipment.xml")));
    static final String GATE = "DE-GATE-TEST";
    static final String PLATFORM = "DE-PF-TEST-0042";
    static final String DATASET_ID = "0b6f3f3e-3c1a-4f6e-9d4b-2a7c5e8f1a90";
    static final ConformanceContext CONTEXT = new ConformanceContext(GATE, PLATFORM, Duration.ofSeconds(60),
            ConformanceContext.Mode.TEST_RUN);
    static final ConformanceContext MONITORING = new ConformanceContext(GATE, PLATFORM, Duration.ofSeconds(60),
            ConformanceContext.Mode.MONITORING);
    static final Instant T0 = Instant.parse("2026-09-15T07:00:00Z");

    private Fixtures() {
    }

    static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Copies the format folder into the target folder and edits one of its files.
     *
     * @return the copied format folder
     */
    static Path formatCopy(Path target, String file, UnaryOperator<String> edit) {
        Path copy = target.resolve("format");
        try (Stream<Path> paths = Files.walk(FORMAT_DIR)) {
            for (Path path : paths.toList()) {
                Files.copy(path, copy.resolve(FORMAT_DIR.relativize(path).toString()));
            }
            Files.writeString(copy.resolve(file), edit.apply(Files.readString(copy.resolve(file))));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return copy;
    }

    static MessageRecord toPlatform(String payload, Instant timestamp, String conversationId) {
        return new MessageRecord(UUID.randomUUID() + "@gate", timestamp, MessageRecord.Direction.TO_PLATFORM,
                GATE, PLATFORM, conversationId, null, payload);
    }

    static MessageRecord toPlatform(String payload, Instant timestamp) {
        return toPlatform(payload, timestamp, UUID.randomUUID().toString());
    }

    /** A message from the platform without RefToMessageId (a request, or a response violating the AS4 profile). */
    static MessageRecord fromPlatform(String payload, Instant timestamp, String conversationId) {
        return new MessageRecord(UUID.randomUUID() + "@platform", timestamp, MessageRecord.Direction.FROM_PLATFORM,
                PLATFORM, GATE, conversationId, null, payload);
    }

    static MessageRecord fromPlatform(String payload, Instant timestamp) {
        return fromPlatform(payload, timestamp, UUID.randomUUID().toString());
    }

    /** A response of the other side that refers to the request by RefToMessageId, in the given conversation. */
    static MessageRecord reply(MessageRecord request, String payload, Instant timestamp, String conversationId) {
        boolean fromPlatform = request.direction() == MessageRecord.Direction.TO_PLATFORM;
        return new MessageRecord(UUID.randomUUID() + (fromPlatform ? "@platform" : "@gate"), timestamp,
                fromPlatform ? MessageRecord.Direction.FROM_PLATFORM : MessageRecord.Direction.TO_PLATFORM,
                request.toParty(), request.fromParty(), conversationId, request.as4MessageId(), payload);
    }

    /** A response of the other side that refers to the request by RefToMessageId, in the request's conversation. */
    static MessageRecord answer(MessageRecord request, String payload, Instant timestamp) {
        return reply(request, payload, timestamp, request.conversationId());
    }

    static String dataRequest(String requestId, String subsetIds) {
        return FORMAT.render("data-request", Map.of("requestId", requestId, "gateId", GATE, "platformId", PLATFORM,
                "datasetId", DATASET_ID, "subsetIds", subsetIds));
    }

    static String dataRequestWithoutRequestId(String subsetIds) {
        return FORMAT.render("data-request-missing-request-id", Map.of("gateId", GATE, "platformId", PLATFORM,
                "datasetId", DATASET_ID, "subsetIds", subsetIds));
    }

    static String followUp(String requestId, String referencedRequestId) {
        return FORMAT.render("follow-up", Map.of("requestId", requestId, "referencedRequestId", referencedRequestId,
                "gateId", GATE, "platformId", PLATFORM, "datasetId", DATASET_ID, "message", "Please check the plate"));
    }

    static String followUpAck(String requestId) {
        return FORMAT.render("follow-up-ack-success", Map.of("requestId", requestId));
    }

    static String dataResponse(String requestId, String dataset) {
        return FORMAT.render("data-response-success", Map.of("requestId", requestId, "dataset", dataset));
    }

    static String errorResponse(String template, String requestId) {
        return FORMAT.render(template, Map.of("requestId", requestId, "status", "404", "description", "Unknown data set"));
    }

    static String identifierUpload(String requestId, String datasetId, String dataset) {
        return FORMAT.render("identifier-upload", Map.of("requestId", requestId, "datasetId", datasetId,
                "identifiers", FORMAT.deriveIdentifiers(dataset)));
    }

    /** An identifier upload with a new request ID. */
    static String identifierUpload(String datasetId, String dataset) {
        return identifierUpload(UUID.randomUUID().toString(), datasetId, dataset);
    }

    /** The upload status the gate side sends for an identifier upload. */
    static MessageRecord uploadStatus(MessageRecord upload, boolean accepted) {
        Map<String, String> values = Map.of("requestId", FORMAT.parse(upload).requestId().orElseThrow(),
                "status", FORMAT.invalidRequestStatus(), "description", "Rejected");
        return answer(upload, FORMAT.render(accepted ? "upload-status-success" : "upload-status-error", values),
                upload.timestamp());
    }
}
