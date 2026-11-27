package eu.efti.tst.runner;

import eu.efti.tst.core.MessageKind;
import eu.efti.tst.core.MessageRecord;
import eu.efti.tst.core.ParsedMessage;
import eu.efti.tst.core.TestCaseIds;
import eu.efti.tst.domibus.DomibusException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static eu.efti.tst.runner.Fixtures.FORMAT;
import static eu.efti.tst.runner.Fixtures.GATE_PARTY;
import static eu.efti.tst.runner.Fixtures.PLATFORM;
import static eu.efti.tst.runner.Fixtures.PLATFORM_PARTY;

/**
 * In-memory platform behind a {@link Transport}: answers the gate's messages with the format's platform-side
 * templates (after {@link #responseDelay}), in the request's AS4 conversation and referring to it by RefToMessageId,
 * and performs the operator's actions. Without faults it behaves conformantly. Like Domibus, it returns received
 * messages until they are marked as downloaded.
 */
final class SimulatedPlatform implements Transport, Operator {

    enum Fault {
        /** The correction of the transport means is not propagated to the identifier upload. */
        STALE_UPLOAD_AFTER_CORRECTION,
        /** The correction of the transport means triggers no identifier upload at all. */
        NO_UPLOAD_AFTER_CORRECTION,
        /** Once the data set is active, every data request and follow-up is answered with success, even invalid ones. */
        ACCEPTS_EVERYTHING,
        /** Follow-ups are never acknowledged. */
        IGNORES_FOLLOW_UPS,
        /** Data requests are answered with all subsets of the data set, whatever subsets were requested. */
        IGNORES_SUBSETS,
        /** The draft is registered at the gate with an identifier upload on creation. */
        REGISTERS_DRAFT,
        /** Like {@link #REGISTERS_DRAFT}, for the first data set only. */
        REGISTERS_FIRST_DRAFT,
        /** A follow-up referring to the data request of another data set is accepted. */
        IGNORES_REQUEST_OF_OTHER_DATA_SET,
        /** Data requests are answered with the whole data set, without any filtering. */
        DOES_NOT_FILTER,
        /** While the second data set is created, an upload of the first one arrives. */
        LATE_UPLOAD_OF_FIRST_DATA_SET,
        /** Before each identifier upload, the previous one is sent again unchanged. */
        RESENDS_PREVIOUS_UPLOAD,
        /** The operator corrects the transport means right after the carrier signature, before being asked. */
        OPERATOR_AHEAD,
        /** The operator first updates the data set without the correction, then with a typo, then correctly. */
        OPERATOR_CORRECTS_IN_ATTEMPTS,
        /** Before the full identifier set, the correction is sent as an update without the transport equipment. */
        PARTIAL_UPDATE_BEFORE_FULL,
        /** The operator enters another data set ID than the one the platform shows. */
        MISTYPED_ID,
        /** A data request for the draft is answered with an error and then once more, with the data. */
        ANSWERS_DRAFT_AGAIN_WITH_DATA,
        /** Data requests for the draft are not answered. */
        IGNORES_DRAFT_REQUESTS,
        /** Once the data set is active, requests it rejects are not answered. */
        SILENT_REJECTIONS,
        /** Error responses carry a blank description. */
        BLANK_ERROR_DESCRIPTIONS,
        /** Success responses carry the UIL of the request, including foreign identifiers. */
        ECHOES_UIL,
        /** Acknowledgements echo the follow-up's own request ID instead of the one of the referenced data request. */
        ECHOES_OWN_FOLLOW_UP_ID,
        /** After the consignee signature, data requests are answered with an error. */
        REJECTS_AFTER_DELIVERY,
        /** After the consignee signature, data requests are answered twice, the second time after {@link #SECOND_RESPONSE_DELAY}. */
        ANSWERS_AGAIN_AFTER_DELIVERY,
        /** After the consignee signature, requests for a non-road data set are not answered. */
        SILENT_AFTER_NON_ROAD_DELIVERY,
        /** Responses are sent in new AS4 conversations, so only their RefToMessageId refers to the request. */
        NEW_CONVERSATIONS,
        /** Responses carry no AS4 RefToMessageId. */
        OMITS_REF_TO_MESSAGE_ID,
        /** Data responses lack the identifier of the transport means. */
        OMITS_TRANSPORT_MEANS_ID,
        /** Data responses lack the actual delivery date that the upload on the consignee signature registered. */
        OMITS_ACTUAL_DELIVERY_DATE,
        /**
         * Data responses hold the same values in another notation: weights with decimals, the carrier acceptance date
         * in UTC, country codes surrounded by whitespace, umlauts as letter plus combining diaeresis and the language
         * of the goods description as attribute.
         */
        OTHER_NOTATION_IN_RESPONSES,
        /** Identifier uploads carry a carrier acceptance date of only zeros. */
        ZERO_CARRIER_ACCEPTANCE_DATE
    }

