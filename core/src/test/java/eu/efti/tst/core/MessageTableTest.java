package eu.efti.tst.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static eu.efti.tst.core.Fixtures.FORMAT;
import static eu.efti.tst.core.Fixtures.T0;
import static org.assertj.core.api.Assertions.assertThat;

class MessageTableTest {

    private static String row(String html, String firstCell) {
        int start = html.indexOf("<tr class=\"msg\"><td class=\"mono\">" + firstCell);
        return html.substring(start, html.indexOf("</tr>", start));
    }

    @Test
    void showsTypeTestCaseAndStatusPerMessageAndTheRestInTheDetails() {
        MessageRecord request = Fixtures.toPlatform(Fixtures.followUp("fu-1", "req-1"), T0,
                MessageRecord.conversationIdFor("T-RUN-05"));
        MessageRecord response = Fixtures.answer(request, Fixtures.errorResponse("follow-up-ack-error", "fu-1"),
                T0.plusSeconds(1));

        String html = MessageTable.render(List.of(FORMAT.parse(request), FORMAT.parse(response)));

        assertThat(row(html, "2026-09-15T07:00:00.000Z")).isEqualTo("<tr class=\"msg\"><td class=\"mono\">2026-09-15T07:00:00.000Z"
                + "</td><td>Gate &rarr; Platform</td><td class=\"mono\">T-RUN-05</td><td class=\"mono\">FOLLOW_UP</td><td></td>");
        assertThat(row(html, "2026-09-15T07:00:01.000Z")).contains("Platform &rarr; Gate", "T-RUN-05", "FOLLOW_UP_ACK",
                "<td>404 Unknown data set</td>");
        assertThat(html).contains("<dt>Request ID</dt><dd>fu-1</dd>", "<dt>Data set ID</dt><dd>" + Fixtures.DATASET_ID,
                "<dt>Follow-up text</dt><dd>Please check the plate</dd>", "<dt>Refers to</dt><dd>" + request.as4MessageId(),
                "<dt>Conversation ID</dt><dd>" + request.conversationId(), "&lt;followUp");
    }

    @Test
    void malformedMessageShowsTheParserErrorAsStatusAndEscapesThePayload() {
        MessageRecord record = Fixtures.fromPlatform("<broken & <b>", T0, null);

        String html = MessageTable.render(List.of(FORMAT.parse(record)));

        assertThat(row(html, "2026-09-15T07:00:00.000Z")).contains("<td class=\"mono\"></td><td class=\"mono\">UNKNOWN</td><td>")
                .doesNotContain("UNKNOWN</td><td></td>");
        assertThat(html).contains("<pre>&lt;broken &amp; &lt;b&gt;</pre>").doesNotContain("<b>", "Conversation ID");
    }

    private static List<String> ids(List<ParsedMessage> messages) {
        return messages.stream().map(message -> message.record().as4MessageId()).toList();
    }

    private static ParsedMessage message(String id, String refToMessageId, int second) {
        return FORMAT.parse(new MessageRecord(id, T0.plusSeconds(second), MessageRecord.Direction.TO_PLATFORM, "gate",
                "platform", null, refToMessageId, "<x/>"));
    }

    @Test
    void responsesFollowTheMessageTheyReferTo() {
        List<ParsedMessage> chronological = List.of(message("a", null, 0), message("b", null, 1),
                message("upload", null, 2), message("b-response", "b", 3), message("orphan", "unknown", 4),
                message("a-response", "a", 5), message("upload-status", "upload", 6), message("a-late", "a", 7));

        assertThat(ids(MessageTable.byExchange(chronological))).containsExactly("a", "a-response", "a-late", "b",
                "b-response", "upload", "upload-status", "orphan");
        // newest first: the exchanges by their latest message, each still read from the referred message on
        assertThat(ids(MessageTable.byExchange(chronological.reversed()))).containsExactly("a", "a-response", "a-late",
                "upload", "upload-status", "orphan", "b", "b-response");
    }

    @Test
    void messagesReferringToThemselvesOrWithEqualTimesStayInPlace() {
        ParsedMessage request = message("a", null, 0);
        ParsedMessage self = message("self", "self", 0);
        ParsedMessage response = message("a-response", "a", 0);

        assertThat(ids(MessageTable.byExchange(List.of(response, self, request)))).containsExactly("a", "a-response", "self");
    }

    @Test
    void timesHaveAFixedWidthWhateverTheirPrecision() {
        assertThat(MessageTable.TIME.format(T0.plusNanos(942216613))).isEqualTo("2026-09-15T07:00:00.942Z");
        assertThat(MessageTable.TIME.format(T0.plusNanos(576350000))).isEqualTo("2026-09-15T07:00:00.576Z");
    }

    @Test
    void noMessagesGiveANoteInsteadOfAnEmptyTable() {
        assertThat(MessageTable.render(List.of())).doesNotContain("<table").contains("No messages.");
    }
}
