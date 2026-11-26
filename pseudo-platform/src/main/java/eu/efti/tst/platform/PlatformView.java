package eu.efti.tst.platform;

import eu.efti.tst.core.MessageTable;
import eu.efti.tst.core.ParsedMessage;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Snapshot of the platform state for the page.
 *
 * @param dataSets        data sets, newest first
 * @param messages        all exchanged messages, newest first
 * @param toggles         active fault toggles
 * @param connectionError error of the last poll of Domibus, empty if it succeeded
 * @param problems        failures handling received messages (the latest per message), newest first
 */
public record PlatformView(
        List<DataSet> dataSets,
        List<ParsedMessage> messages,
        FaultToggles toggles,
        Optional<String> connectionError,
        List<String> problems) {

    public PlatformView {
        dataSets = List.copyOf(dataSets);
        messages = List.copyOf(messages);
        problems = List.copyOf(problems);
    }

    /**
     * The messages of a data set: those carrying its ID, those sharing a request ID with them (responses carry no
     * data set ID), and those referring to any of these by AS4 RefToMessageId (responses without request ID).
     */
    public List<ParsedMessage> messagesOf(String datasetId) {
        Optional<String> id = Optional.of(datasetId);
        Set<String> requestIds = messages.stream()
                .filter(message -> message.datasetId().equals(id))
                .flatMap(message -> message.requestId().stream())
                .collect(Collectors.toSet());
        Predicate<ParsedMessage> direct = message -> message.datasetId().equals(id)
                || message.requestId().filter(requestIds::contains).isPresent();
        Set<String> as4MessageIds = messages.stream().filter(direct)
                .map(message -> message.record().as4MessageId())
                .collect(Collectors.toSet());
        return messages.stream()
                .filter(direct.or(message -> as4MessageIds.contains(message.record().refToMessageId())))
                .toList();
    }

    /** The messages that belong to none of the data sets, e.g. requests for unknown data sets or malformed ones. */
    public List<ParsedMessage> otherMessages() {
        Set<String> ofDataSets = dataSets.stream()
                .flatMap(dataSet -> messagesOf(dataSet.id()).stream())
                .map(message -> message.record().as4MessageId())
                .collect(Collectors.toSet());
        return messages.stream().filter(message -> !ofDataSets.contains(message.record().as4MessageId())).toList();
    }

    /** The messages as the same table as in the test report (HTML). */
    public String messageTable(List<ParsedMessage> messages) {
        return MessageTable.render(messages);
    }
}
