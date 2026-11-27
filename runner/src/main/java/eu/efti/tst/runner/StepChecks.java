package eu.efti.tst.runner;

import eu.efti.tst.core.ConformanceContext;
import eu.efti.tst.core.Finding;
import eu.efti.tst.core.Format;
import eu.efti.tst.core.IdentifierSet;
import eu.efti.tst.core.IdentifierType;
import eu.efti.tst.core.MessageKind;
import eu.efti.tst.core.MessageRecord;
import eu.efti.tst.core.ParsedMessage;
import eu.efti.tst.core.TestCaseResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Pure evaluation of the scenario steps and negative cases from the messages observed. */
final class StepChecks {

    /** What the gate expects as answer to one of its requests. */
    enum Expectation {
        SUCCESS_WITH_DATA,
        SUCCESS,
        /** An error response without data. */
        ERROR,
        ERROR_WITH_DESCRIPTION,
        /** An error response without data, whose status is recorded, as the specific error code is optional. */
        ERROR_RECORDED,
        /** No response, or an error response without data (for requests the platform cannot correlate). */
        NO_SUCCESS,
        /** Any response; only recorded (informative test cases). */
        ANY
    }

    /**
     * Identifier values a platform may set itself at the carrier signature instead of taking them from the test data,
     * so the lifecycle steps only require a value for them (T-DAT-05 still compares them with the returned data set).
     */
    static final Set<IdentifierType> SET_AT_CARRIER_SIGNATURE = Set.of(IdentifierType.CARRIER_ACCEPTANCE_DATE);

    /** Identifier values the platform sets at the consignee signature, so T-RUN-06 only requires a value for them. */
    static final Set<IdentifierType> SET_AT_CONSIGNEE_SIGNATURE = Set.of(IdentifierType.ACTUAL_DELIVERY_DATE);

    private StepChecks() {
    }

    static Finding finding(String testCaseId, MessageRecord record, String detail) {
        return new Finding(testCaseId, record == null ? null : record.as4MessageId(), detail);
    }

    static TestCaseResult result(String testCaseId, String datasetKey, List<Finding> failures, String note) {
        return new TestCaseResult(testCaseId, datasetKey,
                failures.isEmpty() ? TestCaseResult.Outcome.PASSED : TestCaseResult.Outcome.FAILED, note, failures);
    }

    /** The validation the gate applies before accepting an identifier upload: message schema and rules. */
    static List<String> gateValidation(Format format, String payload) {
        List<String> errors = new ArrayList<>(format.validateSchema(payload));
        try {
            format.validateRules(payload).forEach(violation -> errors.add(violation.message()));
        } catch (IllegalArgumentException e) {
            errors.add(e.getMessage());
        }
        return errors;
    }

    private static String status(ParsedMessage response) {
        return response.success() ? "success status" : "status " + response.status().orElse("(none)");
    }

    private static List<String> violations(Expectation expectation, ParsedMessage response, Optional<String> injectedIdentifier) {
        List<String> violations = new ArrayList<>();
        boolean withData = response.dataset().isPresent();
        switch (expectation) {
            case SUCCESS_WITH_DATA -> {
                if (!response.success()) {
                    violations.add("answered with " + status(response) + " instead of success");
                } else if (!withData) {
                    violations.add("the success response carries no data set");
                }
            }
            case SUCCESS -> {
                if (!response.success()) {
                    violations.add("answered with " + status(response) + " instead of success");
                }
            }
            case ERROR, ERROR_WITH_DESCRIPTION, ERROR_RECORDED, NO_SUCCESS -> {
                if (response.success()) {
                    violations.add("answered with success status instead of an error");
                }
                if (withData) {
                    violations.add("the response carries a data set");
                }
                if (expectation == Expectation.ERROR_WITH_DESCRIPTION && !response.success() && response.description().isEmpty()) {
                    violations.add("the error response carries no error description");
                }
            }
            case ANY -> {
            }
        }
        injectedIdentifier
                .filter(injected -> response.success()
                        && (response.gateId().equals(Optional.of(injected)) || response.platformId().equals(Optional.of(injected))))
                .ifPresent(injected -> violations.add("the success response carries the injected identifier " + injected));
        return violations;
    }

