package eu.efti.tst.platform;

import eu.efti.tst.core.Format;
import eu.efti.tst.core.MessageRecord;
import eu.efti.tst.core.ParsedMessage;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformViewTest {

    private static final Format FORMAT = Format.load(Path.of("..", "formats", "ri-v0.5"));

    private static ParsedMessage message(String template, Map<String, String> values) {
        return FORMAT.parse(new MessageRecord("as4-" + values.get("requestId"), Instant.now(),
                MessageRecord.Direction.TO_PLATFORM, "g", "p", "c", null, FORMAT.render(template, values)));
    }

    private static ParsedMessage response(String as4MessageId, String refToMessageId, String payload) {
        return FORMAT.parse(new MessageRecord(as4MessageId, Instant.now(), MessageRecord.Direction.FROM_PLATFORM, "p", "g",
                "c", refToMessageId, payload));
    }

    private static ParsedMessage dataRequest(String requestId, String datasetId) {
        return message("data-request", Map.of("requestId", requestId, "gateId", "g", "platformId", "p",
                "datasetId", datasetId, "subsetIds", "EU01"));
    }

    @Test
    void messagesOfADataSetIncludeTheResponsesToItsRequests() {
        ParsedMessage request = dataRequest("r1", "ds1");
        ParsedMessage response = message("data-response-error", Map.of("requestId", "r1", "status", "404",
                "description", "x"));
        ParsedMessage otherRequest = dataRequest("r2", "ds2");
        ParsedMessage otherStatus = message("upload-status-success", Map.of("requestId", "r3"));
        PlatformView view = new PlatformView(List.of(), List.of(otherStatus, response, otherRequest, request),
                FaultToggles.NONE, Optional.empty(), List.of());

        assertThat(view.messagesOf("ds1")).containsExactly(response, request);
        assertThat(view.messagesOf("unknown")).isEmpty();
    }

    @Test
    void responsesWithoutRequestIdBelongToTheDataSetOfTheMessageTheyReferTo() {
        ParsedMessage request = dataRequest("r1", "ds1");
        ParsedMessage response = response("as4-response", request.record().as4MessageId(),
                FORMAT.render("data-response-error", Map.of("requestId", "", "status", "400", "description", "x")));
        ParsedMessage unrelated = response("as4-unrelated", "as4-unknown", "<not xml");
        PlatformView view = new PlatformView(List.of(), List.of(unrelated, response, request), FaultToggles.NONE,
                Optional.empty(), List.of());

        assertThat(view.messagesOf("ds1")).containsExactly(response, request);
    }

    @Test
    void otherMessagesAreThoseOfNoDataSet() {
        ParsedMessage request = dataRequest("r1", "ds1");
        ParsedMessage unknownDataSet = dataRequest("r2", "unknown");
        ParsedMessage malformed = response("as4-malformed", null, "<not xml");
        PlatformView view = new PlatformView(List.of(new DataSet("ds1", "<x/>", DataSet.Status.ACTIVE)),
                List.of(malformed, unknownDataSet, request), FaultToggles.NONE, Optional.empty(), List.of());

        assertThat(view.otherMessages()).containsExactly(malformed, unknownDataSet);
        assertThat(new PlatformView(List.of(), view.messages(), FaultToggles.NONE, Optional.empty(), List.of()).otherMessages())
                .isEqualTo(view.messages());
    }
}
