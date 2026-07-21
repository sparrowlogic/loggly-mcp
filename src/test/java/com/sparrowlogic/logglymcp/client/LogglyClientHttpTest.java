package com.sparrowlogic.logglymcp.client;

import com.sparrowlogic.logglymcp.domain.SearchPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestToUriTemplate;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Exercises {@link LogglyClient} against a {@link MockRestServiceServer}, covering the
 * {@code /events/iterate} pagination loop (single page, multi-page via {@code next}, capped),
 * the two {@code /fields/} discovery endpoints, and error mapping to {@link LogglyApiException}.
 */
class LogglyClientHttpTest {

    private static final String BASE_URL = "https://acme.loggly.com/apiv2";

    private MockRestServiceServer server;
    private LogglyClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        this.server = MockRestServiceServer.bindTo(builder).build();
        this.client = new LogglyClient(builder.build());
    }

    private static String eventJson(String id) {
        return """
                {"id": "%s", "timestamp": 1, "logmsg": "m", "raw": "r", "unparsed": null,
                 "logtypes": ["syslog"], "tags": ["prod"], "event": null}
                """.formatted(id);
    }

    @Test
    void searchAll_singlePage_noNextCursor() {
        this.server.expect(requestTo(BASE_URL + "/events/iterate?q=*&from=-24h&until=now&order=desc&size=1000"))
                .andExpect(method(GET))
                .andRespond(withSuccess(
                        "{\"events\": [" + eventJson("e1") + "], \"next\": null}", MediaType.APPLICATION_JSON));

        SearchPage page = this.client.searchAll("*", "-24h", "now", "desc", 1000);

        assertThat(page.events()).extracting(e -> e.id()).containsExactly("e1");
        assertThat(page.next()).isNull();
        this.server.verify();
    }

    @Test
    void searchAll_followsNextCursor_acrossPages() {
        String nextUrl = BASE_URL + "/events/iterate?next=cursor-1";
        this.server.expect(requestTo(BASE_URL + "/events/iterate?q=*&from=-24h&until=now&order=desc&size=1000"))
                .andRespond(withSuccess(
                        "{\"events\": [" + eventJson("e1") + "], \"next\": \"" + nextUrl + "\"}",
                        MediaType.APPLICATION_JSON));
        this.server.expect(requestTo(nextUrl))
                .andRespond(withSuccess(
                        "{\"events\": [" + eventJson("e2") + "], \"next\": null}", MediaType.APPLICATION_JSON));

        SearchPage page = this.client.searchAll("*", "-24h", "now", "desc", 1000);

        assertThat(page.events()).extracting(e -> e.id()).containsExactly("e1", "e2");
        assertThat(page.next()).isNull();
        this.server.verify();
    }

    @Test
    void searchAll_capped_reportsNextAndTruncatesToCap() {
        String nextUrl = BASE_URL + "/events/iterate?next=cursor-1";
        this.server.expect(requestTo(BASE_URL + "/events/iterate?q=*&from=-24h&until=now&order=desc&size=1"))
                .andRespond(withSuccess(
                        "{\"events\": [" + eventJson("e1") + "], \"next\": \"" + nextUrl + "\"}",
                        MediaType.APPLICATION_JSON));

        SearchPage page = this.client.searchAll("*", "-24h", "now", "desc", 1);

        assertThat(page.events()).extracting(e -> e.id()).containsExactly("e1");
        assertThat(page.next()).isEqualTo(nextUrl);
        this.server.verify();
    }

    @Test
    void searchAll_blankQueryAndOrder_defaultToWildcardAndDesc() {
        this.server.expect(requestTo(BASE_URL + "/events/iterate?q=*&from=-24h&until=now&order=desc&size=1000"))
                .andRespond(withSuccess("{\"events\": [], \"next\": null}", MediaType.APPLICATION_JSON));

        SearchPage page = this.client.searchAll(null, null, null, null, 0);

        assertThat(page.events()).isEmpty();
        this.server.verify();
    }

    @Test
    void listFields_hitsFieldsEndpointWithFacetSize() {
        this.server.expect(requestTo(BASE_URL + "/fields/?q=*&from=-24h&until=now&facet_size=10"))
                .andExpect(method(GET))
                .andRespond(withSuccess("{\"fields\": []}", MediaType.APPLICATION_JSON));

        JsonNode result = this.client.listFields(null, null, null, 0);

        assertThat(result.path("fields").isArray()).isTrue();
        this.server.verify();
    }

    @Test
    void fieldValues_hitsFieldEndpointWithTrailingSlash() {
        this.server.expect(requestToUriTemplate(BASE_URL + "/fields/{field}/?q=*&from=-24h&until=now&facet_size=50",
                        "syslog.host"))
                .andExpect(method(GET))
                .andRespond(withSuccess("{\"values\": []}", MediaType.APPLICATION_JSON));

        JsonNode result = this.client.fieldValues("syslog.host", null, null, null, 0);

        assertThat(result.path("values").isArray()).isTrue();
        this.server.verify();
    }

    @Test
    void nonSuccessResponse_raisesLogglyApiExceptionWithStatusAndBody() {
        this.server.expect(requestTo(BASE_URL + "/events/iterate?q=*&from=-24h&until=now&order=desc&size=1000"))
                .andRespond(withStatus(INTERNAL_SERVER_ERROR).body("boom"));

        assertThatThrownBy(() -> this.client.searchAll("*", "-24h", "now", "desc", 1000))
                .isInstanceOf(LogglyApiException.class)
                .satisfies(ex -> assertThat(((LogglyApiException) ex).getStatus()).isEqualTo(500))
                .hasMessageContaining("500")
                .hasMessageContaining("boom");
    }

    @Test
    void facetSize_clampedToMax_forListFields() {
        this.server.expect(requestTo(BASE_URL + "/fields/?q=*&from=-24h&until=now&facet_size=500"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        this.client.listFields(null, null, null, 100_000);

        this.server.verify();
    }

    @Test
    void facetSize_clampedToMax_forFieldValues() {
        this.server.expect(requestToUriTemplate(BASE_URL + "/fields/{field}/?q=*&from=-24h&until=now&facet_size=300",
                        "host"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        this.client.fieldValues("host", null, null, null, 100_000);

        this.server.verify();
    }
}
