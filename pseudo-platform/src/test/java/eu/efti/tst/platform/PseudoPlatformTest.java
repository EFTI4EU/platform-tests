package eu.efti.tst.platform;

import eu.efti.tst.core.Format;
import eu.efti.tst.core.MessageKind;
import eu.efti.tst.core.MessageRecord;
import eu.efti.tst.core.ParsedMessage;
import eu.efti.tst.domibus.DomibusException;
import eu.efti.tst.domibus.DomibusStub;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static eu.efti.tst.domibus.DomibusStub.base64;
import static eu.efti.tst.domibus.DomibusStub.fault;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/** The stateful platform against the real Domibus client and a local server that answers like the WS plugin. */
class PseudoPlatformTest {

    private static final Path FORMAT_DIR = Path.of("..", "formats", "ri-v0.5");
    private static final Format FORMAT = Format.load(FORMAT_DIR);
    private static final PlatformBehaviour BEHAVIOUR = PlatformBehaviour.load(FORMAT_DIR);
    private static final String DS1 = read(FORMAT_DIR.resolve("testdata/ds1-road.xml"));
    private static final String GATE = "tstgate";
    private static final String PLATFORM = "pseudoplatform";
    private static final DomibusStub.Reply REJECTED = new DomibusStub.Reply(500,
            fault("Message submission failed", "EBMS:0003", "No matching leg found"));

    private final DomibusStub domibus = DomibusStub.start();
    private final PseudoPlatform platform = new PseudoPlatform(BEHAVIOUR, domibus.client("pseudoplatform_ws", PLATFORM),
            new PlatformProperties(domibus.url(), "pseudoplatform_ws", DomibusStub.PASSWORD, PLATFORM, GATE, null));

