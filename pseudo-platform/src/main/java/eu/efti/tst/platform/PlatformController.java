package eu.efti.tst.platform;

import eu.efti.tst.core.MessageTable;
import eu.efti.tst.domibus.DomibusException;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.stream.Stream;

/** The single page of the pseudo platform; every form posts an action and redirects back to it. */
@Controller
public class PlatformController {

    private final PseudoPlatform platform;
    private final PlatformBehaviour behaviour;
    private final PlatformProperties properties;
    /** The test data sets of the format by name, sorted, so that a base data set comes before its variants. */
    private final SortedMap<String, String> testData;

    public PlatformController(PseudoPlatform platform, PlatformBehaviour behaviour, PlatformProperties properties)
            throws IOException {
        this.platform = platform;
        this.behaviour = behaviour;
        this.properties = properties;
        this.testData = readTestData(Path.of(properties.formatDir(), "testdata"));
    }

    private static SortedMap<String, String> readTestData(Path folder) throws IOException {
        SortedMap<String, String> testData = new TreeMap<>();
        if (Files.isDirectory(folder)) {
            try (Stream<Path> files = Files.list(folder)) {
                for (Path file : files.filter(file -> file.toString().endsWith(".xml")).toList()) {
                    testData.put(file.getFileName().toString().replaceFirst("\\.xml$", ""), Files.readString(file));
                }
            }
        }
        return testData;
    }

    /** Runs the action and reports its outcome (or its failure, e.g. invalid input or Domibus unavailable). */
    private static String act(RedirectAttributes attributes, Supplier<String> action) {
        try {
            attributes.addFlashAttribute("notice", action.get());
        } catch (IllegalArgumentException | IllegalStateException | DomibusException e) {
            attributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/";
    }

    @ModelAttribute("view")
    public PlatformView view() {
        return platform.view();
    }

    @ModelAttribute("properties")
    public PlatformProperties properties() {
        return properties;
    }

    @ModelAttribute("formatVersion")
    public String formatVersion() {
        return behaviour.formatVersion();
    }

    @ModelAttribute("messageTableCss")
    public String messageTableCss() {
        return MessageTable.CSS;
    }

    @ModelAttribute("testDataSets")
    public Set<String> testDataSets() {
        return testData.keySet();
    }

    /** The test data set loaded into the form: the requested one, else the first one. */
    @ModelAttribute("selectedTestData")
    public String selectedTestData(@RequestParam(required = false) String testData) {
        return testData != null && this.testData.containsKey(testData) ? testData
                : this.testData.isEmpty() ? "" : this.testData.firstKey();
    }

    /** Not called if the flash attributes of a failed create already carry the entered XML. */
    @ModelAttribute("newXml")
    public String newXml(@RequestParam(required = false) String testData) {
        return this.testData.getOrDefault(selectedTestData(testData), "");
    }

    /**
     * The template is not named index, so that Spring Boot's welcome page (which renders index without the model
     * attributes above) never serves it.
     */
    @GetMapping("/")
    public String page() {
        return "platform";
    }

    @PostMapping("/datasets")
    public String create(@RequestParam String xml, RedirectAttributes attributes) {
        attributes.addFlashAttribute("newXml", xml);
        return act(attributes, () -> "Data set " + platform.create(xml) + " created (DRAFT)");
    }

    @PostMapping("/datasets/{id}/carrier-signature")
    public String signAsCarrier(@PathVariable String id, RedirectAttributes attributes) {
        return act(attributes, () -> {
            platform.signAsCarrier(id);
            return "Carrier signature applied to " + id + ", identifier upload sent";
        });
    }

    @PostMapping("/datasets/{id}/update")
    public String update(@PathVariable String id, @RequestParam String xml, RedirectAttributes attributes) {
        attributes.addFlashAttribute("editedId", id);
        attributes.addFlashAttribute("editedXml", xml);
        return act(attributes, () -> "Data set " + id + " updated"
                + (platform.update(id, xml) ? ", identifier upload sent" : ", no identifier upload sent"));
    }

    @PostMapping("/datasets/{id}/consignee-signature")
    public String signAsConsignee(@PathVariable String id, RedirectAttributes attributes) {
        return act(attributes, () -> {
            platform.signAsConsignee(id);
            return "Consignee signature applied to " + id + ", identifier upload sent";
        });
    }

    @PostMapping("/clear")
    public String clear(RedirectAttributes attributes) {
        return act(attributes, () -> {
            platform.clear();
            return "Data sets and messages cleared";
        });
    }

    @PostMapping("/faults")
    public String setFaults(@RequestParam(defaultValue = "false") boolean ignoreDataRequests,
                            @RequestParam(defaultValue = "false") boolean ignoreSubsetFilter,
                            @RequestParam(defaultValue = "false") boolean skipUploadOnUpdate,
                            RedirectAttributes attributes) {
        return act(attributes, () -> {
            platform.setToggles(new FaultToggles(ignoreDataRequests, ignoreSubsetFilter, skipUploadOnUpdate));
            return "Fault toggles saved";
        });
    }
}