    /**
     * Checks the responses to a request against the expectation.
     *
     * @param label              names the request in the findings
     * @param responses          the responses received (usually zero or one)
     * @param injectedIdentifier foreign gate or platform identifier the request carried, which a success response
     *                           must not echo
     */
    static List<Finding> verify(String testCaseId, String label, Expectation expectation, ParsedMessage request,
                                List<ParsedMessage> responses, Optional<String> injectedIdentifier) {
        if (responses.isEmpty()) {
            return expectation == Expectation.NO_SUCCESS ? List.of()
                    : List.of(finding(testCaseId, request.record(), label + ": no response within the response timeout."));
        }
        return responses.stream()
                .flatMap(response -> violations(expectation, response, injectedIdentifier).stream()
                        .map(violation -> finding(testCaseId, response.record(), label + ": " + violation + ".")))
                .toList();
    }

    /** The observed reaction to a request, for the notes of informative test cases. */
    static String observed(String label, List<ParsedMessage> responses) {
        return label + ": " + (responses.isEmpty() ? "no response"
                : String.join(", ", responses.stream().map(StepChecks::status).toList()));
    }

    /**
     * @param setByPlatform identifier types that only need a value, whatever it is, besides {@link #SET_AT_CARRIER_SIGNATURE}
     */
    private static List<String> deviations(IdentifierSet expected, IdentifierSet actual, Set<IdentifierType> setByPlatform) {
        return Arrays.stream(IdentifierType.values())
                .<String>mapMulti((type, found) -> {
                    List<String> values = actual.get(type);
                    if (SET_AT_CARRIER_SIGNATURE.contains(type) || setByPlatform.contains(type)) {
                        if (values.isEmpty()) {
                            found.accept(type + ": no value");
                        }
                    } else if (!expected.get(type).equals(values)) {
                        found.accept(type + ": expected " + expected.get(type) + " but found " + values);
                    }
                })
                .toList();
    }

    private static boolean isUploadFromPlatform(ParsedMessage message) {
        return message.kind() == MessageKind.IDENTIFIER_UPLOAD && message.record().direction() == MessageRecord.Direction.FROM_PLATFORM;
    }

    /** The platform's identifier uploads for the data set, in order. */
    static List<ParsedMessage> uploadsFor(String datasetId, List<ParsedMessage> messages) {
        return messages.stream()
                .filter(StepChecks::isUploadFromPlatform)
                // UUIDs are case-insensitive, so the operator may have entered the ID in another case
                .filter(message -> message.datasetId().filter(datasetId::equalsIgnoreCase).isPresent())
                .toList();
    }

    private static List<ParsedMessage> after(Optional<ParsedMessage> previous, List<ParsedMessage> uploads) {
        return uploads.subList(previous.map(uploads::indexOf).orElse(-1) + 1, uploads.size());
    }

    /** The latest upload after the previous step's upload, evaluated if no {@link #stepUpload} came. */
    static Optional<ParsedMessage> latestUpload(List<ParsedMessage> uploads, Optional<ParsedMessage> previous) {
        List<ParsedMessage> after = after(previous, uploads);
        return after.isEmpty() ? Optional.empty() : Optional.of(after.getLast());
    }

    /** T-RUN-01: the draft must not be registered at the gate, so no upload may come before the carrier signature. */
    static List<Finding> draftRegistrations(String testCaseId, List<ParsedMessage> uploads) {
        return uploads.stream()
                .map(upload -> finding(testCaseId, upload.record(), "The platform sent an identifier upload for the data set "
                        + "before the runner asked for the carrier signature: the draft must not be registered at the gate "
                        + "(unless the operator applied the carrier signature too early)."))
                .toList();
    }

