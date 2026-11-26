package eu.efti.tst.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;
import java.util.List;

import static eu.efti.tst.core.Fixtures.DATASET;
import static eu.efti.tst.core.Fixtures.FORMAT;
import static org.assertj.core.api.Assertions.assertThat;

class IdentifierSetTest {

    /** CsvSource passes an empty column as null. */
    private static List<String> values(String commaSeparated) {
        return commaSeparated == null ? List.of() : Arrays.asList(commaSeparated.split(","));
    }

    @ParameterizedTest(name = "registered [{0}], returned [{1}]: {2}")
    @CsvSource(delimiter = ';', value = {
            "A|PL|TE|1; A|PL|TE|1; true",
            // fields empty in all returned values are not compared
            "A|PL|TE|1; A|PL|TE|; true",
            "A|PL|TE|1; |PL||; true",
            "A|PL|TE|1; A|CZ|TE|; false",
            // order of repeated values does not matter, their number does
            "A|PL,B|SE; B|SE,A|PL; true",
            "A|PL; A|PL,A|PL; false",
            "A|PL,B|SE; A|PL; false",
            // a value the registry does not hold
            "; A|PL; false",
            "; ; true"})
    void agreeOnReturnedFieldsComparesOnlyTheFieldsTheResponseCarries(String registered, String returned, boolean agree) {
        assertThat(IdentifierSet.agreeOnReturnedFields(values(registered), values(returned))).isEqualTo(agree);
    }

    @Test
    void identifiersReadsAllValuesSortedWithAllFields() {
        String identifiers = """
                <shipment xmlns="urn:tst:mini:identifiers">
                  <acceptedAt>202401010000</acceptedAt>
                  <vehicle><plate>ABC123</plate></vehicle>
                  <equipment><id>EQ2</id><country>SE</country><category>TE</category><seq>2</seq>
                    <carried><id>C2</id><seq>2</seq></carried></equipment>
                  <equipment><id>EQ1</id><country>FI</country><category>AE</category><seq>1</seq>
                    <carried><id>C1</id><seq>1</seq></carried></equipment>
                  <delivery><at>202401020000</at></delivery>
                  <mode>3</mode>
                  <dangerous>1</dangerous>
                </shipment>""";

        IdentifierSet set = FORMAT.identifiers(identifiers);

        assertThat(set.get(IdentifierType.TRANSPORT_MEANS)).containsExactly("ABC123|");
        assertThat(set.get(IdentifierType.TRANSPORT_EQUIPMENT)).containsExactly("EQ1|FI|AE|1", "EQ2|SE|TE|2");
        assertThat(set.get(IdentifierType.CARRIED_EQUIPMENT)).containsExactly("C1|1", "C2|2");
        assertThat(set.get(IdentifierType.CARRIER_ACCEPTANCE_DATE)).containsExactly("202401010000");
        assertThat(set.get(IdentifierType.ACTUAL_DELIVERY_DATE)).containsExactly("202401020000");
        assertThat(set.get(IdentifierType.MODE_OF_TRANSPORT)).containsExactly("3");
        assertThat(set.get(IdentifierType.DANGEROUS_GOODS)).containsExactly("true");
    }

    @Test
    void identifiersOfADataSetWithoutValuesAreEmpty() {
        IdentifierSet set = FORMAT.identifiers(DATASET);

        assertThat(set.get(IdentifierType.ACTUAL_DELIVERY_DATE)).isEmpty();
        assertThat(set.get(IdentifierType.CARRIED_EQUIPMENT)).isEmpty();
        assertThat(set.get(IdentifierType.TRANSPORT_EQUIPMENT)).containsExactly("EQ1|PL|TE|1");
        assertThat(set.get(IdentifierType.DANGEROUS_GOODS)).containsExactly("false");
    }
}