    /** The operator's steps after the creation of a data set, in the order the gate asks for them. */
    private enum Action { CARRIER_SIGNATURE, CORRECT_TRANSPORT_MEANS, CONSIGNEE_SIGNATURE }

    private record Pending(String payload, String conversationId, String refToMessageId, Instant due) {
    }

    /** Long enough for the run to end before, unless the gate keeps polling after its last step. */
    static final Duration SECOND_RESPONSE_DELAY = Duration.ofMillis(50);

    private final Set<Fault> faults;
    private final List<Pending> outbox = new ArrayList<>();
    private final Map<String, MessageRecord> unmarked = new LinkedHashMap<>();
    private final Set<String> submitted = new HashSet<>();
    /** The data set each data request was for, by request ID. */
    private final Map<String, String> dataRequests = new HashMap<>();
    final List<ParsedMessage> received = new ArrayList<>();
    final List<String> progress = new ArrayList<>();
    final List<String> instructions = new ArrayList<>();
    final String datasetId = UUID.randomUUID().toString();
    Duration responseDelay = Duration.ZERO;
    /** The operator stops giving input at this data set (counted from 1). */
    int stopsAtDataSet = Integer.MAX_VALUE;
    /** The test case whose first request takes the gate's access point down for {@link #outage}. */
    String outageAtTestCase;
    Duration outage = Duration.ZERO;
    private Instant outageUntil = Instant.MIN;
    /** Whether the gate's access point fails to report the AS4 status of the messages sent, while all else works. */
    boolean statusQueriesFail;
    /** Whether the operator confirms each step right after performing it, instead of leaving it to the upload. */
    boolean confirms = true;
    /** A data set the platform holds or will hold, as the operator creates them in this order. */
    private static final class Held {
        final String id;
        final DatasetPlan plan;
        String xml;
        boolean active;
        boolean delivered;
        int instructed;

        Held(String id, DatasetPlan plan) {
            this.id = id;
            this.plan = plan;
            this.xml = FORMAT.testData(plan.config().testData());
        }
    }

    private final List<Held> held = new ArrayList<>();
    private int created;
    private String lastUpload;

    SimulatedPlatform(Fault... faults) {
        this.faults = faults.length == 0 ? EnumSet.noneOf(Fault.class) : EnumSet.of(faults[0], faults);
        held.add(new Held(datasetId, Fixtures.DS1_PLAN));
    }

    /** The data sets the operator creates, in this order. */
    SimulatedPlatform holding(DatasetPlan... plans) {
        held.clear();
        IntStream.range(0, plans.length).forEach(i -> held.add(new Held(i == 0 ? datasetId : UUID.randomUUID().toString(), plans[i])));
        return this;
    }

    String datasetIdOf(int index) {
        return held.get(index).id;
    }

    /** The data set the operator is currently working on. */
    private Held current() {
        return held.get(Math.max(created - 1, 0));
    }

    private Optional<Held> heldFor(ParsedMessage request) {
        return request.datasetId().flatMap(id -> held.stream().filter(candidate -> candidate.id.equalsIgnoreCase(id)).findFirst());
    }

