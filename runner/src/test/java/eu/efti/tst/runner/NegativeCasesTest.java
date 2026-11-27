package eu.efti.tst.runner;

import eu.efti.tst.core.CheckedMessage;
import eu.efti.tst.core.Finding;
import eu.efti.tst.core.MessageHistory;
import eu.efti.tst.core.MessageRecord;
import eu.efti.tst.core.ParsedMessage;
import eu.efti.tst.core.TestCaseResult;
import eu.efti.tst.runner.NegativeCases.Probe;
import eu.efti.tst.runner.StepChecks.Expectation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static eu.efti.tst.runner.Fixtures.DATASET_ID;
import static eu.efti.tst.runner.Fixtures.DS1;
import static eu.efti.tst.runner.Fixtures.FORMAT;
import static eu.efti.tst.runner.Fixtures.T0;
import static eu.efti.tst.runner.Fixtures.fromPlatform;
import static eu.efti.tst.runner.Fixtures.toPlatform;
import static org.assertj.core.api.Assertions.assertThat;

class NegativeCasesTest {

    /** An error response that echoes no request ID. */
    private static final String NO_REQUEST_ID_RESPONSE = FORMAT.render("data-response-error",
            Map.of("requestId", "", "status", FORMAT.invalidRequestStatus(), "description", "no request ID"));

    private final MessageHistory history = new MessageHistory(FORMAT);

    private CheckedMessage exchange(MessageRecord record) {
        return Fixtures.exchange(history, record);
    }

    private CheckedMessage answer(ParsedMessage request, String payload) {
        return exchange(Fixtures.answer(request, payload, T0));
    }

    private ParsedMessage sent(String payload) {
        return exchange(toPlatform(payload, T0)).message();
    }

    private Probe probe(String testCaseId, String label, Expectation expectation, Optional<String> injected, String requestPayload) {
        return new Probe(testCaseId, label, expectation, injected, sent(requestPayload));
    }

    private static String render(String template, String gateId, String platformId) {
        return FORMAT.render(template, Map.of("requestId", "req-1", "referencedRequestId", "req-0", "gateId", gateId,
                "platformId", platformId, "datasetId", DATASET_ID, "subsetIds", "EU01", "message", "Please check"));
    }

    private Probe missingRequestIdProbe() {
        return probe("T-NEG-02", "without request ID", Expectation.NO_SUCCESS, Optional.empty(),
                render("data-request-missing-request-id", Fixtures.GATE, Fixtures.PLATFORM));
    }

    private Probe dataRequestProbe(String label, String requestId, Expectation expectation, Optional<String> injected) {
        return probe("T-NEG-04", label, expectation, injected, Fixtures.dataRequest("data-request", requestId, DATASET_ID, "EU01"));
    }

    @Test
    void malformedRequestsRequireAnErrorUnlessTheyLackEveryValueAResponseCanEcho() {
        assertThat(NegativeCases.forMalformed(sent(render("data-request-invalid", "g", "p")), FORMAT)).isEqualTo(Expectation.ERROR);
        assertThat(NegativeCases.forMalformed(sent(render("follow-up-invalid", "g", "p")), FORMAT)).isEqualTo(Expectation.ERROR);
        assertThat(NegativeCases.forMalformed(sent(render("data-request-missing-request-id", "g", "p")), FORMAT))
                .isEqualTo(Expectation.NO_SUCCESS);
        assertThat(NegativeCases.forMalformed(sent(render("follow-up-missing-request-id", "g", "p")), FORMAT))
                .isEqualTo(Expectation.NO_SUCCESS);
        // a follow-up without its own request ID that still names the data request can be acknowledged correlatably
        ParsedMessage followUp = sent(render("follow-up", "g", "p"));
        ParsedMessage withoutOwnRequestId = new ParsedMessage(followUp.record(), followUp.kind(), followUp.malformation(),
                Optional.empty(), followUp.referencedRequestId(), followUp.gateId(), followUp.platformId(), followUp.datasetId(),
                followUp.status(), followUp.success(), followUp.description(), followUp.message(), followUp.subsetIds(),
                followUp.dataset(), followUp.identifiers());
        assertThat(NegativeCases.forMalformed(withoutOwnRequestId, FORMAT)).isEqualTo(Expectation.ERROR);
    }

    @Test
    void responseReferringToAProbeWithoutRequestIdIsItsResponseWithoutCorrelationFinding() {
        exchange(fromPlatform(NO_REQUEST_ID_RESPONSE, T0));
        Probe probe = missingRequestIdProbe();

        CheckedMessage response = answer(probe.request(), NO_REQUEST_ID_RESPONSE);

        assertThat(response.findings()).extracting(Finding::testCaseId).doesNotContain("T-ALL-03");
        assertThat(NegativeCases.evaluate("T-NEG-02", List.of(probe), history).outcome()).isEqualTo(TestCaseResult.Outcome.PASSED);
    }

    @Test
    void negativeCaseFailsOnAnyViolatedProbe() {
        Probe probe = missingRequestIdProbe();
        answer(probe.request(), Fixtures.dataResponse("", DS1, List.of("EU01")));

        TestCaseResult result = NegativeCases.evaluate("T-NEG-02", List.of(probe), history);

        assertThat(result.outcome()).isEqualTo(TestCaseResult.Outcome.FAILED);
        assertThat(result.findings()).extracting(Finding::testCaseId).containsOnly("T-NEG-02");
    }

