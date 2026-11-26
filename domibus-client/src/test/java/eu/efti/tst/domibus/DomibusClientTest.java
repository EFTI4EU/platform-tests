package eu.efti.tst.domibus;

import eu.efti.tst.core.MessageRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static eu.efti.tst.domibus.DomibusStub.base64;
import static eu.efti.tst.domibus.DomibusStub.fault;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runs the client against a local HTTP server that answers like the Domibus WS plugin. */
class DomibusClientTest {

    private static final String PAYLOAD = "<identifierQuery xmlns=\"http://efti.eu/v1/edelivery\">ä &amp; ü</identifierQuery>";
    private static final DomibusStub.Reply FAULT = new DomibusStub.Reply(500,
            fault("Error retrieving message", "WSPLUGIN:0004", "Message not found"));

    private final DomibusStub domibus = DomibusStub.start();
    private final DomibusClient client = domibus.client("tstgate_ws", "tstgate");

    @AfterEach
    void stopServer() {
        domibus.close();
    }

    private void receive(String... messageIds) {
        List.of(messageIds).forEach(id -> domibus.addPending(id,
                DomibusStub.retrieveResponse(id, "pseudoplatform", "tstgate", "conv-" + id, base64(PAYLOAD))));
    }

    private void failFor(String operation, String messageId, DomibusStub.Reply reply) {
        domibus.override(request -> request.operation().equals(operation) && request.messageId().orElse("").equals(messageId)
                ? reply : null);
    }

    private static DomibusException failure(Runnable call) {
        try {
            call.run();
        } catch (DomibusException e) {
            return e;
        }
        throw new AssertionError("no DomibusException thrown");
    }

    @Test
    void submitReturnsMessageIdAndAuthenticatesWithBasicAuth() {
        String messageId = client.submit("pseudoplatform", "conv-1", null, PAYLOAD);

        assertThat(domibus.submitted()).containsExactly(
                new DomibusStub.Submitted(messageId, "pseudoplatform", "conv-1", null, PAYLOAD));
        assertThat(domibus.authorizationHeaders()).containsExactly("Basic dHN0Z2F0ZV93czp0ZXN0MTIz");
    }

    @Test
    void submittedResponseRefersToTheAnsweredMessage() {
        String messageId = client.submit("pseudoplatform", "conv-1", "req-1@domibus", PAYLOAD);

        assertThat(domibus.submitted()).containsExactly(
                new DomibusStub.Submitted(messageId, "pseudoplatform", "conv-1", "req-1@domibus", PAYLOAD));
    }

    @Test
    void fetchedMessagesCarryTheirRefToMessageIdIfAny() {
        domibus.addPending("id-1", DomibusStub.retrieveResponse("id-1", "pseudoplatform", "tstgate", "conv-1", base64(PAYLOAD)));
        domibus.addPending("id-2",
                DomibusStub.retrieveResponse("id-2", "pseudoplatform", "tstgate", "conv-2", "req-2@stub", base64(PAYLOAD)));

        assertThat(client.fetchNew(MessageRecord.Direction.FROM_PLATFORM).messages())
                .extracting(MessageRecord::refToMessageId).containsExactly(null, "req-2@stub");
    }

    @Test
    void fetchNewReturnsPendingMessagesWithTheTimeOfRetrievalAndMarksNothing() {
        receive("id-1", "id-2");
        Instant before = Instant.now();

        DomibusClient.Fetched fetched = client.fetchNew(MessageRecord.Direction.FROM_PLATFORM);

        assertThat(fetched.messages()).extracting(MessageRecord::as4MessageId).containsExactly("id-1", "id-2");
        assertThat(fetched.messages()).allSatisfy(message -> {
            assertThat(message.direction()).isEqualTo(MessageRecord.Direction.FROM_PLATFORM);
            assertThat(message.payload()).isEqualTo(PAYLOAD);
            assertThat(message.timestamp()).isBetween(before, Instant.now());
        });
        assertThat(fetched.unreadable()).isEmpty();
        assertThat(domibus.marked()).isEmpty();
        assertThat(client.fetchNew(MessageRecord.Direction.FROM_PLATFORM).messages()).hasSize(2);
    }

    @Test
    void markedMessageIsNotFetchedAgain() {
        receive("id-1", "id-2");

        client.markDownloaded("id-1");

        assertThat(domibus.marked()).containsExactly("id-1");
        assertThat(client.fetchNew(MessageRecord.Direction.FROM_PLATFORM).messages())
                .extracting(MessageRecord::as4MessageId).containsExactly("id-2");
    }

