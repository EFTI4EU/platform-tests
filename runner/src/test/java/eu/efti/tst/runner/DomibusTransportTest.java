package eu.efti.tst.runner;

import eu.efti.tst.core.MessageKind;
import eu.efti.tst.core.MessageRecord;
import eu.efti.tst.domibus.DomibusStub;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static eu.efti.tst.domibus.DomibusStub.base64;
import static eu.efti.tst.runner.Fixtures.CONTEXT;
import static eu.efti.tst.runner.Fixtures.DATASET_ID;
import static eu.efti.tst.runner.Fixtures.DS1;
import static eu.efti.tst.runner.Fixtures.FORMAT;
import static eu.efti.tst.runner.Fixtures.GATE_PARTY;
import static eu.efti.tst.runner.Fixtures.PLATFORM_PARTY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The transport and the engine against a local stand-in for the Domibus WS plugin. */
class DomibusTransportTest {

    private final DomibusStub domibus = DomibusStub.start();
    private final List<String> log = new ArrayList<>();
    private final DomibusTransport transport = new DomibusTransport(domibus.client("tstgate_ws", GATE_PARTY), GATE_PARTY,
            PLATFORM_PARTY, log::add);
    private final GateEngine engine = new GateEngine(FORMAT, transport, CONTEXT, Duration.ofMillis(1), Duration.ofSeconds(1),
            log::add);

    @AfterEach
    void stop() {
        domibus.close();
    }

    private void receive(String messageId, String fromParty, String payload) {
        domibus.addPending(messageId, DomibusStub.retrieveResponse(messageId, fromParty, GATE_PARTY, UUID.randomUUID().toString(),
                base64(payload)));
    }

    @Test
    void messagesAreMarkedOnlyAfterTheyWereProcessed() {
        receive("upload-1", PLATFORM_PARTY, Fixtures.identifierUpload(DATASET_ID, DS1));

        assertThat(transport.fetchNew()).extracting(MessageRecord::as4MessageId).containsExactly("upload-1");
        assertThat(domibus.pending()).containsExactly("upload-1");

        engine.poll();

        assertThat(domibus.pending()).isEmpty();
        assertThat(domibus.marked()).containsExactly("upload-1");
        // the upload was answered with an upload status referring to it, which the platform's access point received
        assertThat(domibus.submitted()).singleElement().satisfies(status -> {
            assertThat(status.refToMessageId()).isEqualTo("upload-1");
            assertThat(FORMAT.parse(Fixtures.toPlatform(status.payload(), Fixtures.T0)).kind()).isEqualTo(MessageKind.UPLOAD_STATUS);
        });
        assertThat(engine.receiptStatuses(Duration.ZERO)).containsOnly(Map.entry(domibus.submitted().getFirst().as4MessageId(),
                Results.ACKNOWLEDGED));
    }

    @Test
    void requestsReferToNoMessage() {
        transport.submit("conv-1", null, Fixtures.dataRequest("data-request", "req-1", DATASET_ID, "EU01"));

        assertThat(domibus.submitted()).singleElement().satisfies(request -> {
            assertThat(request.conversationId()).isEqualTo("conv-1");
            assertThat(request.refToMessageId()).isNull();
        });
    }

    @Test
    void messagesOfOtherPartiesAreDropped() {
        receive("foreign-1", "otherplatform", Fixtures.identifierUpload(DATASET_ID, DS1));

        assertThat(transport.fetchNew()).isEmpty();
        assertThat(domibus.marked()).containsExactly("foreign-1");
        assertThat(log).singleElement().asString().contains("not the platform under test");
    }

    @Test
    void messageOfAnotherPartyThatCannotBeMarkedDoesNotHoldBackThePlatformsMessages() {
        receive("foreign-1", "otherplatform", Fixtures.identifierUpload(DATASET_ID, DS1));
        receive("upload-1", PLATFORM_PARTY, Fixtures.identifierUpload(DATASET_ID, DS1));
        domibus.override(request -> request.operation().equals("markMessageAsDownloadedRequest")
                ? new DomibusStub.Reply(500, DomibusStub.fault("Error marking message", "WSPLUGIN:0004", "Database locked")) : null);

        assertThat(transport.fetchNew()).extracting(MessageRecord::as4MessageId).containsExactly("upload-1");
        assertThat(transport.fetchNew()).extracting(MessageRecord::as4MessageId).containsExactly("upload-1");

        assertThat(domibus.pending()).containsExactly("foreign-1", "upload-1");
        assertThat(log).singleElement().asString().contains("not the platform under test");
    }

    @Test
    void submitWithoutResponseIsNotRetried() {
        AtomicInteger submits = new AtomicInteger();
        domibus.override(request -> {
            if (!request.operation().equals("submitRequest")) {
                return null;
            }
            submits.incrementAndGet();
            return DomibusStub.NO_RESPONSE;
        });

        assertThatThrownBy(() -> engine.request("T-RUN-01", "upload-status-success", Map.of("requestId", "r")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("may have been sent anyway");
        assertThat(submits).hasValue(1);
    }
}
