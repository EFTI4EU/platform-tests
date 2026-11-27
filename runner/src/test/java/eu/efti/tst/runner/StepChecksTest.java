package eu.efti.tst.runner;

import eu.efti.tst.core.Finding;
import eu.efti.tst.core.IdentifierSet;
import eu.efti.tst.core.IdentifierType;
import eu.efti.tst.core.MessageHistory;
import eu.efti.tst.core.ParsedMessage;
import eu.efti.tst.core.TestCaseResult;
import eu.efti.tst.runner.StepChecks.Expectation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static eu.efti.tst.runner.Fixtures.CONTEXT;
import static eu.efti.tst.runner.Fixtures.DATASET_ID;
import static eu.efti.tst.runner.Fixtures.DS1;
import static eu.efti.tst.runner.Fixtures.DS1_CORRECTED;
import static eu.efti.tst.runner.Fixtures.DS1_PLAN;
import static eu.efti.tst.runner.Fixtures.FORMAT;
import static eu.efti.tst.runner.Fixtures.T0;
import static eu.efti.tst.runner.Fixtures.fromPlatform;
import static eu.efti.tst.runner.Fixtures.toPlatform;
import static org.assertj.core.api.Assertions.assertThat;

class StepChecksTest {

    private static final IdentifierSet CORRECTED = DS1_PLAN.identifiersCorrected();

    private final MessageHistory history = new MessageHistory(FORMAT);
    private final ParsedMessage request = Fixtures.exchange(history, toPlatform(
            Fixtures.dataRequest("data-request", "req-1", DATASET_ID, String.join(" ", DS1_PLAN.subsets())), T0)).message();

    /** A response of the platform to the data request. */
    private ParsedMessage response(String payload) {
        return Fixtures.exchange(history, Fixtures.answer(request, payload, T0.plusSeconds(1))).message();
    }

    /** An identifier upload of the platform. */
    private ParsedMessage upload(String datasetId, String dataset) {
        return Fixtures.exchange(history, fromPlatform(Fixtures.identifierUpload(datasetId, dataset), T0.plusSeconds(1))).message();
    }

    /** The step upload among the uploads for the data set so far. */
    private Optional<ParsedMessage> stepUpload(Optional<ParsedMessage> previous, IdentifierSet expected, Set<IdentifierType> setByPlatform) {
        return StepChecks.stepUpload(StepChecks.uploadsFor(DATASET_ID, history.messages()), previous, FORMAT, CONTEXT, expected, setByPlatform);
    }

    private static List<String> details(List<Finding> findings) {
        return findings.stream().map(Finding::detail).toList();
    }

    private static IdentifierSet with(IdentifierSet set, IdentifierType type, String value) {
        Map<IdentifierType, List<String>> values = new EnumMap<>(set.values());
        values.put(type, List.of(value));
        return new IdentifierSet(values);
    }

    /** The message as parsed if it carried this data set. */
    private static ParsedMessage withDataset(ParsedMessage message, String dataset) {
        return new ParsedMessage(message.record(), message.kind(), message.malformation(), message.requestId(),
                message.referencedRequestId(), message.gateId(), message.platformId(), message.datasetId(), message.status(),
                message.success(), message.description(), message.message(), message.subsetIds(), Optional.of(dataset),
                message.identifiers());
    }

    private List<Finding> verify(Expectation expectation, List<ParsedMessage> responses) {
        return StepChecks.verify("T-X", "request", expectation, request, responses, Optional.empty());
    }

    @Test
    void missingResponseFailsUnlessNoResponseIsAcceptable() {
        assertThat(verify(Expectation.ERROR, List.of())).singleElement().satisfies(finding -> {
            assertThat(finding.as4MessageId()).isEqualTo(request.record().as4MessageId());
            assertThat(finding.detail()).isEqualTo("request: no response within the response timeout.");
        });
        assertThat(verify(Expectation.ANY, List.of())).hasSize(1);
        assertThat(verify(Expectation.NO_SUCCESS, List.of())).isEmpty();
    }