    /** The finding if no identifier upload came, pointing out uploads for other data sets (e.g. a mistyped ID). */
    static Finding noUpload(String testCaseId, String datasetId, List<ParsedMessage> messages) {
        List<String> others = messages.stream()
                .filter(StepChecks::isUploadFromPlatform)
                .flatMap(message -> message.datasetId().stream())
                .filter(id -> !id.equalsIgnoreCase(datasetId))
                .distinct()
                .toList();
        return finding(testCaseId, null, "No identifier upload for the data set " + datasetId + " within the response timeout after the operator step."
                + (others.isEmpty() ? "" : " The platform uploaded identifiers for other data set IDs (" + String.join(", ", others)
                + "): was the data set ID entered correctly?"));
    }

    /** A note if the UIL composition of an identifier upload cannot be checked, as the format does not carry it. */
    static Optional<String> uncheckedUil(ParsedMessage upload, Format format) {
        return upload.gateId().isEmpty() && upload.platformId().isEmpty()
                ? Optional.of("the gate and platform identifiers of the UIL are not checked: identifier uploads of message format "
                + format.version() + " do not carry them")
                : Optional.empty();
    }

    /**
     * T-RUN-04 (b): the elements of the data set a response returns that are outside the subsets of the request. A data
     * set that cannot be evaluated is a finding as well.
     */
    static List<Finding> outsideSubsets(String testCaseId, String label, ParsedMessage request, Optional<ParsedMessage> response,
                                        Format format) {
        return response.flatMap(message -> message.dataset().map(dataset -> {
            try {
                return format.elementsOutsideSubsets(dataset, request.subsetIds()).stream()
                        .map(path -> finding(testCaseId, message.record(), label + ": " + path + " is not part of the requested subsets."))
                        .toList();
            } catch (RuntimeException e) {
                return List.of(finding(testCaseId, message.record(), label + ": the returned data set cannot be evaluated: "
                        + e.getMessage()));
            }
        })).orElse(List.of());
    }

    /**
     * T-RUN-05: the acknowledgement of a follow-up echoes the request ID of the data request the follow-up refers to
     * (FTI030), not just any value accepted for the correlation (T-ALL-03).
     */
    static List<Finding> echoesDataRequest(String testCaseId, String label, String dataRequestId, Optional<ParsedMessage> ack) {
        return ack.filter(response -> !response.requestId().equals(Optional.of(dataRequestId)))
                .map(response -> List.of(finding(testCaseId, response.record(), label + ": the acknowledgement echoes "
                        + response.requestId().map(id -> "request ID " + id).orElse("no request ID") + " instead of "
                        + dataRequestId + ", the request ID of the data request the follow-up refers to.")))
                .orElse(List.of());
    }

    /**
     * Checks an identifier upload of the lifecycle: it passes the gate's validation, the UIL carries the gate's and
     * the platform's identifiers (if the format exposes them) and it carries exactly the expected identifier values,
     * except for {@link #SET_AT_CARRIER_SIGNATURE}, which only need a value.
     *
     * @param setByPlatform further identifier types that only need a value (e.g. {@link #SET_AT_CONSIGNEE_SIGNATURE})
     */
    static List<Finding> registration(String testCaseId, ParsedMessage message, Format format,
                                      ConformanceContext context, IdentifierSet expected, Set<IdentifierType> setByPlatform) {
        List<String> problems = new ArrayList<>(gateValidation(format, message.record().payload()).stream()
                .map(error -> "Rejected by the gate's validation: " + error)
                .toList());
        message.gateId().filter(id -> !id.equals(context.gateId()))
                .ifPresent(id -> problems.add("The UIL carries the gate identifier " + id + " instead of " + context.gateId() + "."));
        message.platformId().filter(id -> !id.equals(context.platformId()))
                .ifPresent(id -> problems.add("The UIL carries the platform identifier " + id + " instead of " + context.platformId() + "."));
        if (message.identifiers().isEmpty()) {
            problems.add("The upload carries no identifier set.");
        } else {
            try {
                deviations(expected, format.identifiers(message.identifiers().get()), setByPlatform)
                        .forEach(deviation -> problems.add("Identifier " + deviation + "."));
            } catch (IllegalArgumentException e) {
                problems.add("The identifier set cannot be read: " + e.getMessage());
            }
        }
        return problems.stream().map(problem -> finding(testCaseId, message.record(), problem)).toList();
    }

