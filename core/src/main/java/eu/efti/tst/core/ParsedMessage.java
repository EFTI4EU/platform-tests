package eu.efti.tst.core;

import java.util.List;
import java.util.Optional;

/**
 * Format-neutral view of a {@link MessageRecord}: the fields the conformance checks need, read with the
 * expressions of the format. A field is empty if the message does not carry it (or the format does not define it).
 *
 * @param record              the message as transmitted
 * @param kind                kind of the message, {@link MessageKind#UNKNOWN} if not well-formed or not recognised
 * @param malformation        the parser error if the payload is not well-formed XML
 * @param requestId           request ID of a request, or the request ID echoed by a response
 * @param referencedRequestId request ID of the data request a follow-up refers to
 * @param gateId              gate identifier of the UIL
 * @param platformId          platform identifier of the UIL
 * @param datasetId           data set ID (of the UIL or of an identifier upload)
 * @param status              status code of a response
 * @param success             whether the status code is one of the format's success codes
 * @param description         error description of a response
 * @param message             text of a follow-up
 * @param subsetIds           subsets requested by a data request
 * @param dataset             data set carried by a data response (XML)
 * @param identifiers         identifier consignment carried by an identifier upload (XML)
 */
public record ParsedMessage(
        MessageRecord record,
        MessageKind kind,
        Optional<String> malformation,
        Optional<String> requestId,
        Optional<String> referencedRequestId,
        Optional<String> gateId,
        Optional<String> platformId,
        Optional<String> datasetId,
        Optional<String> status,
        boolean success,
        Optional<String> description,
        Optional<String> message,
        List<String> subsetIds,
        Optional<String> dataset,
        Optional<String> identifiers) {

    public ParsedMessage {
        subsetIds = List.copyOf(subsetIds);
    }

    static ParsedMessage malformed(MessageRecord record, String error) {
        return new ParsedMessage(record, MessageKind.UNKNOWN, Optional.of(error), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), false, Optional.empty(),
                Optional.empty(), List.of(), Optional.empty(), Optional.empty());
    }
}
