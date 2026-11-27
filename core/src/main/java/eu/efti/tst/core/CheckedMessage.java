package eu.efti.tst.core;

import java.util.List;

/**
 * A message checked and appended by {@link ConformanceChecks#checkAndAppend}.
 *
 * @param message  the message as appended to the history
 * @param findings the violations found; empty for messages sent to the platform
 */
public record CheckedMessage(ParsedMessage message, List<Finding> findings) {

    public CheckedMessage {
        findings = List.copyOf(findings);
    }
}