    private void respond(ParsedMessage request, String payload, Duration delay) {
        String conversation = faults.contains(Fault.NEW_CONVERSATIONS) ? UUID.randomUUID().toString() : request.record().conversationId();
        String refToMessageId = faults.contains(Fault.OMITS_REF_TO_MESSAGE_ID) ? null : request.record().as4MessageId();
        outbox.add(new Pending(payload, conversation, refToMessageId, Instant.now().plus(delay)));
    }

    private void respond(ParsedMessage request, String payload) {
        respond(request, payload, responseDelay);
    }

    /** Tagged with the test case of the step like the pseudo-platform does, so that the sample report shows it. */
    private void upload(String identifiersOf, String testCaseId) {
        if (faults.contains(Fault.RESENDS_PREVIOUS_UPLOAD) && lastUpload != null) {
            outbox.add(new Pending(lastUpload, MessageRecord.conversationIdFor(testCaseId), null, Instant.now()));
        }
        lastUpload = Fixtures.identifierUpload(current().id, faults.contains(Fault.ZERO_CARRIER_ACCEPTANCE_DATE)
                ? identifiersOf.replaceFirst("(<carrierAcceptanceDateTime formatId=\"205\">)[^<]+", "$1000000000000+0000")
                : identifiersOf);
        outbox.add(new Pending(lastUpload, MessageRecord.conversationIdFor(testCaseId), null, Instant.now()));
    }

    private record Rejection(String status, String description) {
        Map<String, String> values(String requestId) {
            return Map.of("requestId", requestId, "status", status, "description", description);
        }
    }

    /** Why a conformant platform rejects the request; it does not check the gate identifier. */
    private Optional<Rejection> rejection(ParsedMessage request) {
        if (!FORMAT.validateSchema(request.record().payload()).isEmpty()) {
            return Optional.of(new Rejection(FORMAT.invalidRequestStatus(), "The request is not valid against the message schema."));
        }
        Optional<Held> target = heldFor(request);
        if (target.isEmpty() || held.indexOf(target.get()) >= created) {
            return Optional.of(new Rejection(FORMAT.notFoundStatus(), "Unknown data set."));
        }
        if (!target.get().active) {
            return Optional.of(new Rejection(FORMAT.notFoundStatus(), "The data set is a draft."));
        }
        if (!FORMAT.subsets().containsAll(request.subsetIds())) {
            return Optional.of(new Rejection(FORMAT.invalidRequestStatus(), "Unknown data subset."));
        }
        if (!request.platformId().equals(Optional.of(PLATFORM))) {
            return Optional.of(new Rejection(FORMAT.invalidRequestStatus(), "The request is addressed to another platform."));
        }
        if (request.referencedRequestId().filter(id -> !dataRequests.containsKey(id)).isPresent()) {
            return Optional.of(new Rejection(FORMAT.notFoundStatus(), "Unknown data request."));
        }
        if (!faults.contains(Fault.IGNORES_REQUEST_OF_OTHER_DATA_SET) && request.referencedRequestId()
                .filter(id -> !dataRequests.get(id).equalsIgnoreCase(target.get().id)).isPresent()) {
            return Optional.of(new Rejection(FORMAT.notFoundStatus(), "The data request is for another data set."));
        }
        if (target.get().delivered && request.kind() == MessageKind.DATA_REQUEST && faults.contains(Fault.REJECTS_AFTER_DELIVERY)) {
            return Optional.of(new Rejection(FORMAT.notFoundStatus(), "The transport has ended."));
        }
        return Optional.empty();
    }

    private String dataResponse(ParsedMessage request, String requestId) {
        Held target = heldFor(request).orElseGet(this::current);
        String dataset = faults.contains(Fault.OMITS_TRANSPORT_MEANS_ID)
                ? Fixtures.without(target.xml, target.plan.config().transportMeansCorrected())
                : faults.contains(Fault.OMITS_ACTUAL_DELIVERY_DATE) && target.delivered
                ? target.xml.replaceFirst("<actualOccurrenceDateTime [^>]*>[^<]*</actualOccurrenceDateTime>", "")
                : target.xml;
        String notated = faults.contains(Fault.OTHER_NOTATION_IN_RESPONSES)
                ? dataset.replaceAll("(<grossWeight [^>]*>[0-9]+)<", "$1.000<").replace(">202609150800+0200<", ">202609150600+0000<")
                .replaceAll("<countryCode>([^<]*)</countryCode>", "<countryCode>\n  $1 </countryCode>")
                .replace(">M\u00FCnchen<", ">Mu\u0308nchen<").replace("<identificationText>", "<identificationText languageId=\"en\">")
                : dataset;
        return faults.contains(Fault.DOES_NOT_FILTER) ? FORMAT.render("data-response-success", Map.of("requestId", requestId, "dataset", notated))
                : Fixtures.dataResponse(requestId, notated, faults.contains(Fault.IGNORES_SUBSETS) ? target.plan.subsets() : request.subsetIds());
    }

