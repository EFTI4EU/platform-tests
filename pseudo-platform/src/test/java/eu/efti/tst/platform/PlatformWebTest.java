package eu.efti.tst.platform;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.util.HtmlUtils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The page in the embedded server, with a client that, like a browser on its first visit, has no session cookie.
 * Domibus is not needed: the poller points to a closed port and only reports the connection error on the page.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "platform.ws-plugin-url=http://127.0.0.1:9/domibus/services/wsplugin",
        "platform.poll-interval=1h"})
class PlatformWebTest {

    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @LocalServerPort
    private int port;

    @Autowired
    private PseudoPlatform platform;

    private HttpResponse<String> send(HttpRequest.Builder request) throws IOException, InterruptedException {
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    }

    @Test
    void formPostWithoutSessionRedirectsToThePageItself() throws IOException, InterruptedException {
        HttpResponse<String> response = send(request("/faults")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("ignoreDataRequests=false")));

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location")).hasValueSatisfying(location ->
                assertThat(URI.create("http://localhost:" + port + "/").resolve(location).getRawPath()).isEqualTo("/"));
        assertThat(response.headers().firstValue("Location").orElseThrow()).doesNotContain(";", "?");
    }

    @Test
    void pageRendersWithItsState() throws IOException, InterruptedException {
        HttpResponse<String> response = send(request("/").GET());

        assertThat(response.statusCode()).isEqualTo(200);
        // the shared message table of core, with its style and unescaped
        assertThat(response.body()).contains("eFTI pseudo platform", "ri-v0.5", "data-live=\"other-messages\"",
                "table.messages tr.msg + tr td", "<p class=\"messages-empty\">No messages.</p>");
    }

    @Test
    void formIsFilledWithTheChosenTestDataSetAndOtherwiseTheFirst() throws IOException, InterruptedException {
        Path testData = Path.of("..", "formats", "ri-v0.5", "testdata");
        String first = send(request("/").GET()).body();
        String chosen = send(request("/?testData=ds2-rail").GET()).body();
        String unknown = send(request("/?testData=../../secret").GET()).body();

        assertThat(first).contains("<option value=\"ds1-road\" selected=\"selected\">", "<option value=\"ds4-air\">",
                HtmlUtils.htmlEscape(Files.readString(testData.resolve("ds1-road.xml")), "UTF-8"));
        assertThat(chosen).contains("<option value=\"ds2-rail\" selected=\"selected\">",
                HtmlUtils.htmlEscape(Files.readString(testData.resolve("ds2-rail.xml")), "UTF-8"));
        assertThat(unknown).contains("<option value=\"ds1-road\" selected=\"selected\">");
    }

    @Test
    void draftShowsItsXmlAndOnlyTheButtonsThatApply() throws IOException, InterruptedException {
        String id = platform.create(Files.readString(Path.of("..", "formats", "ri-v0.5", "testdata", "ds1-road.xml")));
        try {
            String body = send(request("/").GET()).body();

            assertThat(body).contains("<textarea name=\"xml\" rows=\"14\">", "<button>Update</button>",
                    "formaction=\"/datasets/" + id + "/carrier-signature\">Carrier signature</button>",
                    "formaction=\"/datasets/" + id + "/consignee-signature\" disabled=\"disabled\">Consignee signature</button>",
                    "<details open=\"open\">\n            <summary>Messages</summary>");
        } finally {
            platform.clear();
        }
    }

    @Test
    void unknownPagesShowWhereToFindTheLog() throws IOException, InterruptedException {
        HttpResponse<String> response = send(request("/unknown").GET().header("Accept", "text/html"));

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("docker compose logs pseudo-platform").doesNotContain("Whitelabel");
    }
}
