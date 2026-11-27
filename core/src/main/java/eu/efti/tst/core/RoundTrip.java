package eu.efti.tst.core;

import org.w3c.dom.Attr;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;

import javax.xml.XMLConstants;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Compares two data sets value by value. Both are reduced to their leaf values (text of elements without child
 * elements, attribute values) keyed by the path of local names, and the values per path are compared as multisets,
 * so the order of repeated elements does not matter. As a consequence, values swapped between two repeated
 * elements with the same structure go unnoticed.
 *
 * <p>What counts as the same value follows the XSD type of the element: numbers are compared by value, whitespace
 * only counts in free text (where it reveals padding), and canonically equivalent Unicode sequences (e.g. a
 * precomposed and a decomposed umlaut) are equal.
 */
final class RoundTrip {

    /**
     * @param differences     one line per path whose values differ, empty if the data sets match
     * @param omittedOptional paths whose values are only missing and whose element may be omitted per its status
     * @param addedAttributes paths of attributes that the actual data set only adds: they qualify a value of the
     *                        expected data set without changing it (e.g. the language of a text)
     */
    record Comparison(List<String> differences, List<String> omittedOptional, List<String> addedAttributes) {
    }

    private RoundTrip() {
    }

    private static String join(String path, String step) {
        return path.isEmpty() ? step : path + "/" + step;
    }

    private static void add(Map<String, List<String>> values, String path, String value) {
        values.computeIfAbsent(path, key -> new ArrayList<>()).add(value);
    }

    private static void collect(Element element, String path, Set<String> ignoredPaths, Map<String, List<String>> values) {
        NamedNodeMap attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Attr attribute = (Attr) attributes.item(i);
            String namespace = attribute.getNamespaceURI();
            if (!XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(namespace)
                    && !XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI.equals(namespace)) {
                add(values, join(path, "@" + attribute.getLocalName()), attribute.getValue());
            }
        }
        List<Element> children = Xml.childElements(element);
        // An element without child elements and text holds no value, e.g. an aggregate whose children were all
        // omitted or filtered out (its text is the indentation).
        if (children.isEmpty() && !path.isEmpty() && !element.getTextContent().isBlank()) {
            add(values, path, element.getTextContent());
        }
        for (Element child : children) {
            String childPath = join(path, child.getLocalName());
            if (!ignoredPaths.contains(childPath)) {
                collect(child, childPath, ignoredPaths, values);
            }
        }
    }

    /** The number in plain canonical notation; a value that is no number stays as it is, so that it shows as different. */
    private static String canonicalNumber(String value) {
        try {
            return new BigDecimal(value.strip()).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException e) {
            return value;
        }
    }

    /** The value in one notation per value, so that equal values of the type are equal strings. */
    private static String comparable(String value, SubsetSchema.ValueType type) {
        String canonical = switch (type) {
            case NUMBER -> canonicalNumber(value);
            // the whitespace characters of XSD, collapsed as its whiteSpace facet does
            case TOKEN -> value.replaceAll("[ \\t\\n\\r]+", " ").replaceAll("^ | $", "");
            case TEXT -> value;
        };
        return Normalizer.normalize(canonical, Normalizer.Form.NFC);
    }

    private static Map<String, List<String>> leafValues(Element root, Set<String> ignoredPaths, SubsetSchema schema) {
        Map<String, List<String>> values = new TreeMap<>();
        collect(root, "", ignoredPaths, values);
        values.replaceAll((path, list) -> {
            SubsetSchema.ValueType type = schema.valueType(List.of(path.split("/")));
            return list.stream().map(value -> comparable(value, type)).sorted().toList();
        });
        return values;
    }

    /** Whether the values hold each of the others, counting repeated ones. */
    private static boolean holdsAll(List<String> values, List<String> others) {
        List<String> remaining = new ArrayList<>(values);
        return others.stream().allMatch(remaining::remove);
    }

    /** Values are only missing, not changed or added, and the element may be omitted per its status. */
    private static boolean isTolerableOmission(String path, List<String> expected, List<String> actual,
                                               SubsetSchema schema, Set<String> subsets) {
        List<String> elementPath = Arrays.stream(path.split("/")).filter(step -> !step.startsWith("@")).toList();
        return holdsAll(expected, actual) && schema.optionalInAll(elementPath, subsets);
    }

    /** Attribute values are only added, not changed or missing. */
    private static boolean isAddedAttribute(String path, List<String> expected, List<String> actual) {
        return path.contains("@") && holdsAll(actual, expected);
    }

    /**
     * @param ignoredPaths paths of elements (local names below the root, separated by '/') that are not compared
     */
    static Comparison compare(Element expected, Element actual, SubsetSchema schema, Set<String> subsets,
                              Set<String> ignoredPaths) {
        Map<String, List<String>> expectedValues = leafValues(expected, ignoredPaths, schema);
        Map<String, List<String>> actualValues = leafValues(actual, ignoredPaths, schema);
        Set<String> paths = new TreeSet<>(expectedValues.keySet());
        paths.addAll(actualValues.keySet());
        List<String> differences = new ArrayList<>();
        List<String> omittedOptional = new ArrayList<>();
        List<String> addedAttributes = new ArrayList<>();
        for (String path : paths) {
            List<String> expectedAtPath = expectedValues.getOrDefault(path, List.of());
            List<String> actualAtPath = actualValues.getOrDefault(path, List.of());
            if (expectedAtPath.equals(actualAtPath)) {
                continue;
            }
            if (isTolerableOmission(path, expectedAtPath, actualAtPath, schema, subsets)) {
                omittedOptional.add(path);
            } else if (isAddedAttribute(path, expectedAtPath, actualAtPath)) {
                addedAttributes.add(path);
            } else {
                differences.add(path + ": expected " + expectedAtPath + " but found " + actualAtPath);
            }
        }
        return new Comparison(differences, omittedOptional, addedAttributes);
    }
}