    private String success(ParsedMessage request, String requestId) {
        String payload = request.kind() == MessageKind.DATA_REQUEST
                ? dataResponse(request, requestId)
                : FORMAT.render("follow-up-ack-success", Map.of("requestId", requestId));
        return faults.contains(Fault.ECHOES_UIL) ? Fixtures.withUilOf(request, payload) : payload;
    }

    private void answer(ParsedMessage request) {
        request.requestId().filter(id -> request.kind() == MessageKind.DATA_REQUEST && request.datasetId().isPresent())
                .ifPresent(id -> dataRequests.put(id, request.datasetId().orElseThrow()));
        boolean active = current().active;
        Optional<String> echo = faults.contains(Fault.ECHOES_OWN_FOLLOW_UP_ID) ? request.requestId() : FORMAT.correlationKey(request);
        String requestId = echo.orElse("");
        boolean acceptsEverything = active && faults.contains(Fault.ACCEPTS_EVERYTHING);
        Optional<Rejection> rejection = (acceptsEverything ? Optional.<Rejection>empty() : rejection(request))
                .map(error -> faults.contains(Fault.BLANK_ERROR_DESCRIPTIONS) ? new Rejection(error.status(), " ") : error);
        boolean silent = echo.isEmpty() && !acceptsEverything
                || !active && faults.contains(Fault.IGNORES_DRAFT_REQUESTS)
                || active && rejection.isPresent() && faults.contains(Fault.SILENT_REJECTIONS)
                || request.kind() == MessageKind.FOLLOW_UP && faults.contains(Fault.IGNORES_FOLLOW_UPS)
                || faults.contains(Fault.SILENT_AFTER_NON_ROAD_DELIVERY)
                        && heldFor(request).filter(target -> target.delivered && !target.plan.road()).isPresent();
        if (silent || request.kind() != MessageKind.DATA_REQUEST && request.kind() != MessageKind.FOLLOW_UP) {
            return;
        }
        String prefix = request.kind() == MessageKind.DATA_REQUEST ? "data-response" : "follow-up-ack";
        String payload = rejection.map(error -> FORMAT.render(prefix + "-error", error.values(requestId)))
                .orElseGet(() -> success(request, requestId));
        respond(request, payload);
        if (faults.contains(Fault.ANSWERS_AGAIN_AFTER_DELIVERY) && current().delivered && request.kind() == MessageKind.DATA_REQUEST) {
            respond(request, payload, SECOND_RESPONSE_DELAY);
        }
        if (!active && faults.contains(Fault.ANSWERS_DRAFT_AGAIN_WITH_DATA)) {
            respond(request, Fixtures.dataResponse(requestId, current().xml, request.subsetIds()));
        }
    }

    @Override
    public MessageRecord submit(String conversationId, String refToMessageId, String payloadXml) {
        MessageRecord record = new MessageRecord(UUID.randomUUID() + "@gate", Instant.now(), MessageRecord.Direction.TO_PLATFORM,
                GATE_PARTY, PLATFORM_PARTY, conversationId, refToMessageId, payloadXml);
        submitted.add(record.as4MessageId());
        if (record.testCaseId().filter(id -> id.equals(outageAtTestCase)).isPresent()) {
            outageAtTestCase = null;
            outageUntil = record.timestamp().plus(outage);
        }
        ParsedMessage message = FORMAT.parse(record);
        received.add(message);
        answer(message);
        return record;
    }