    @Test
    void errorExpectationRejectsSuccessAndData() {
        ParsedMessage success = response(Fixtures.dataResponse("req-1", DS1, List.of("EU01")));
        ParsedMessage error = response(Fixtures.errorResponse("data-response-error", "req-1", "Unknown data set"));

        assertThat(details(verify(Expectation.ERROR, List.of(success)))).containsExactly(
                "request: answered with success status instead of an error.", "request: the response carries a data set.");
        assertThat(verify(Expectation.ERROR, List.of(error))).isEmpty();
        assertThat(verify(Expectation.NO_SUCCESS, List.of(error))).isEmpty();
        assertThat(verify(Expectation.SUCCESS_WITH_DATA, List.of(error))).extracting(Finding::detail)
                .containsExactly("request: answered with status " + FORMAT.notFoundStatus() + " instead of success.");
    }

    @Test
    void everyResponseIsVerifiedSoThatALaterOneWithDataFails() {
        ParsedMessage error = response(Fixtures.errorResponse("data-response-error", "req-1", "draft"));
        ParsedMessage data = response(Fixtures.dataResponse("req-1", DS1, List.of("EU01")));

        assertThat(history.allResponsesTo(request)).containsExactly(error, data);
        assertThat(verify(Expectation.ERROR, history.allResponsesTo(request))).extracting(Finding::as4MessageId)
                .containsOnly(data.record().as4MessageId()).hasSize(2);
    }

    @Test
    void errorWithDescriptionNeedsANonBlankDescription() {
        ParsedMessage withoutDescription = response(Fixtures.errorResponse("data-response-error", "req-1", " "));

        assertThat(details(verify(Expectation.ERROR_WITH_DESCRIPTION, List.of(withoutDescription))))
                .containsExactly("request: the error response carries no error description.");
        assertThat(verify(Expectation.ERROR, List.of(withoutDescription))).isEmpty();
    }

    @Test
    void successWithoutDataSetFailsDataExpectation() {
        ParsedMessage empty = response(FORMAT.render("data-response-success", Map.of("requestId", "req-1", "dataset", "")));

        assertThat(details(verify(Expectation.SUCCESS_WITH_DATA, List.of(empty))))
                .containsExactly("request: the success response carries no data set.");
        assertThat(verify(Expectation.SUCCESS, List.of(empty))).isEmpty();
    }

    @Test
    void successEchoingAnInjectedIdentifierFails() {
        ParsedMessage echoing = Fixtures.withUil(response(Fixtures.dataResponse("req-1", DS1, List.of("EU01"))), "G", "FOREIGN");
        ParsedMessage error = Fixtures.withUil(response(Fixtures.errorResponse("data-response-error", "req-1", "x")), "G", "FOREIGN");

        assertThat(StepChecks.verify("T-X", "foreign", Expectation.ANY, request, List.of(echoing), Optional.of("FOREIGN")))
                .extracting(Finding::detail).containsExactly("foreign: the success response carries the injected identifier FOREIGN.");
        assertThat(StepChecks.verify("T-X", "foreign", Expectation.ANY, request, List.of(echoing), Optional.of("OTHER"))).isEmpty();
        // an error response may echo it
        assertThat(StepChecks.verify("T-X", "foreign", Expectation.ANY, request, List.of(error), Optional.of("FOREIGN"))).isEmpty();
    }

    @Test
    void observedDescribesTheReaction() {
        assertThat(StepChecks.observed("a", List.of())).isEqualTo("a: no response");
        assertThat(StepChecks.observed("b", List.of(response(Fixtures.errorResponse("data-response-error", "req-1", "x")))))
                .isEqualTo("b: status " + FORMAT.notFoundStatus());
    }

    @Test
    void gateValidationReportsSchemaAndRuleViolationsWithoutThrowing() {
        assertThat(StepChecks.gateValidation(FORMAT, Fixtures.identifierUpload(DATASET_ID, DS1))).isEmpty();
        assertThat(StepChecks.gateValidation(FORMAT, Fixtures.identifierUpload("not-a-uuid", DS1)))
                .singleElement().asString().contains("not a UUID version 4");
        assertThat(StepChecks.gateValidation(FORMAT, "<notWellFormed")).isNotEmpty();
    }

