package eu.efti.tst.runner;

import eu.efti.tst.core.Format;
import eu.efti.tst.core.IdentifierSet;
import eu.efti.tst.core.IdentifierType;

import java.util.List;
import java.util.regex.Pattern;

/**
 * A configured data set with what the gate expects from it during the lifecycle scenario.
 *
 * @param expectedDataset      the data set after the correction of the transport means, compared in the round trip
 *                             (T-DAT-06)
 * @param identifiersAsCreated identifier values derived from the data set as created (T-RUN-02)
 * @param identifiersCorrected identifier values after the correction of the transport means (T-RUN-03 onwards)
 * @param road                 whether its mode of transport is road (retrievability after delivery is checked)
 */
record DatasetPlan(DatasetCatalogue.Dataset config, String expectedDataset, IdentifierSet identifiersAsCreated,
                   IdentifierSet identifiersCorrected, boolean road) {

    // Comments are not data, but may mention the typo.
    private static final Pattern XML_COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

    String key() {
        return config.key();
    }

    /** The subsets the test data set holds, requested in T-RUN-04 (a). */
    List<String> subsets() {
        return config.subsets();
    }

    /**
     * @throws IllegalArgumentException if the format has no such test data set, the data set is not valid for the
     *                                  format or the typo does not identify the transport means unambiguously
     * @throws java.io.UncheckedIOException if the test data set cannot be read
     */
    static DatasetPlan load(DatasetCatalogue.Dataset config, Format format) {
        String created = XML_COMMENT.matcher(format.testData(config.testData())).replaceAll("");
        int occurrences = created.split(Pattern.quote(config.transportMeansTypo()), -1).length - 1;
        if (occurrences != 1) {
            throw new IllegalArgumentException(config.key() + ": transportMeansTypo " + config.transportMeansTypo() + " occurs "
                    + occurrences + " times in the test data set " + config.testData() + " instead of once");
        }
        String corrected = created.replace(config.transportMeansTypo(), config.transportMeansCorrected());
        IdentifierSet asCreated = format.identifiers(format.deriveIdentifiers(created));
        IdentifierSet afterCorrection = format.identifiers(format.deriveIdentifiers(corrected));
        if (asCreated.get(IdentifierType.TRANSPORT_MEANS).equals(afterCorrection.get(IdentifierType.TRANSPORT_MEANS))) {
            throw new IllegalArgumentException(config.key() + ": transportMeansTypo " + config.transportMeansTypo()
                    + " is not the identifier of the transport means");
        }
        return new DatasetPlan(config, corrected, asCreated, afterCorrection,
                asCreated.get(IdentifierType.MODE_OF_TRANSPORT).contains(format.roadModeCode()));
    }
}
