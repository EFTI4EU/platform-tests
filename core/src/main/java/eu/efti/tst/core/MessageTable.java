package eu.efti.tst.core;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The exchanged messages as an HTML table, shared by the message transcript of the test report and the message log
 * of the pseudo-platform: one row per message with what tells what happened (time, direction, test case, message
 * type, status) and an expandable row with the identifiers, the follow-up text and the payload. Each response is shown
 * right below the message it refers to by its AS4 RefToMessageId, so that an exchange reads as one block even if
 * several requests were sent before their responses arrived.
 */
public final class MessageTable {

    /** Style of the table; self-contained, so that any page can include it. */
    public static final String CSS = Resources.read("message-table.css");

    /** Fixed width, as the precision of the recorded times depends on the clock of the machine. */
    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private MessageTable() {
    }

    private static String esc(String text) {
        return text == null ? "" : Xml.escape(text);
    }

    private static String direction(MessageRecord record) {
        return record.direction() == MessageRecord.Direction.TO_PLATFORM ? "Gate &rarr; Platform" : "Platform &rarr; Gate";
    }

    /** The parser error of a malformed message, otherwise the status and its description (empty for requests). */
    private static String status(ParsedMessage message) {
        return message.malformation()
                .orElseGet(() -> Stream.of(message.status(), message.description()).flatMap(Optional::stream)
                        .collect(Collectors.joining(" ")));
    }

    private static Optional<String> detail(String label, Optional<String> value) {
        return value.map(text -> "<dt>" + label + "</dt><dd>" + esc(text) + "</dd>");
    }

    private static String details(ParsedMessage message) {
        MessageRecord record = message.record();
        String lines = Stream.of(
                        detail("Request ID", message.requestId()),
                        detail("Data set ID", message.datasetId()),
                        detail("Follow-up text", message.message()),
                        detail("AS4 message ID", Optional.of(record.as4MessageId())),
                        detail("Parties", Optional.of(record.fromParty() + " -> " + record.toParty())),
                        detail("Conversation ID", Optional.ofNullable(record.conversationId())),
                        detail("Refers to", Optional.ofNullable(record.refToMessageId())))
                .flatMap(Optional::stream)
                .collect(Collectors.joining());
        return "<details><summary>details</summary><dl>" + lines + "</dl><pre>" + esc(record.payload()) + "</pre></details>";
    }

    private static String rows(ParsedMessage message) {
        MessageRecord record = message.record();
        return "<tr class=\"msg\"><td class=\"mono\">" + TIME.format(record.timestamp()) + "</td><td>"
                + direction(record) + "</td><td class=\"mono\">" + esc(record.testCaseId().orElse(""))
                + "</td><td class=\"mono\">" + message.kind() + "</td><td>" + esc(status(message)) + "</td></tr>\n"
                + "<tr><td colspan=\"5\">" + details(message) + "</td></tr>\n";
    }

    /**
     * Groups each message with the message it refers to (a response without a RefToMessageId, or with one of a
     * message not in the list, stays on its own). The exchanges keep the order in which they first appear in the list;
     * within an exchange, the referred message comes first, then the others by time.
     */
    static List<ParsedMessage> byExchange(List<ParsedMessage> messages) {
        Map<String, ParsedMessage> byId = messages.stream().collect(Collectors.toMap(
                message -> message.record().as4MessageId(), Function.identity(), (first, second) -> first));
        Function<ParsedMessage, String> exchange = message -> Optional.ofNullable(message.record().refToMessageId())
                .filter(byId::containsKey).orElse(message.record().as4MessageId());
        return messages.stream()
                .collect(Collectors.groupingBy(exchange, LinkedHashMap::new, Collectors.toList()))
                .entrySet().stream()
                .flatMap(group -> group.getValue().stream().sorted(Comparator
                        .comparing((ParsedMessage message) -> !message.record().as4MessageId().equals(group.getKey()))
                        .thenComparing(message -> message.record().timestamp())))
                .toList();
    }

    /**
     * The messages grouped by exchange (see {@link #byExchange}), the exchanges in the given order; a note instead
     * of the table if there are none.
     */
    public static String render(List<ParsedMessage> messages) {
        if (messages.isEmpty()) {
            return "<p class=\"messages-empty\">No messages.</p>\n";
        }
        return "<table class=\"messages\">\n<tr><th>Time (UTC)</th><th>Direction</th><th>Test case</th>"
                + "<th>Message type</th><th>Status</th></tr>\n"
                + byExchange(messages).stream().map(MessageTable::rows).collect(Collectors.joining()) + "</table>\n";
    }
}