    /**
     * The identifier upload of a lifecycle step: the first upload after the previous step's upload that passes the
     * step's {@link #registration} checks, as the operator may trigger uploads with other values before (e.g. an
     * update without the change, or with a typo they then correct).
     *
     * @param uploads  the platform's uploads for the data set, in order (see {@link #uploadsFor})
     * @param previous the upload of the previous step, empty for the first step with an upload
     */
    static Optional<ParsedMessage> stepUpload(List<ParsedMessage> uploads, Optional<ParsedMessage> previous, Format format,
                                              ConformanceContext context, IdentifierSet expected, Set<IdentifierType> setByPlatform) {
        return after(previous, uploads).stream()
                .filter(upload -> registration("", upload, format, context, expected, setByPlatform).isEmpty())
                .findFirst();
    }

    /**
     * The uploads that came for a step before the one it was evaluated on (see {@link #stepUpload}), which therefore did
     * not count for it (parameters as for {@link #stepUpload}).
     *
     * @param evaluated the upload the step was evaluated on, empty if none came
     */
    static List<ParsedMessage> skippedUploads(List<ParsedMessage> uploads, Optional<ParsedMessage> previous,
                                              Optional<ParsedMessage> evaluated) {
        List<ParsedMessage> after = after(previous, uploads);
        return evaluated.map(upload -> after.subList(0, after.indexOf(upload))).orElse(List.of());
    }

    /** A note naming the uploads a step skipped, as its result would otherwise not show them. */
    static Optional<String> skippedUploadsNote(List<ParsedMessage> skipped) {
        return skipped.isEmpty() ? Optional.empty()
                : Optional.of(skipped.size() + " earlier identifier upload(s) did not count for this step, as they did not pass "
                + "its checks (AS4 message " + String.join(", ", skipped.stream().map(upload -> upload.record().as4MessageId()).toList())
                + ")");
    }

    /**
     * Checks that a data request was answered with success and a data set.
     *
     * @param expected identifier values the returned data set must reflect, compared on the identifiers and fields it
     *                 contains except {@link #SET_AT_CARRIER_SIGNATURE}, as the requested subsets need not contain all
     *                 of them and optional elements may be omitted; empty to skip this comparison
     */
    static List<Finding> dataReturned(String testCaseId, String label, ParsedMessage request, Optional<ParsedMessage> response,
                                      Format format, Optional<IdentifierSet> expected) {
        List<Finding> failures = verify(testCaseId, label, Expectation.SUCCESS_WITH_DATA, request,
                response.stream().toList(), Optional.empty());
        if (!failures.isEmpty() || expected.isEmpty()) {
            return failures;
        }
        MessageRecord record = response.orElseThrow().record();
        try {
            IdentifierSet returned = format.identifiers(response.get().dataset().orElseThrow());
            return Arrays.stream(IdentifierType.values())
                    .filter(type -> !returned.get(type).isEmpty())
                    .filter(type -> !SET_AT_CARRIER_SIGNATURE.contains(type))
                    .filter(type -> !IdentifierSet.agreeOnReturnedFields(expected.get().get(type), returned.get(type)))
                    .map(type -> finding(testCaseId, record, label + ": " + type + " expected " + expected.get().get(type)
                            + " but the data set contains " + returned.get(type) + "."))
                    .toList();
        } catch (IllegalArgumentException e) {
            return List.of(finding(testCaseId, record, label + ": the identifiers of the data set cannot be read: " + e.getMessage()));
        }
    }

    /**
     * A note if the returned data set lacks the corrected identifier of the transport means, so that the correction
     * could not be compared. Only for a response {@link #dataReturned} accepted, whose identifiers are readable.
     */
    static Optional<String> correctionNotReturned(String label, Optional<ParsedMessage> response, Format format, String corrected) {
        return response.flatMap(ParsedMessage::dataset)
                .filter(dataset -> format.identifiers(dataset).get(IdentifierType.TRANSPORT_MEANS).stream()
                        .noneMatch(value -> value.contains(corrected)))
                .map(dataset -> label + ": the data set does not contain the identifier of the transport means, so its "
                        + "corrected value is not compared");
    }
}
