package eu.efti.tst.runner;

import eu.efti.tst.core.MessageKind;
import eu.efti.tst.core.MessageRecord;
import eu.efti.tst.core.ParsedMessage;
import eu.efti.tst.core.TestCaseIds;
import eu.efti.tst.domibus.DomibusException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static eu.efti.tst.runner.Fixtures.CONTEXT;
import static eu.efti.tst.runner.Fixtures.DATASET_ID;
import static eu.efti.tst.runner.Fixtures.DS1;
import static eu.efti.tst.runner.Fixtures.FORMAT;
import static eu.efti.tst.runner.Fixtures.T0;
import static eu.efti.tst.runner.Fixtures.fromPlatform;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GateEngineTest {

    /**
     * Transport whose incoming messages the test queues; they are returned until marked, like Domibus does. Each
     * failure queued is thrown by the next call other than marking, which fails {@code markFailures} times.
     */
    private static final class QueueTransport implements Transport {
        final Map<String, MessageRecord> incoming = new LinkedHashMap<>();
        final Deque<DomibusException> failures = new ArrayDeque<>();
        final List<String> submitted = new ArrayList<>();
        final List<String> marked = new ArrayList<>();
        final Deque<String> statuses = new ArrayDeque<>();
        final Set<String> statusNotQueryable = new HashSet<>();
        int markFailures;

        void add(MessageRecord record) {
            incoming.put(record.as4MessageId(), record);
        }

        private void failIfQueued() {
            if (!failures.isEmpty()) {
                throw failures.pop();
            }
        }

        @Override
        public MessageRecord submit(String conversationId, String refToMessageId, String payloadXml) {
            submitted.add(payloadXml);
            failIfQueued();
            return new MessageRecord(UUID.randomUUID() + "@gate", T0, MessageRecord.Direction.TO_PLATFORM,
                    Fixtures.GATE_PARTY, Fixtures.PLATFORM_PARTY, conversationId, refToMessageId, payloadXml);
        }

        @Override
        public List<MessageRecord> fetchNew() {
            failIfQueued();
            return List.copyOf(incoming.values());
        }

        @Override
        public void markDownloaded(String as4MessageId) {
            if (markFailures > 0) {
                markFailures--;
                throw new DomibusException("mark failed");
            }
            marked.add(as4MessageId);
            incoming.remove(as4MessageId);
        }

        @Override
        public String status(String as4MessageId) {
            failIfQueued();
            if (statusNotQueryable.contains(as4MessageId)) {
                throw NOT_SENT;
            }
            return statuses.size() > 1 ? statuses.pop() : statuses.getFirst();
        }
    }

    private static final DomibusException NOT_SENT = new DomibusException("no connection", null, false);
    private static final DomibusException MAYBE_SENT = new DomibusException("read timeout", null, true);

    private final QueueTransport transport = new QueueTransport();
    private final GateEngine engine = new GateEngine(FORMAT, transport, CONTEXT, Duration.ofMillis(1),
            Duration.ofMillis(200), message -> { });

    private List<ParsedMessage> uploadStatuses() {
        return engine.history().messages().stream().filter(message -> message.kind() == MessageKind.UPLOAD_STATUS).toList();
    }

    private ParsedMessage request() {
        return engine.request("T-RUN-04", "data-request", Map.of("requestId", UUID.randomUUID().toString(),
                "gateId", "g", "platformId", "p", "datasetId", DATASET_ID, "subsetIds", "EU01"));
    }

    @Test
    void validUploadIsAnsweredWithSuccessReferringToItAndBecomesTheBaseline() {
        MessageRecord upload = fromPlatform(Fixtures.identifierUpload(DATASET_ID, DS1), T0);
        transport.add(upload);

        engine.poll();

        assertThat(uploadStatuses()).singleElement().satisfies(status -> {
            assertThat(status.success()).isTrue();
            assertThat(status.requestId()).isEqualTo(FORMAT.parse(upload).requestId());
            assertThat(status.record().conversationId()).isEqualTo(upload.conversationId());
            assertThat(status.record().refToMessageId()).isEqualTo(upload.as4MessageId());
        });
        assertThat(engine.history().latestAcceptedUpload(DATASET_ID)).hasValueSatisfying(
                accepted -> assertThat(accepted.record()).isEqualTo(upload));
        assertThat(engine.received()).singleElement().satisfies(received -> assertThat(received.findings()).isEmpty());
    }

    @Test
    void invalidUploadIsAnsweredWithTheViolations() {
        transport.add(fromPlatform(Fixtures.identifierUpload("DS1-0001", DS1), T0));

        engine.poll();

        assertThat(uploadStatuses()).singleElement().satisfies(status -> {
            assertThat(status.success()).isFalse();
            assertThat(status.status()).contains(FORMAT.invalidRequestStatus());
            assertThat(status.description()).hasValueSatisfying(description -> assertThat(description).contains("UUID version 4"));
        });
    }

    @Test
    void findingsAreAttributedToTheCurrentDataSet() {
        engine.attributeTo("DS1");
        transport.add(fromPlatform("<notAMessage/>", T0));

        engine.poll();

        assertThat(engine.received()).singleElement().satisfies(received -> {
            assertThat(received.datasetKey()).isEqualTo("DS1");
            assertThat(received.findings()).extracting(finding -> finding.testCaseId()).containsExactly("T-DAT-01");
        });
    }

    @Test
    void messageIsMarkedAfterProcessingAndProcessedOnceIfItComesAgain() {
        MessageRecord upload = fromPlatform(Fixtures.identifierUpload(DATASET_ID, DS1), T0);
        transport.add(upload);
        transport.failures.add(NOT_SENT);
        transport.markFailures = 1;

        engine.poll();
        assertThat(transport.marked).isEmpty();
        assertThat(engine.history().messages()).isEmpty();

        // fetched and answered, but marking fails, so the transport returns it again
        engine.poll();
        assertThat(engine.history().messages()).hasSize(2);
        assertThat(transport.incoming).containsKey(upload.as4MessageId());

        engine.poll();
        assertThat(engine.history().messages()).hasSize(2);
        assertThat(transport.marked).containsExactly(upload.as4MessageId());
    }

    @Test
    void receivingIsRetriedUntilTheOutageLimit() {
        transport.failures.add(NOT_SENT);
        transport.failures.add(MAYBE_SENT);
        transport.add(fromPlatform(Fixtures.identifierUpload(DATASET_ID, DS1), T0));

        assertThat(engine.await(() -> engine.history().messages().stream().findFirst(), Duration.ofSeconds(5))).isPresent();

        for (int i = 0; i < 10_000; i++) {
            transport.failures.add(NOT_SENT);
        }
        assertThatThrownBy(() -> engine.await(Optional::empty, Duration.ofSeconds(5)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("for more than").hasMessageContaining("no connection");
    }

    @Test
    void anOutageIsLoggedOnceWhenItStartsAndOnceWhenItEnds() {
        List<String> log = new ArrayList<>();
        GateEngine logging = new GateEngine(FORMAT, transport, CONTEXT, Duration.ofMillis(1), Duration.ofSeconds(5), log::add);
        for (int i = 0; i < 5; i++) {
            transport.failures.add(NOT_SENT);
        }

        logging.await(Optional::empty, Duration.ofMillis(50));

        assertThat(log).hasSize(2);
        assertThat(log.getFirst()).startsWith("Receiving from the platform failed, retrying for up to 5 s: no connection");
        assertThat(log.getLast()).startsWith("The gate's access point works again");

        transport.failures.add(NOT_SENT);
        logging.poll();

        assertThat(log).hasSize(3).last().asString().startsWith("Receiving from the platform failed");
    }

    @Test
    void sendingIsRetriedOnlyIfTheMessageWasCertainlyNotSent() {
        transport.failures.add(NOT_SENT);
        transport.failures.add(NOT_SENT);

        ParsedMessage request = request();

        assertThat(transport.submitted).hasSize(3);
        assertThat(engine.history().messages()).containsExactly(request);
        assertThat(request.record().refToMessageId()).isNull();

        transport.submitted.clear();
        transport.failures.add(MAYBE_SENT);
        assertThatThrownBy(this::request).isInstanceOf(IllegalStateException.class).hasMessageContaining("may have been sent");
        assertThat(transport.submitted).hasSize(1);
    }

    @Test
    void responseTimeIsNotEvaluatedIfSendingFailedWhileTheRequestWasPending() {
        ParsedMessage request = request();
        transport.failures.add(NOT_SENT);
        request();
        transport.add(Fixtures.answer(request, Fixtures.dataResponse(request.requestId().orElseThrow(), DS1, List.of("EU01")),
                T0.plus(CONTEXT.responseTimeout()).plusSeconds(1)));

        engine.poll();

        assertThat(engine.received()).singleElement().satisfies(received -> assertThat(received.findings())
                .extracting(finding -> finding.testCaseId()).doesNotContain(TestCaseIds.ALL_04));
        assertThat(engine.responseTimesNotEvaluated()).isEqualTo(1);
    }

    @Test
    void requestPendingWhenSendingFailsIsNotOverdueAtStop() {
        request();
        assertThat(engine.overdueAtStop()).extracting(finding -> finding.testCaseId()).containsExactly(TestCaseIds.ALL_04);

        transport.failures.add(MAYBE_SENT);
        assertThatThrownBy(this::request).isInstanceOf(IllegalStateException.class);

        assertThat(engine.overdueAtStop()).isEmpty();
    }

    @Test
    void sendingGivesUpAfterTheOutageLimit() {
        for (int i = 0; i < 10_000; i++) {
            transport.failures.add(NOT_SENT);
        }

        assertThatThrownBy(this::request).isInstanceOf(IllegalStateException.class).hasMessageContaining("for more than");
    }

    @Test
    void receiptStatusesAreQueriedAgainWhileInTransit() {
        ParsedMessage request = request();
        transport.statuses.addAll(List.of("WAITING_FOR_RETRY", "SEND_ENQUEUED", Results.ACKNOWLEDGED));
        transport.failures.add(NOT_SENT);

        assertThat(engine.receiptStatuses(Duration.ofSeconds(5)))
                .containsExactly(Map.entry(request.record().as4MessageId(), Results.ACKNOWLEDGED));

        transport.statuses.clear();
        transport.statuses.add("WAITING_FOR_RETRY");
        assertThat(engine.receiptStatuses(Duration.ofMillis(20))).containsEntry(request.record().as4MessageId(), "WAITING_FOR_RETRY");
    }

    @Test
    void aReceiptStatusThatCannotBeQueriedStopsTheRunEvenIfTheOthersAreKnown() {
        request();
        ParsedMessage unknown = request();
        transport.statuses.add(Results.ACKNOWLEDGED);
        transport.statusNotQueryable.add(unknown.record().as4MessageId());

        assertThatThrownBy(() -> engine.receiptStatuses(Duration.ofMillis(20))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("The AS4 status of 1 of 2 messages");
    }

    @Test
    void discardPendingDropsLeftoversAndFailsIfTheTransportIsDown() {
        transport.add(fromPlatform(Fixtures.identifierUpload(DATASET_ID, DS1), T0));
        engine.discardPending();
        engine.poll();
        assertThat(engine.history().messages()).isEmpty();

        transport.failures.add(NOT_SENT);
        assertThatThrownBy(engine::discardPending).hasMessageContaining("no connection");
    }
}
