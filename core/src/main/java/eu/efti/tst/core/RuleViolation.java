package eu.efti.tst.core;

/**
 * A failed assertion of the format's Schematron rules.
 *
 * @param testCaseId ID of the test case the rule belongs to
 * @param message    assertion text
 * @param location   XPath of the node the assertion failed on
 */
public record RuleViolation(String testCaseId, String message, String location) {
}