    @Test
    void everyResponseToAProbeIsEvaluated() {
        Probe probe = missingRequestIdProbe();
        answer(probe.request(), NO_REQUEST_ID_RESPONSE);
        ParsedMessage success = answer(probe.request(), Fixtures.dataResponse("", DS1, List.of("EU01"))).message();

        TestCaseResult result = NegativeCases.evaluate("T-NEG-02", List.of(probe), history);

        assertThat(result.findings()).extracting(Finding::as4MessageId).containsOnly(success.record().as4MessageId()).isNotEmpty();
    }

    @Test
    void informativeCasesRecordTheObservedBehaviour() {
        Probe platform = dataRequestProbe("foreign platform ID", "req-1", Expectation.ANY, Optional.of("XX"));
        Probe gate = dataRequestProbe("foreign gate ID", "req-2", Expectation.ANY, Optional.of("YY"));
        answer(platform.request(), Fixtures.errorResponse("data-response-error", "req-1", "x"));
        answer(gate.request(), Fixtures.dataResponse("req-2", DS1, List.of("EU01")));

        TestCaseResult result = NegativeCases.evaluate("T-NEG-04", List.of(platform, gate), history);

        assertThat(result.outcome()).isEqualTo(TestCaseResult.Outcome.PASSED);
        assertThat(result.note()).isEqualTo("observed: foreign platform ID: status " + FORMAT.notFoundStatus()
                + "; foreign gate ID: success status");
        assertThat(NegativeCases.evaluate("T-NEG-02", List.of(missingRequestIdProbe()), history).note()).isNull();
    }

    @Test
    void recordedErrorProbeNeedsAnErrorResponseAndNotesItsStatus() {
        Probe error = dataRequestProbe("unknown subset", "req-1", Expectation.ERROR_RECORDED, Optional.empty());
        Probe success = dataRequestProbe("unknown subset", "req-2", Expectation.ERROR_RECORDED, Optional.empty());
        Probe unanswered = dataRequestProbe("unknown subset", "req-3", Expectation.ERROR_RECORDED, Optional.empty());
        answer(error.request(), Fixtures.errorResponse("data-response-error", "req-1", "x"));
        answer(success.request(), Fixtures.dataResponse("req-2", DS1, List.of("EU01")));

        TestCaseResult passed = NegativeCases.evaluate("T-NEG-05", List.of(error), history);

        assertThat(passed.outcome()).isEqualTo(TestCaseResult.Outcome.PASSED);
        assertThat(passed.note()).isEqualTo("observed: unknown subset: status " + FORMAT.notFoundStatus());
        assertThat(NegativeCases.evaluate("T-NEG-05", List.of(success), history).findings()).extracting(Finding::detail)
                .containsExactly("unknown subset: answered with success status instead of an error.",
                        "unknown subset: the response carries a data set.");
        assertThat(NegativeCases.evaluate("T-NEG-05", List.of(unanswered), history).findings()).extracting(Finding::detail)
                .containsExactly("unknown subset: no response within the response timeout.");
    }

    @Test
    void unansweredInformativeProbeFails() {
        Probe probe = dataRequestProbe("foreign platform ID", "req-1", Expectation.ANY, Optional.of("XX"));

        assertThat(NegativeCases.evaluate("T-NEG-04", List.of(probe), history).findings()).extracting(Finding::detail)
                .containsExactly("foreign platform ID: no response within the response timeout.");
    }

    @Test
    void echoedForeignIdentifierIsNoKnownValueViolation() {
        Probe probe = probe("T-NEG-04", "foreign platform ID", Expectation.ANY, Optional.of("XX-PF"),
                render("data-request", "XX-GATE", "XX-PF"));
        CheckedMessage response = answer(probe.request(),
                Fixtures.withUilOf(probe.request(), Fixtures.errorResponse("data-response-error", "req-1", "wrong platform")));

        assertThat(response.findings()).filteredOn(finding -> finding.testCaseId().equals("T-DAT-02"))
                .extracting(finding -> NegativeCases.isExpectedReaction(finding, response.message(), List.of(probe), history))
                // the foreign platform ID was injected, the foreign gate ID was not
                .containsExactly(false, true);
    }

    @Test
    void missingRequestIdInTheResponseToAProbeWithoutRequestIdIsNoSyntaxViolation() {
        Probe uncorrelatable = missingRequestIdProbe();
        ParsedMessage response = answer(uncorrelatable.request(), NO_REQUEST_ID_RESPONSE).message();
        Probe correlatable = dataRequestProbe("unknown data set", "req-1", Expectation.ERROR, Optional.empty());
        ParsedMessage otherResponse = answer(correlatable.request(), NO_REQUEST_ID_RESPONSE).message();
        // as the schema validator reports it for a format requiring the request ID on responses
        Finding missingRequestId = new Finding("T-DAT-01", response.record().as4MessageId(),
                "Schema violation at 1:80: cvc-complex-type.4: Attribute 'requestId' must appear on the response.");
        Finding otherViolation = new Finding("T-DAT-01", response.record().as4MessageId(),
                "Schema violation at 1:80: cvc-complex-type.4: Attribute 'status' must appear on the response.");
        List<Probe> probes = List.of(uncorrelatable, correlatable);

        assertThat(NegativeCases.isExpectedReaction(missingRequestId, response, probes, history)).isTrue();
        assertThat(NegativeCases.isExpectedReaction(otherViolation, response, probes, history)).isFalse();
        assertThat(NegativeCases.isExpectedReaction(missingRequestId, otherResponse, probes, history)).isFalse();
    }
}