    @AfterEach
    void stopServer() {
        domibus.close();
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static ParsedMessage parse(String payload) {
        return FORMAT.parse(new MessageRecord("id", Instant.now(), MessageRecord.Direction.FROM_PLATFORM, PLATFORM, GATE,
                "conversation", null, payload));
    }

    /** Makes a message from the gate pending and returns its request ID. */
    private String receive(String as4MessageId, String fromParty, String conversationId, String template, String datasetId) {
        String requestId = UUID.randomUUID().toString();
        String payload = FORMAT.render(template, Map.of("requestId", requestId, "gateId", GATE, "platformId", PLATFORM,
                "datasetId", datasetId, "subsetIds", "EU01", "message", "Please check the plate",
                "referencedRequestId", UUID.randomUUID().toString()));
        domibus.addPending(as4MessageId, DomibusStub.retrieveResponse(as4MessageId, fromParty, PLATFORM, conversationId,
                base64(payload)));
        return requestId;
    }

    private String receiveDataRequest(String as4MessageId, String datasetId) {
        return receive(as4MessageId, GATE, "conv-" + as4MessageId, "data-request", datasetId);
    }

    private String activeDataSet() {
        String id = platform.create(DS1);
        platform.signAsCarrier(id);
        return id;
    }

    private DataSet dataSet(String id) {
        return platform.view().dataSets().stream().filter(dataSet -> dataSet.id().equals(id)).findFirst().orElseThrow();
    }

    private void rejectSubmits() {
        domibus.override(request -> request.operation().equals("submitRequest") ? REJECTED : null);
    }

    private long receivedInLog(String as4MessageId) {
        return platform.view().messages().stream().filter(message -> message.record().as4MessageId().equals(as4MessageId)).count();
    }

    @Test
    void carrierSignatureUploadsTheIdentifiersToTheGate() {
        String id = activeDataSet();

        assertThat(dataSet(id).status()).isEqualTo(DataSet.Status.ACTIVE);
        DomibusStub.Submitted upload = domibus.submitted().getFirst();
        assertThat(upload.toParty()).isEqualTo(GATE);
        ParsedMessage parsed = parse(upload.payload());
        assertThat(parsed.kind()).isEqualTo(MessageKind.IDENTIFIER_UPLOAD);
        assertThat(parsed.datasetId()).contains(id);
        assertThat(upload.refToMessageId()).isNull();
        assertThat(platform.view().messages()).extracting(message -> message.record().as4MessageId())
                .containsExactly(upload.as4MessageId());
    }

    @Test
    void consigneeSignatureAssignsTheStatusByTheModeOfTransport() {
        String road = platform.create(DS1);
        String rail = platform.create(read(FORMAT_DIR.resolve("testdata/ds2-rail.xml")));
        Stream.of(road, rail).forEach(id -> {
            platform.signAsCarrier(id);
            platform.signAsConsignee(id);
        });

        assertThat(dataSet(road).status()).isEqualTo(DataSet.Status.INACTIVE);
        assertThat(dataSet(rail).status()).isEqualTo(DataSet.Status.READY_TO_ARCHIVE);
    }

    @Test
    void newestDataSetComesFirstAndKeepsItsPlaceWhenChanged() {
        String first = platform.create(DS1);
        String second = platform.create(DS1);
        platform.signAsCarrier(first);

        assertThat(platform.view().dataSets()).extracting(DataSet::id).containsExactly(second, first);
    }

    @Test
    void failedSubmitLeavesTheStateUnchanged() {
        String draft = platform.create(DS1);
        String active = activeDataSet();
        rejectSubmits();

        assertThatThrownBy(() -> platform.signAsCarrier(draft)).isInstanceOf(DomibusException.class);
        assertThatThrownBy(() -> platform.update(active, DS1.replace("BAB132", "BAB123"))).isInstanceOf(DomibusException.class);
        assertThatThrownBy(() -> platform.signAsConsignee(active)).isInstanceOf(DomibusException.class);

        assertThat(dataSet(draft)).isEqualTo(new DataSet(draft, DS1, DataSet.Status.DRAFT));
        assertThat(dataSet(active)).isEqualTo(new DataSet(active, DS1, DataSet.Status.ACTIVE));
        assertThat(platform.view().messages()).hasSize(1);
    }

    @Test
    void updateOfADraftSendsNoUpload() {
        String draft = platform.create(DS1);
        String corrected = DS1.replace("BAB132", "BAB123");

        assertThat(platform.update(draft, corrected)).isFalse();

        assertThat(dataSet(draft).xml()).isEqualTo(corrected);
        assertThat(domibus.submitted()).isEmpty();
    }

    @Test
    void updateOfAnActiveDataSetSendsAnUploadUnlessToggledOff() {
        String id = activeDataSet();

        assertThat(platform.update(id, DS1.replace("BAB132", "BAB123"))).isTrue();
        platform.setToggles(new FaultToggles(false, false, true));
        assertThat(platform.update(id, DS1)).isFalse();

        assertThat(domibus.submitted()).hasSize(2);
        assertThat(dataSet(id).xml()).isEqualTo(DS1);
        assertThat(platform.view().toggles().skipUploadOnUpdate()).isTrue();
    }

    @Test
    void uploadsAreSentInAConversationTaggedWithTheTestCaseOfTheirStep() {
        String id = activeDataSet();
        platform.update(id, DS1.replace("BAB132", "BAB123"));
        platform.signAsConsignee(id);

        assertThat(platform.view().messages().reversed()).extracting(message -> message.record().testCaseId().orElseThrow())
                .containsExactly("T-RUN-02", "T-RUN-03", "T-RUN-06");
    }

    @Test
    void answersGoToTheSenderInItsConversationAndAreMarkedAfterwards() {
        String id = activeDataSet();
        String requestId = receive("in-1", "othergate", "conv-42", "data-request", id);

        platform.poll();

        DomibusStub.Submitted answer = domibus.submitted().getLast();
        assertThat(answer.toParty()).isEqualTo("othergate");
        assertThat(answer.conversationId()).isEqualTo("conv-42");
        assertThat(parse(answer.payload()).requestId()).contains(requestId);
        assertThat(domibus.marked()).containsExactly("in-1");
        assertThat(receivedInLog("in-1")).isEqualTo(1);
    }

    @Test
    void answersReferToTheMessageTheyAnswer() {
        String id = activeDataSet();
        receiveDataRequest("in-1", id);
        receive("in-2", GATE, "conv-2", "follow-up", id);

        platform.poll();

        assertThat(domibus.submitted()).extracting(DomibusStub.Submitted::refToMessageId).containsExactly(null, "in-1", "in-2");
        assertThat(platform.view().messages())
                .filteredOn(message -> message.record().direction() == MessageRecord.Direction.FROM_PLATFORM)
                .extracting(message -> message.record().refToMessageId()).containsExactlyInAnyOrder(null, "in-1", "in-2");
    }

    @Test
    void messagesThatNeedNoAnswerAreMarked() {
        receive("in-1", GATE, "conv-1", "upload-status-success", "unused");

        platform.poll();

        assertThat(domibus.submitted()).isEmpty();
        assertThat(domibus.marked()).containsExactly("in-1");
        assertThat(platform.view().problems()).isEmpty();
    }

    @Test
    void rejectedAnswerIsRecordedAndRetriedOnTheNextPollWhileTheNextMessageIsAnswered() {
        String id = activeDataSet();
        receiveDataRequest("in-1", id);
        receiveDataRequest("in-2", id);
        AtomicInteger submits = new AtomicInteger();
        domibus.override(request -> request.operation().equals("submitRequest") && submits.getAndIncrement() == 0 ? REJECTED : null);

        platform.poll();

        assertThat(domibus.marked()).containsExactly("in-2");
        assertThat(domibus.pending()).containsExactly("in-1");
        assertThat(platform.view().problems()).singleElement().asString().contains("in-1", "trying again", "No matching leg");

        platform.poll();

        assertThat(domibus.marked()).containsExactly("in-2", "in-1");
        assertThat(domibus.submitted()).hasSize(3);
        assertThat(receivedInLog("in-1")).isEqualTo(1);
    }

    @Test
    void answerThatMayHaveBeenSentIsNotSentAgain() {
        String id = activeDataSet();
        receiveDataRequest("in-1", id);
        AtomicInteger submits = new AtomicInteger();
        domibus.override(request -> request.operation().equals("submitRequest") && submits.getAndIncrement() == 0
                ? DomibusStub.NO_RESPONSE : null);

        platform.poll();
        platform.poll();

        assertThat(submits).hasValue(1);
        assertThat(domibus.marked()).containsExactly("in-1");
        assertThat(platform.view().problems()).singleElement().asString().contains("in-1", "not trying again");
    }

    @Test
    void failedMarkingIsRetriedWithoutAnsweringAgain() {
        String id = activeDataSet();
        receiveDataRequest("in-1", id);
        AtomicInteger marks = new AtomicInteger();
        domibus.override(request -> request.operation().equals("markMessageAsDownloadedRequest") && marks.getAndIncrement() == 0
                ? REJECTED : null);

        platform.poll();
        assertThat(domibus.pending()).containsExactly("in-1");
        platform.poll();

        assertThat(domibus.pending()).isEmpty();
        assertThat(domibus.submitted()).hasSize(2);
        assertThat(platform.view().problems()).singleElement().asString().contains("in-1", "marking");
    }

    @Test
    void unreadableMessageIsRecordedOnceAndDoesNotBlockTheOthers() {
        String id = activeDataSet();
        receiveDataRequest("in-1", id);
        receiveDataRequest("in-2", id);
        domibus.override(request -> request.operation().equals("retrieveMessageRequest") && request.messageId().orElse("").equals("in-1")
                ? REJECTED : null);

        platform.poll();
        platform.poll();

        assertThat(domibus.marked()).containsExactly("in-2");
        assertThat(platform.view().problems()).singleElement().asString().contains("in-1", "reading failed");
    }

    @Test
    void pollErrorIsShownUntilDomibusIsReachableAgain() {
        domibus.override(request -> new DomibusStub.Reply(503, "<html>Service unavailable</html>"));

        platform.poll();
        assertThat(platform.view().connectionError()).hasValueSatisfying(error -> assertThat(error).contains("HTTP 503"));

        domibus.override(request -> null);
        platform.poll();
        assertThat(platform.view().connectionError()).isEmpty();
    }

    @Test
    void viewDoesNotWaitForASubmitInProgress() throws Exception {
        String draft = platform.create(DS1);
        CountDownLatch release = new CountDownLatch(1);
        domibus.override(request -> {
            if (request.operation().equals("submitRequest")) {
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return null;
        });
        CompletableFuture<Void> signature = CompletableFuture.runAsync(() -> platform.signAsCarrier(draft));

        PlatformView during = assertTimeoutPreemptively(Duration.ofSeconds(2), platform::view);
        release.countDown();
        signature.get(10, TimeUnit.SECONDS);

        assertThat(during.dataSets()).singleElement().extracting(DataSet::status).isEqualTo(DataSet.Status.DRAFT);
        assertThat(dataSet(draft).status()).isEqualTo(DataSet.Status.ACTIVE);
    }

    @Test
    void clearForgetsDataSetsMessagesAndFailuresButNotTogglesOrAnsweredMessages() {
        String id = activeDataSet();
        receiveDataRequest("in-1", id);
        platform.setToggles(new FaultToggles(false, true, false));
        AtomicInteger marks = new AtomicInteger();
        domibus.override(request -> request.operation().equals("markMessageAsDownloadedRequest") && marks.getAndIncrement() == 0
                ? REJECTED : null);
        platform.poll();

        platform.clear();
        platform.poll();

        PlatformView view = platform.view();
        assertThat(view.dataSets()).isEmpty();
        assertThat(view.messages()).isEmpty();
        assertThat(view.problems()).isEmpty();
        assertThat(view.toggles().ignoreSubsetFilter()).isTrue();
        assertThat(domibus.submitted()).hasSize(2);
        assertThat(domibus.pending()).isEmpty();
    }

    @Test
    void unknownDataSetIsRejected() {
        assertThatThrownBy(() -> platform.signAsCarrier("unknown")).isInstanceOf(IllegalArgumentException.class);
        assertThat(domibus.submitted()).isEmpty();
    }
}