    @Override
    public List<MessageRecord> fetchNew() {
        Instant now = Instant.now();
        if (now.isBefore(outageUntil)) {
            throw new DomibusException("no connection", null, false);
        }
        for (Iterator<Pending> pending = outbox.iterator(); pending.hasNext(); ) {
            Pending message = pending.next();
            if (!message.due().isAfter(now)) {
                MessageRecord record = new MessageRecord(UUID.randomUUID() + "@platform", now, MessageRecord.Direction.FROM_PLATFORM,
                        PLATFORM_PARTY, GATE_PARTY, message.conversationId(), message.refToMessageId(), message.payload());
                unmarked.put(record.as4MessageId(), record);
                pending.remove();
            }
        }
        return List.copyOf(unmarked.values());
    }

    @Override
    public void markDownloaded(String as4MessageId) {
        unmarked.remove(as4MessageId);
    }

    @Override
    public String status(String as4MessageId) {
        if (statusQueriesFail) {
            throw new DomibusException("status query failed");
        }
        return submitted.contains(as4MessageId) ? Results.ACKNOWLEDGED : "NOT_FOUND";
    }

    @Override
    public void progress(String message) {
        progress.add(message);
    }

    @Override
    public String createDataset(String instruction, Duration limit) {
        instructions.add(instruction);
        if (created + 1 >= stopsAtDataSet) {
            throw new IllegalStateException("No operator input");
        }
        created++;
        if (faults.contains(Fault.REGISTERS_DRAFT) || created == 1 && faults.contains(Fault.REGISTERS_FIRST_DRAFT)) {
            upload(current().xml, TestCaseIds.RUN_01);
        }
        if (created == 2 && faults.contains(Fault.LATE_UPLOAD_OF_FIRST_DATA_SET)) {
            outbox.add(new Pending(Fixtures.identifierUpload(held.getFirst().id, held.getFirst().xml),
                    MessageRecord.conversationIdFor(TestCaseIds.RUN_06), null, Instant.now()));
        }
        // UUIDs are case-insensitive; the gate must cope with the operator typing them in upper case.
        return faults.contains(Fault.MISTYPED_ID) ? UUID.randomUUID().toString() : current().id.toUpperCase();
    }

    private void correctTransportMeans() {
        Held dataset = current();
        String typo = dataset.plan.config().transportMeansTypo();
        String before = dataset.xml;
        if (faults.contains(Fault.OPERATOR_CORRECTS_IN_ATTEMPTS)) {
            upload(dataset.xml, TestCaseIds.RUN_03);
            upload(dataset.xml.replace(typo, typo + "X"), TestCaseIds.RUN_03);
        }
        dataset.xml = dataset.xml.replace(typo, dataset.plan.config().transportMeansCorrected());
        if (faults.contains(Fault.PARTIAL_UPDATE_BEFORE_FULL)) {
            upload(dataset.xml.replaceAll("(?s)<usedTransportEquipment>.*</usedTransportEquipment>", ""), TestCaseIds.RUN_03);
        }
        if (!faults.contains(Fault.NO_UPLOAD_AFTER_CORRECTION)) {
            upload(faults.contains(Fault.STALE_UPLOAD_AFTER_CORRECTION) ? before : dataset.xml, TestCaseIds.RUN_03);
        }
    }

    @Override
    public void instruct(String instruction) {
        instructions.add(instruction);
        switch (Action.values()[current().instructed++]) {
            case CARRIER_SIGNATURE -> {
                current().active = true;
                upload(current().xml, TestCaseIds.RUN_02);
                if (faults.contains(Fault.OPERATOR_AHEAD)) {
                    correctTransportMeans();
                }
            }
            case CORRECT_TRANSPORT_MEANS -> {
                if (!faults.contains(Fault.OPERATOR_AHEAD)) {
                    correctTransportMeans();
                }
            }
            case CONSIGNEE_SIGNATURE -> {
                current().delivered = true;
                current().xml = Fixtures.delivered(current().xml);
                upload(current().xml, TestCaseIds.RUN_06);
            }
        }
    }

    @Override
    public boolean confirmed() {
        return confirms;
    }
}