    @Test
    void failedMarkingLeavesOnlyThatMessagePending() {
        receive("id-1", "id-2", "id-3");
        failFor("markMessageAsDownloadedRequest", "id-2", FAULT);
        List<String> ids = client.fetchNew(MessageRecord.Direction.FROM_PLATFORM).messages().stream()
                .map(MessageRecord::as4MessageId).toList();

        client.markDownloaded(ids.get(0));
        assertThatThrownBy(() -> client.markDownloaded(ids.get(1))).isInstanceOf(DomibusException.class);
        client.markDownloaded(ids.get(2));

        assertThat(domibus.pending()).containsExactly("id-2");
    }

    @Test
    void messageThatCannotBeRetrievedIsReportedAndDoesNotBlockTheOthers() {
        receive("id-1", "id-2");
        failFor("retrieveMessageRequest", "id-1", FAULT);

        for (int poll = 0; poll < 2; poll++) {
            DomibusClient.Fetched fetched = client.fetchNew(MessageRecord.Direction.FROM_PLATFORM);

            assertThat(fetched.messages()).extracting(MessageRecord::as4MessageId).containsExactly("id-2");
            assertThat(fetched.unreadable()).containsOnlyKeys("id-1");
            assertThat(fetched.unreadable().get("id-1")).hasMessageContaining("Message not found");
        }
        assertThat(domibus.pending()).containsExactly("id-1", "id-2");
    }

    @Test
    void messageWithoutPayloadIsReturnedWithAnEmptyPayload() {
        domibus.addPending("id-1", DomibusStub.retrieveResponse("id-1", "pseudoplatform", "tstgate", "conv-1", null));

        assertThat(client.fetchNew(MessageRecord.Direction.FROM_PLATFORM).messages())
                .singleElement().satisfies(message -> assertThat(message.payload()).isEmpty());
    }

    @Test
    void httpErrorWhileRetrievingFailsTheWholeFetch() {
        receive("id-1", "id-2");
        failFor("retrieveMessageRequest", "id-2", new DomibusStub.Reply(503, "<html>Service unavailable</html>"));

        assertThatThrownBy(() -> client.fetchNew(MessageRecord.Direction.FROM_PLATFORM))
                .isInstanceOf(DomibusException.class)
                .hasMessageContaining("HTTP 503");
    }

    @Test
    void fetchNewWithoutPendingMessagesReturnsNothing() {
        DomibusClient.Fetched fetched = client.fetchNew(MessageRecord.Direction.TO_PLATFORM);

        assertThat(fetched.messages()).isEmpty();
        assertThat(fetched.unreadable()).isEmpty();
    }

    @Test
    void getStatusReturnsTheStatusOfASentMessage() {
        String messageId = client.submit("pseudoplatform", "conv-1", null, PAYLOAD);

        assertThat(client.getStatus(messageId)).isEqualTo("ACKNOWLEDGED");
        assertThat(client.getStatus("unknown")).isEqualTo("NOT_FOUND");
    }

    @Test
    void failedSubmitThatCannotHaveReachedDomibusMayBeRetried() {
        domibus.override(request -> new DomibusStub.Reply(401, "<html>Unauthorized</html>"));
        DomibusException unauthorized = failure(() -> client.submit("pseudoplatform", "conv-1", null, PAYLOAD));
        domibus.override(request -> new DomibusStub.Reply(500, fault("Message submission failed", "EBMS:0003", "No matching leg")));
        DomibusException rejected = failure(() -> client.submit("pseudoplatform", "conv-1", null, PAYLOAD));
        domibus.close();
        DomibusException unreachable = failure(() -> client.submit("pseudoplatform", "conv-1", null, PAYLOAD));

        assertThat(unauthorized).hasMessageContaining("HTTP 401");
        assertThat(rejected).hasMessageContaining("No matching leg");
        assertThat(unreachable).hasMessageContaining("Connecting to Domibus WS plugin").hasMessageContaining("failed");
        assertThat(List.of(unauthorized, rejected, unreachable)).noneMatch(DomibusException::mayHaveBeenDelivered);
    }

    @Test
    void failedSubmitThatMayHaveReachedDomibusIsFlagged() {
        domibus.override(request -> DomibusStub.NO_RESPONSE);
        DomibusException brokenConnection = failure(() -> client.submit("pseudoplatform", "conv-1", null, PAYLOAD));
        domibus.override(request -> new DomibusStub.Reply(502, "<html>Bad gateway</html>"));
        DomibusException serverError = failure(() -> client.submit("pseudoplatform", "conv-1", null, PAYLOAD));
        domibus.override(request -> new DomibusStub.Reply(200, "<html>not SOAP</html>"));
        DomibusException unexpectedResponse = failure(() -> client.submit("pseudoplatform", "conv-1", null, PAYLOAD));

        assertThat(List.of(brokenConnection, serverError, unexpectedResponse)).allMatch(DomibusException::mayHaveBeenDelivered);
    }
}
