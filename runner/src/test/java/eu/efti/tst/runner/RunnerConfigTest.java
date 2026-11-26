package eu.efti.tst.runner;

import eu.efti.tst.core.IdentifierType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static eu.efti.tst.runner.Fixtures.FORMAT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunnerConfigTest {

    private static final Path EXAMPLE = Path.of("tst.example.yml");

    @Test
    void exampleConfigLoadsWithPathsRelativeToTheFile() {
        RunnerConfig config = RunnerConfig.load(EXAMPLE);
        Path base = EXAMPLE.toAbsolutePath().getParent();

        assertThat(config.formatDir()).isEqualTo(base.resolve("../formats/ri-v0.5"));
        assertThat(config.outputDir()).isEqualTo(base.resolve("target/tst-report"));
        assertThat(config.datasets()).containsExactly("DS1");
    }

    @Test
    void everyDataSetOfTheCatalogueWithTestDataHasAValidPlanWhoseCorrectionChangesTheTransportMeans() {
        assertThat(Fixtures.CATALOGUE.datasets()).filteredOn(dataset -> dataset.testData() != null).isNotEmpty()
                .allSatisfy(dataset -> {
                    DatasetPlan plan = DatasetPlan.load(dataset, FORMAT);
                    assertThat(plan.subsets()).isEqualTo(dataset.subsets()).isNotEmpty();
                    assertThat(plan.identifiersCorrected().get(IdentifierType.TRANSPORT_MEANS))
                            .isNotEqualTo(plan.identifiersAsCreated().get(IdentifierType.TRANSPORT_MEANS))
                            .singleElement().asString().contains(dataset.transportMeansCorrected());
                });
    }

    @Test
    void roadIsDerivedFromTheModeOfTransportOfTheTestData() {
        assertThat(Fixtures.plan("DS1").road()).isTrue();
        assertThat(Fixtures.plan("DS1-DG").road()).isTrue();
        assertThat(Fixtures.plan("DS1C").road()).isTrue();
        assertThat(Fixtures.plan("DS2").road()).isFalse();
        assertThat(Fixtures.plan("DS3").road()).isFalse();
        assertThat(Fixtures.plan("DS4").road()).isFalse();
    }

    @Test
    void dataSetsAreSelectedInTheRequestedOrderIfTheyAreAvailable() {
        DatasetCatalogue catalogue = Fixtures.CATALOGUE;

        assertThat(catalogue.select(List.of("DS2", "DS1"))).extracting(DatasetCatalogue.Dataset::key).containsExactly("DS2", "DS1");
        assertThatThrownBy(() -> catalogue.select(List.of("DS9"))).hasMessageContaining("DS9").hasMessageContaining("DS1");
        assertThatThrownBy(() -> catalogue.select(List.of("W"))).hasMessageContaining("W cannot be requested");
        assertThatThrownBy(() -> catalogue.select(List.of("DS1", "DS1"))).hasMessageContaining("more than once");
    }

    @Test
    void missingValuesAndUnknownKeysAreRejected(@TempDir Path dir) throws IOException {
        String example = Files.readString(EXAMPLE);
        Path withoutPlatformId = Files.writeString(dir.resolve("a.yml"), example.replace("  id: pseudoplatform ", "  # "));
        Path withoutDatasets = Files.writeString(dir.resolve("b.yml"), example.substring(0, example.indexOf("datasets:")));
        Path misspelledProperty = Files.writeString(dir.resolve("d.yml"), example.replace("platformName:", "platformNam:"));
        Path withResponseTimeLimit = Files.writeString(dir.resolve("e.yml"), example + "timeouts:\n  responseSeconds: 600\n");

        assertThatThrownBy(() -> RunnerConfig.load(withoutPlatformId)).hasMessageContaining("platform.id is required");
        assertThatThrownBy(() -> RunnerConfig.load(withoutDatasets)).hasMessageContaining("datasets must list at least one data set");
        assertThatThrownBy(() -> RunnerConfig.load(misspelledProperty)).hasMessageContaining("platformNam");
        // the 60 s of T-ALL-04 are a legal limit, so no setting may loosen them
        assertThatThrownBy(() -> RunnerConfig.load(withResponseTimeLimit)).hasMessageContaining("timeouts");
    }

    private static DatasetCatalogue.Dataset dataset(String testData, String typo, String corrected) {
        return new DatasetCatalogue.Dataset("DS1", "Road", null, testData, List.of("EU01"), typo, corrected);
    }

    @Test
    void typoMustIdentifyTheTransportMeans() {
        assertThatThrownBy(() -> DatasetPlan.load(dataset("ds1-road", "DE", "PL"), FORMAT)).hasMessageContaining("instead of once");
        assertThatThrownBy(() -> DatasetPlan.load(dataset("ds1-road", "Rampa 5", "Rampa 6"), FORMAT))
                .hasMessageContaining("is not the identifier of the transport means");
    }

    @Test
    void testDataMustExistInTheFormat() {
        assertThatThrownBy(() -> DatasetPlan.load(dataset("ds9-unknown", "BAB132", "BAB123"), FORMAT))
                .hasMessageContaining("no test data set ds9-unknown");
    }

    @Test
    void aDataSetWithTestDataNeedsItsSubsetsAndTransportMeans() {
        assertThatThrownBy(() -> new DatasetCatalogue.Dataset("DS1", "Road", null, "ds1-road", List.of(), "A", "B"))
                .hasMessageContaining("DS1.subsets");
        assertThatThrownBy(() -> dataset("ds1-road", null, "B")).hasMessageContaining("DS1.transportMeansTypo");
        assertThat(new DatasetCatalogue.Dataset("W", "Waste", null, null, null, null, null).subsets()).isEmpty();
    }
}
