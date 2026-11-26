package eu.efti.tst.runner;

import eu.efti.tst.core.Finding;
import eu.efti.tst.core.Format;
import eu.efti.tst.core.MessageHistory;
import eu.efti.tst.core.ParsedMessage;
import eu.efti.tst.core.TestCaseIds;
import eu.efti.tst.core.TestCaseResult;
import eu.efti.tst.runner.StepChecks.Expectation;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Pure evaluation of the fault-injected requests of the negative cases, and of which monitoring findings are the
 * expected reaction to them rather than a violation.
 */
final class NegativeCases {

    /**
     * A fault-injected request.
     *
     * @param label              names the request in findings and notes
     * @param injectedIdentifier foreign gate or platform identifier the request carries, if any
     */
    record Probe(String testCaseId, String label, Expectation expectation, Optional<String> injectedIdentifier,
                 ParsedMessage request) {

        /** The request lacks every value a response could echo as request ID, so no correlatable response can be built. */
        boolean uncorrelatable() {
            return expectation == Expectation.NO_SUCCESS;
        }
    }

    private static final String MARKER = "tst-request-id-marker";

    private NegativeCases() {
    }

    /**
     * What the gate expects as answer to a malformed request (T-NEG-02, T-NEG-06): an error response if the request
     * carries a value the response can echo as request ID; otherwise the platform cannot build a correlatable response,
     * so no response is accepted as well. Never a success.
     */
    static Expectation forMalformed(ParsedMessage request, Format format) {
        return format.acceptedEchoes(request).isEmpty() ? Expectation.NO_SUCCESS : Expectation.ERROR;
    }

    /** All responses to the probe, so that a second one (e.g. a success after an error) is evaluated as well. */
    private static List<ParsedMessage> responsesTo(Probe probe, MessageHistory history) {
        return history.allResponsesTo(probe.request());
    }

    /**
     * The result of a negative case from its probes. Probes whose reaction is recorded (expectation ANY or
     * ERROR_RECORDED) add the observed behaviour as note.
     */
    static TestCaseResult evaluate(String testCaseId, List<Probe> probes, MessageHistory history) {
        List<Finding> failures = probes.stream()
                .flatMap(probe -> StepChecks.verify(testCaseId, probe.label(), probe.expectation(), probe.request(),
                        responsesTo(probe, history), probe.injectedIdentifier()).stream())
                .toList();
        String note = probes.stream()
                .filter(probe -> probe.expectation() == Expectation.ANY || probe.expectation() == Expectation.ERROR_RECORDED)
                .map(probe -> StepChecks.observed(probe.label(), responsesTo(probe, history)))
                .collect(Collectors.collectingAndThen(Collectors.joining("; "), joined -> joined.isEmpty() ? null : "observed: " + joined));
        return StepChecks.result(testCaseId, null, failures, note);
    }

    /**
     * Name of the attribute or element carrying the request ID in a response of the format, read from the format's
     * error response template, since the Java code does not name XML elements.
     */
    private static Optional<String> requestIdName(Format format) {
        String response = format.render("data-response-error", Map.of("requestId", MARKER, "status", "", "description", ""));
        Matcher matcher = Pattern.compile("([\\w.:-]+)\\s*=\\s*[\"']" + MARKER + "|<([\\w.:-]+)[^>]*>\\s*" + MARKER).matcher(response);
        return matcher.find()
                ? Stream.of(matcher.group(1), matcher.group(2)).filter(Objects::nonNull)
                .map(name -> name.substring(name.indexOf(':') + 1)).findFirst()
                : Optional.empty();
    }

    /**
     * Whether a finding on a message received from the platform is the expected reaction to a probe and therefore no
     * violation of its test case; the probe's own negative case evaluates that reaction instead:
     * <ul>
     *   <li>T-DAT-01 on a response without request ID to a probe without request ID, if the finding is about the
     *       missing request ID: the response cannot echo one, even if the format requires it.</li>
     *   <li>T-DAT-02 on a response to a probe with a foreign identifier, if the finding is about that identifier:
     *       echoing it in an error response is acceptable.</li>
     * </ul>
     */
    static boolean isExpectedReaction(Finding finding, ParsedMessage message, List<Probe> probes, MessageHistory history) {
        return probes.stream()
                .filter(probe -> responsesTo(probe, history).contains(message))
                .anyMatch(probe -> switch (finding.testCaseId()) {
                    case TestCaseIds.DAT_01 -> probe.uncorrelatable() && message.requestId().isEmpty()
                            && requestIdName(history.format()).filter(finding.detail()::contains).isPresent();
                    case TestCaseIds.DAT_02 -> probe.injectedIdentifier().filter(finding.detail()::contains).isPresent();
                    default -> false;
                });
    }
}
