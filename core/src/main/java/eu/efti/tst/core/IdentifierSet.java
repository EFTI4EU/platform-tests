package eu.efti.tst.core;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Identifier values of one consignment, read either from an identifier upload or from a data set, so that both
 * can be compared. Every {@link IdentifierType} is present as a key; its values are sorted and empty if the
 * consignment holds no value for it. A value that consists of several fields (e.g. an ID and its registration
 * country) joins all of them with {@value #FIELD_SEPARATOR}, an absent field as empty string.
 */
public record IdentifierSet(Map<IdentifierType, List<String>> values) {

    private static final String FIELD_SEPARATOR = "|";

    public IdentifierSet {
        values = Map.copyOf(values);
    }

    private static List<String> fields(String value) {
        return List.of(value.split(Pattern.quote(FIELD_SEPARATOR), -1));
    }

    private static List<String> restrictedTo(List<String> values, Set<Integer> positions) {
        return values.stream()
                .map(value -> {
                    List<String> fields = fields(value);
                    return IntStream.range(0, fields.size()).filter(positions::contains).mapToObj(fields::get)
                            .collect(Collectors.joining(FIELD_SEPARATOR));
                })
                .sorted()
                .toList();
    }

    /**
     * Whether the registered values equal the returned ones on the fields the returned values carry. Fields that are
     * empty in all returned values are not compared, since a data set restricted to subsets may lack them (e.g. the
     * sequence number of a transport equipment).
     */
    public static boolean agreeOnReturnedFields(List<String> registered, List<String> returned) {
        Set<Integer> carried = returned.stream()
                .map(IdentifierSet::fields)
                .flatMap(fields -> IntStream.range(0, fields.size()).filter(i -> !fields.get(i).isEmpty()).boxed())
                .collect(Collectors.toSet());
        return restrictedTo(registered, carried).equals(restrictedTo(returned, carried));
    }

    /** Whether a value of the type holds at least one non-empty field. */
    boolean holdsValue(IdentifierType type) {
        return get(type).stream().anyMatch(value -> fields(value).stream().anyMatch(field -> !field.isEmpty()));
    }

    public List<String> get(IdentifierType type) {
        return values.getOrDefault(type, List.of());
    }
}