    @Test
    void uploadWithoutModeOfTransportAndDangerousGoodsIndicatorIsRejectedAndFailsTheCompositionOfTheIdentifierSet() {
        String withoutMovement = Fixtures.identifierUpload(DATASET_ID,
                DS1.replaceAll("(?s)<mainCarriageTransportMovement>.*</mainCarriageTransportMovement>", ""));

        assertThat(StepChecks.gateValidation(FORMAT, withoutMovement)).containsExactlyInAnyOrder(
                "The identifier upload does not contain the mode of transport.",
                "The identifier upload does not contain the dangerous goods indicator.");
        assertThat(Fixtures.exchange(history, fromPlatform(withoutMovement, T0.plusSeconds(1))).findings())
                .extracting(Finding::testCaseId).containsExactly("T-DAT-03", "T-DAT-03");
    }

    @ParameterizedTest
    @CsvSource({"102, 20260915", "102, 20240229", "102, ' 20260915 '", "203, 202609150800", "203, 202609152359",
            "205, 202609150800+0200", "205, 202609150800-0500", "205, 202609150800+0000"})
    void dateValuesMatchingTheirFormatCodePass(String formatId, String value) {
        assertThat(StepChecks.gateValidation(FORMAT, responseWithUnloadingDate(formatId, value))).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"102, 00000000", "102, 99999999", "102, --------", "102, 20261301", "102, 20260931", "102, 20250229",
            "102, 2026-09-15", "102, 202609150800", "203, 20260915", "203, 202609152500", "203, 202609150860",
            "203, 202609150800+0200", "205, 202609150800", "205, 202609150800+1500", "205, 202609150800+0260",
            "205, 20260915+0200", "205, 202609150800+02", "205, 000000000000+0000"})
    void dateValuesNotMatchingTheirFormatCodeAreRejected(String formatId, String value) {
        assertThat(StepChecks.gateValidation(FORMAT, responseWithUnloadingDate(formatId, value)))
                .singleElement().asString().contains("scheduledOccurrenceDateTime", "'" + value + "'", "format code " + formatId);
    }

    @Test
    void identifierDatesRejectFormat203ButNotTheOtherFormats() {
        String carrierDate = "<carrierAcceptanceDateTime formatId=\"205\">202609150800+0200";
        String deliveryDate = "(<actualOccurrenceDateTime formatId=\")205(\">)[^<]+";
        String delivered = Fixtures.delivered(DS1);

        assertThat(StepChecks.gateValidation(FORMAT, Fixtures.identifierUpload(DATASET_ID,
                DS1.replace(carrierDate, "<carrierAcceptanceDateTime formatId=\"203\">202609150800"))))
                .singleElement().asString().contains("203 is not allowed on carrierAcceptanceDateTime");
        assertThat(StepChecks.gateValidation(FORMAT, Fixtures.identifierUpload(DATASET_ID,
                delivered.replaceFirst(deliveryDate, "$1203$2202609150800"))))
                .singleElement().asString().contains("203 is not allowed on actualOccurrenceDateTime");
        assertThat(StepChecks.gateValidation(FORMAT, Fixtures.identifierUpload(DATASET_ID,
                DS1.replace(carrierDate, "<carrierAcceptanceDateTime formatId=\"102\">20260915")))).isEmpty();
        assertThat(StepChecks.gateValidation(FORMAT, Fixtures.identifierUpload(DATASET_ID,
                delivered.replaceFirst(deliveryDate, "$1102$220260915")))).isEmpty();
        assertThat(StepChecks.gateValidation(FORMAT, Fixtures.identifierUpload(DATASET_ID, delivered))).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"202609150800+0200, 202609150600+0000, true", "202609150800+0200, 202609142100-0900, true",
            "202609150800+0200, 202609150800+0100, false", "202609150800+0200, 202609150801+0200, false",
            "00000000, 00000000, true", "00000000, 00000001, false"})
    void carrierAcceptanceDatesCompareByInstant(String registered, String returned, boolean agree) {
        assertThat(IdentifierSet.agreeOnReturnedFields(carrierAcceptanceDate("205", registered), carrierAcceptanceDate("205", returned)))
                .isEqualTo(agree);
    }

    @Test
    void carrierAcceptanceDatesInDifferentFormatsDiffer() {
        assertThat(IdentifierSet.agreeOnReturnedFields(carrierAcceptanceDate("102", "20260915"),
                carrierAcceptanceDate("205", "202609150000+0000"))).isFalse();
    }

    @Test
    void equipmentSequenceNumbersCompareByValue() {
        List<String> values = FORMAT.identifiers(DS1).get(IdentifierType.TRANSPORT_EQUIPMENT);

        assertThat(values).singleElement().asString().endsWith("|1");
        assertThat(FORMAT.identifiers(DS1.replace(">1</sequenceNumber>", ">01</sequenceNumber>")).get(IdentifierType.TRANSPORT_EQUIPMENT))
                .isEqualTo(values);
        assertThat(FORMAT.identifiers(DS1.replace("<sequenceNumber>1</sequenceNumber>", "")).get(IdentifierType.TRANSPORT_EQUIPMENT))
                .singleElement().asString().endsWith("|");
    }

    private static List<String> carrierAcceptanceDate(String formatId, String value) {
        return FORMAT.identifiers(DS1.replaceFirst("<carrierAcceptanceDateTime formatId=\"205\">[^<]+",
                "<carrierAcceptanceDateTime formatId=\"" + formatId + "\">" + value)).get(IdentifierType.CARRIER_ACCEPTANCE_DATE);
    }

    private static String responseWithUnloadingDate(String formatId, String value) {
        String dataset = DS1.replaceFirst("<scheduledOccurrenceDateTime formatId=\"102\">[^<]+",
                "<scheduledOccurrenceDateTime formatId=\"" + formatId + "\">" + value);
        return FORMAT.render("data-response-success", Map.of("requestId", "req-1", "dataset", dataset));
    }

    private List<Finding> registration(ParsedMessage upload, IdentifierSet expected, Set<IdentifierType> setByPlatform) {
        return StepChecks.registration("T-RUN-03", upload, FORMAT, CONTEXT, expected, setByPlatform);
    }

    private List<Finding> registration(String uploadedDataset, Set<IdentifierType> setByPlatform) {
        return registration(upload(DATASET_ID, uploadedDataset), CORRECTED, setByPlatform);
    }

    @Test
    void registrationNeedsTheExpectedIdentifierValues() {
        IdentifierType means = IdentifierType.TRANSPORT_MEANS;

        assertThat(registration(DS1_CORRECTED, Set.of())).isEmpty();
        assertThat(details(registration(DS1, Set.of()))).containsExactly("Identifier TRANSPORT_MEANS: expected "
                + CORRECTED.get(means) + " but found " + DS1_PLAN.identifiersAsCreated().get(means) + ".");
    }

    @Test
    void registrationNeedsTheModeOfTransportAndDangerousGoodsIndicatorOfTheTestData() {
        ParsedMessage upload = upload(DATASET_ID, DS1_CORRECTED);

        for (IdentifierType type : List.of(IdentifierType.MODE_OF_TRANSPORT, IdentifierType.DANGEROUS_GOODS)) {
            assertThat(details(registration(upload, with(CORRECTED, type, "other"), Set.of())))
                    .containsExactly("Identifier " + type + ": expected [other] but found " + CORRECTED.get(type) + ".");
        }
    }

    @Test
    void registrationNeedsTheGateAndPlatformIdentifiersInTheUil() {
        ParsedMessage upload = upload(DATASET_ID, DS1_CORRECTED);

        assertThat(registration(Fixtures.withUil(upload, CONTEXT.gateId(), CONTEXT.platformId()), CORRECTED, Set.of())).isEmpty();
        assertThat(details(registration(Fixtures.withUil(upload, "OTHER-GATE", "OTHER-PF"), CORRECTED, Set.of()))).containsExactly(
                "The UIL carries the gate identifier OTHER-GATE instead of " + CONTEXT.gateId() + ".",
                "The UIL carries the platform identifier OTHER-PF instead of " + CONTEXT.platformId() + ".");
    }

    @Test
    void partialIdentifierSetFailsTheStep() {
        // the data set restricted to one subset holds fewer identifiers
        String partial = FORMAT.filterBySubsets(DS1_CORRECTED, List.of(DS1_PLAN.subsets().getFirst()));
        IdentifierSet partialValues = FORMAT.identifiers(partial);
        List<IdentifierType> lacking = Arrays.stream(IdentifierType.values())
                .filter(type -> partialValues.get(type).isEmpty() && !CORRECTED.get(type).isEmpty())
                .toList();

        assertThat(lacking).isNotEmpty();
        assertThat(details(registration(partial, Set.of())))
                .containsAll(lacking.stream().map(type -> "Identifier " + type + ": expected " + CORRECTED.get(type) + " but found [].").toList());
    }

    @Test
    void registrationNeedsAnyCarrierAcceptanceDate() {
        String restamped = DS1_CORRECTED.replace("202609150800+0200", "202609151012+0200");
        String withoutDate = Fixtures.without(DS1_CORRECTED, "202609150800+0200");

        assertThat(registration(restamped, Set.of())).isEmpty();
        assertThat(details(registration(withoutDate, Set.of()))).containsExactly("Identifier CARRIER_ACCEPTANCE_DATE: no value.");
    }

    @Test
    void registrationAtTheEndOfTransportNeedsTheDeliveryDate() {
        Set<IdentifierType> endOfTransport = StepChecks.SET_AT_CONSIGNEE_SIGNATURE;
        String delivered = Fixtures.delivered(DS1_CORRECTED);

        assertThat(FORMAT.identifiers(delivered).get(IdentifierType.ACTUAL_DELIVERY_DATE)).isNotEmpty();
        assertThat(registration(delivered, endOfTransport)).isEmpty();
        assertThat(details(registration(DS1_CORRECTED, endOfTransport)))
                .containsExactly("Identifier ACTUAL_DELIVERY_DATE: no value.");
        // without the expectation, a delivery date before the consignee signature is a deviation
        assertThat(details(registration(delivered, Set.of())))
                .singleElement().asString().startsWith("Identifier ACTUAL_DELIVERY_DATE: expected []");
    }

    @Test
    void registrationReportsRejectedUploads() {
        ParsedMessage invalid = upload("DS1-0001", DS1_CORRECTED);

        assertThat(StepChecks.registration("T-RUN-02", invalid, FORMAT, CONTEXT, CORRECTED, Set.of()))
                .singleElement().satisfies(finding -> assertThat(finding.detail()).startsWith("Rejected by the gate's validation:"));
    }

    @Test
    void missingUploadPointsOutUploadsForOtherDataSets() {
        String other = "1c6f3f3e-3c1a-4f6e-9d4b-2a7c5e8f1a91";
        upload(other, DS1);
        upload(other, DS1_CORRECTED);

        Finding finding = StepChecks.noUpload("T-RUN-02", DATASET_ID, history.messages());

        assertThat(finding.as4MessageId()).isNull();
        assertThat(finding.detail()).startsWith("No identifier upload for the data set " + DATASET_ID + " within")
                .endsWith("The platform uploaded identifiers for other data set IDs (" + other + "): was the data set ID "
                        + "entered correctly?");
        assertThat(StepChecks.noUpload("T-RUN-02", other.toUpperCase(), history.messages()).detail()).doesNotContain("other");
    }

    @Test
    void stepUploadIsTheFirstUploadAfterThePreviousOneThatPassesTheChecks() {
        ParsedMessage activation = upload(DATASET_ID, DS1);
        ParsedMessage resent = upload(DATASET_ID, DS1);
        ParsedMessage otherDataset = upload("1c6f3f3e-3c1a-4f6e-9d4b-2a7c5e8f1a91", DS1_CORRECTED);

        assertThat(StepChecks.uploadsFor(DATASET_ID.toUpperCase(), history.messages()))
                .containsExactly(activation, resent).doesNotContain(otherDataset);
        assertThat(stepUpload(Optional.empty(), DS1_PLAN.identifiersAsCreated(), Set.of())).contains(activation);
        // neither a resent upload nor one with a wrong correction is the next step's upload; without the right one, the
        // latest is evaluated
        ParsedMessage typo = upload(DATASET_ID, DS1.replace("BAB132", "BAB124"));
        List<ParsedMessage> uploads = StepChecks.uploadsFor(DATASET_ID, history.messages());
        assertThat(stepUpload(Optional.of(activation), CORRECTED, Set.of())).isEmpty();
        assertThat(StepChecks.latestUpload(uploads, Optional.of(activation))).contains(typo);
        assertThat(StepChecks.latestUpload(uploads, Optional.of(typo))).isEmpty();

        ParsedMessage correction = upload(DATASET_ID, DS1_CORRECTED);
        upload(DATASET_ID, DS1);
        assertThat(stepUpload(Optional.of(activation), CORRECTED, Set.of())).contains(correction);
    }

    @Test
    void uploadsBeforeTheEvaluatedOneAreSkippedAndNamedInANote() {
        ParsedMessage activation = upload(DATASET_ID, DS1);
        ParsedMessage unchanged = upload(DATASET_ID, DS1);
        ParsedMessage typo = upload(DATASET_ID, DS1.replace("BAB132", "BAB124"));
        ParsedMessage correction = upload(DATASET_ID, DS1_CORRECTED);
        upload(DATASET_ID, DS1_CORRECTED);
        List<ParsedMessage> uploads = StepChecks.uploadsFor(DATASET_ID, history.messages());

        List<ParsedMessage> skipped = StepChecks.skippedUploads(uploads, Optional.of(activation), Optional.of(correction));

        assertThat(skipped).containsExactly(unchanged, typo);
        assertThat(StepChecks.skippedUploadsNote(skipped)).contains("2 earlier identifier upload(s) did not count for this step, "
                + "as they did not pass its checks (AS4 message " + unchanged.record().as4MessageId() + ", "
                + typo.record().as4MessageId() + ")");
        assertThat(StepChecks.skippedUploads(uploads, Optional.empty(), Optional.of(activation))).isEmpty();
        assertThat(StepChecks.skippedUploads(uploads, Optional.of(activation), Optional.empty())).isEmpty();
        assertThat(StepChecks.skippedUploadsNote(List.of())).isEmpty();
    }

    @Test
    void uploadThatOnlyAddsTheEndOfTransportIsTheStepUpload() {
        ParsedMessage correction = upload(DATASET_ID, DS1_CORRECTED);
        upload(DATASET_ID, DS1_CORRECTED);
        ParsedMessage delivery = upload(DATASET_ID, Fixtures.delivered(DS1_CORRECTED));

        assertThat(stepUpload(Optional.of(correction), CORRECTED, StepChecks.SET_AT_CONSIGNEE_SIGNATURE)).contains(delivery);
    }

    @Test
    void everyUploadBeforeTheCarrierSignatureIsARegistrationOfTheDraft() {
        ParsedMessage upload = upload(DATASET_ID, DS1);

        assertThat(StepChecks.draftRegistrations("T-RUN-01", List.of(upload))).singleElement()
                .satisfies(finding -> assertThat(finding.as4MessageId()).isEqualTo(upload.record().as4MessageId()));
        assertThat(StepChecks.draftRegistrations("T-RUN-01", List.of())).isEmpty();
    }

    @Test
    void uilCheckIsNotedAsSkippedIfTheUploadCarriesNoUil() {
        ParsedMessage upload = upload(DATASET_ID, DS1);

        assertThat(StepChecks.uncheckedUil(upload, FORMAT)).hasValueSatisfying(note -> assertThat(note).contains(FORMAT.version()));
        assertThat(StepChecks.uncheckedUil(Fixtures.withUil(upload, "g", "p"), FORMAT)).isEmpty();
    }

    @Test
    void elementsOutsideTheRequestedSubsetAreFindingsOfTheStep() {
        List<String> single = List.of(DS1_PLAN.subsets().getFirst());
        ParsedMessage singleRequest = Fixtures.exchange(history, toPlatform(
                Fixtures.dataRequest("data-request", "req-2", DATASET_ID, single.getFirst()), T0)).message();
        ParsedMessage all = response(Fixtures.dataResponse("req-2", DS1_CORRECTED, DS1_PLAN.subsets()));
        ParsedMessage filtered = response(Fixtures.dataResponse("req-2", DS1_CORRECTED, single));
        ParsedMessage error = response(Fixtures.errorResponse("data-response-error", "req-2", "x"));

        assertThat(StepChecks.outsideSubsets("T-RUN-04", "(b)", singleRequest, Optional.of(all), FORMAT)).isNotEmpty()
                .allSatisfy(finding -> {
                    assertThat(finding.testCaseId()).isEqualTo("T-RUN-04");
                    assertThat(finding.as4MessageId()).isEqualTo(all.record().as4MessageId());
                    assertThat(finding.detail()).startsWith("(b): /").endsWith(" is not part of the requested subsets.");
                });
        assertThat(StepChecks.outsideSubsets("T-RUN-04", "(b)", singleRequest, Optional.of(filtered), FORMAT)).isEmpty();
        assertThat(StepChecks.outsideSubsets("T-RUN-04", "(b)", singleRequest, Optional.of(error), FORMAT)).isEmpty();
        assertThat(StepChecks.outsideSubsets("T-RUN-04", "(b)", singleRequest, Optional.empty(), FORMAT)).isEmpty();
        // a data set that cannot be evaluated does not pass silently
        assertThat(StepChecks.outsideSubsets("T-RUN-04", "(b)", singleRequest, Optional.of(withDataset(all, "<other/>")), FORMAT))
                .singleElement().satisfies(finding -> assertThat(finding.detail()).startsWith("(b): the returned data set cannot be evaluated"));
    }

    @Test
    void dataReturnedComparesTheIdentifiersTheDataSetHolds() {
        IdentifierType means = IdentifierType.TRANSPORT_MEANS;
        ParsedMessage corrected = response(Fixtures.dataResponse("req-1", DS1_CORRECTED, DS1_PLAN.subsets()));
        ParsedMessage stale = response(Fixtures.dataResponse("req-1", DS1, DS1_PLAN.subsets()));
        Optional<IdentifierSet> expected = Optional.of(CORRECTED);

        // the subsets need not hold every identifier (e.g. not the mode of transport), which are then not compared
        assertThat(StepChecks.dataReturned("T-RUN-04", "(a)", request, Optional.of(corrected), FORMAT, expected)).isEmpty();
        assertThat(StepChecks.dataReturned("T-RUN-04", "(a)", request, Optional.of(stale), FORMAT, expected))
                .extracting(Finding::detail).containsExactly("(a): TRANSPORT_MEANS expected " + CORRECTED.get(means)
                        + " but the data set contains " + DS1_PLAN.identifiersAsCreated().get(means) + ".");
        assertThat(StepChecks.dataReturned("T-RUN-04", "(b)", request, Optional.of(stale), FORMAT, Optional.empty())).isEmpty();
        ParsedMessage restamped = response(Fixtures.dataResponse("req-1",
                DS1_CORRECTED.replace("202609150800+0200", "202609151012+0200"), DS1_PLAN.subsets()));
        assertThat(restamped.dataset().orElseThrow()).contains("202609151012+0200");
        assertThat(StepChecks.dataReturned("T-RUN-04", "(a)", request, Optional.of(restamped), FORMAT, expected)).isEmpty();
        assertThat(StepChecks.dataReturned("T-RUN-04", "(b)", request, Optional.empty(), FORMAT, expected)).hasSize(1);
    }

    @Test
    void dataReturnedComparesOnlyTheFieldsTheDataSetHolds() {
        String corrected = DS1_PLAN.config().transportMeansCorrected();
        ParsedMessage withoutCountry = response(Fixtures.dataResponse("req-1",
                Fixtures.withoutSiblingsOf(DS1_CORRECTED, corrected), DS1_PLAN.subsets()));

        assertThat(StepChecks.dataReturned("T-RUN-04", "(a)", request, Optional.of(withoutCountry), FORMAT, Optional.of(CORRECTED))).isEmpty();
        assertThat(StepChecks.correctionNotReturned("(a)", Optional.of(withoutCountry), FORMAT, corrected)).isEmpty();
    }

    @Test
    void correctionNotReturnedNotesAMissingIdentifierOfTheTransportMeans() {
        String corrected = DS1_PLAN.config().transportMeansCorrected();
        ParsedMessage withoutId = response(Fixtures.dataResponse("req-1", Fixtures.without(DS1_CORRECTED, corrected), DS1_PLAN.subsets()));
        ParsedMessage complete = response(Fixtures.dataResponse("req-1", DS1_CORRECTED, DS1_PLAN.subsets()));

        assertThat(StepChecks.correctionNotReturned("(a)", Optional.of(withoutId), FORMAT, corrected)).isPresent();
        assertThat(StepChecks.correctionNotReturned("(a)", Optional.of(complete), FORMAT, corrected)).isEmpty();
        assertThat(StepChecks.correctionNotReturned("(a)", Optional.empty(), FORMAT, corrected)).isEmpty();
    }

    @Test
    void errorResponseToADataRequestOfTheLifecycleFails() {
        ParsedMessage error = response(Fixtures.errorResponse("data-response-error", "req-1", "x"));

        for (Optional<IdentifierSet> expected : List.of(Optional.of(CORRECTED), Optional.<IdentifierSet>empty())) {
            assertThat(details(StepChecks.dataReturned("T-RUN-04", "(a)", request, Optional.of(error), FORMAT, expected)))
                    .containsExactly("(a): answered with status " + FORMAT.notFoundStatus() + " instead of success.");
        }
    }

    @Test
    void acknowledgementMustBeASuccessEchoingTheReferencedDataRequest() {
        ParsedMessage followUp = Fixtures.exchange(history, toPlatform(Fixtures.followUp("follow-up", "fu-1", "req-1"), T0)).message();
        ParsedMessage echoingDataRequest = Fixtures.exchange(history, Fixtures.answer(followUp,
                FORMAT.render("follow-up-ack-success", Map.of("requestId", "req-1")), T0)).message();
        ParsedMessage echoingFollowUp = Fixtures.exchange(history, Fixtures.answer(followUp,
                FORMAT.render("follow-up-ack-success", Map.of("requestId", "fu-1")), T0)).message();
        ParsedMessage error = Fixtures.exchange(history, Fixtures.answer(followUp,
                Fixtures.errorResponse("follow-up-ack-error", "req-1", "x"), T0)).message();

        assertThat(StepChecks.echoesDataRequest("T-RUN-05", "(a)", "req-1", Optional.of(echoingDataRequest))).isEmpty();
        assertThat(StepChecks.echoesDataRequest("T-RUN-05", "(a)", "req-1", Optional.empty())).isEmpty();
        assertThat(details(StepChecks.echoesDataRequest("T-RUN-05", "(a)", "req-1", Optional.of(echoingFollowUp))))
                .containsExactly("(a): the acknowledgement echoes request ID fu-1 instead of req-1, the request ID of the data "
                        + "request the follow-up refers to.");
        assertThat(details(StepChecks.verify("T-RUN-05", "(a)", Expectation.SUCCESS, followUp, List.of(error), Optional.empty())))
                .containsExactly("(a): answered with status " + FORMAT.notFoundStatus() + " instead of success.");
    }

    @Test
    void resultPassesWithoutFailures() {
        assertThat(StepChecks.result("T-RUN-01", "DS1", List.of(), "n").outcome()).isEqualTo(TestCaseResult.Outcome.PASSED);
        assertThat(StepChecks.result("T-RUN-01", "DS1", List.of(new Finding("T-RUN-01", null, "x")), null).outcome())
                .isEqualTo(TestCaseResult.Outcome.FAILED);
    }
}
