package io.paysre.control.investigation.minimax;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HttpMiniMaxChatClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    @Test
    void postsOpenAiCompatibleSingleDecisionRequest() {
        var fixture = fixture();
        fixture.server.expect(requestTo("https://api.minimax.io/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(jsonPath("$.model").value("MiniMax-M2"))
                .andExpect(jsonPath("$.tool_choice").value("auto"))
                .andExpect(jsonPath("$.temperature").value(1.0))
                .andExpect(jsonPath("$.max_completion_tokens").value(4096))
                .andExpect(jsonPath("$.messages[0].role").value("system"))
                .andExpect(jsonPath("$.messages[0].content").value("system prompt"))
                .andExpect(jsonPath("$.messages[1].role").value("user"))
                .andExpect(jsonPath("$.messages[1].content").value("state payload"))
                .andExpect(jsonPath("$.tools[0].function.name").value("get_payment_timeline"))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{
                          "role":"assistant",
                          "content":"",
                          "tool_calls":[{"id":"call_1","type":"function","function":{
                            "name":"get_payment_timeline",
                            "arguments":"{\\"paymentId\\":\\"PAY-1\\"}"}}]}}]}
                        """, MediaType.APPLICATION_JSON));

        var turn = fixture.client.complete("system prompt", "state payload", tools());

        assertThat(turn.toolCalls()).hasSize(1);
        var call = turn.toolCalls().get(0);
        assertThat(call.id()).isEqualTo("call_1");
        assertThat(call.name()).isEqualTo("get_payment_timeline");
        assertThat(call.arguments().path("paymentId").asText()).isEqualTo("PAY-1");
        fixture.server.verify();
    }

    @Test
    void returnsPlainAssistantContentWhenNoToolCallIsPresent() {
        var fixture = fixture();
        fixture.server.expect(requestTo("https://api.minimax.io/v1/chat/completions"))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"role":"assistant","content":"thinking aloud"}}]}
                        """, MediaType.APPLICATION_JSON));

        var turn = fixture.client.complete("s", "u", tools());

        assertThat(turn.content()).isEqualTo("thinking aloud");
        assertThat(turn.toolCalls()).isEmpty();
    }

    @Test
    void failsClosedOnHttpError() {
        var fixture = fixture();
        fixture.server.expect(requestTo("https://api.minimax.io/v1/chat/completions"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> fixture.client.complete("s", "u", tools()))
                .isInstanceOf(MiniMaxModelException.class)
                .hasMessageContaining("MINIMAX_HTTP_500");
    }

    @Test
    void failsClosedWhenBackendIsUnreachable() {
        var fixture = fixture();
        fixture.server.expect(requestTo("https://api.minimax.io/v1/chat/completions"))
                .andRespond(withException(new IOException("connection reset")));

        assertThatThrownBy(() -> fixture.client.complete("s", "u", tools()))
                .isInstanceOf(MiniMaxModelException.class)
                .hasMessageContaining("MINIMAX_UNREACHABLE");
    }

    @Test
    void failsClosedOnEmptyChoices() {
        var fixture = fixture();
        fixture.server.expect(requestTo("https://api.minimax.io/v1/chat/completions"))
                .andRespond(withSuccess("{\"choices\":[]}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client.complete("s", "u", tools()))
                .isInstanceOf(MiniMaxModelException.class)
                .hasMessageContaining("MINIMAX_EMPTY_RESPONSE");
    }

    @Test
    void failsClosedOnNonZeroBaseRespEvenWithHttp200() {
        var fixture = fixture();
        fixture.server.expect(requestTo("https://api.minimax.io/v1/chat/completions"))
                .andRespond(withSuccess("""
                        {"base_resp":{"status_code":1004,"status_msg":"invalid api key"},
                         "choices":[{"message":{"role":"assistant","content":"x"}}]}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client.complete("s", "u", tools()))
                .isInstanceOf(MiniMaxModelException.class)
                .hasMessageContaining("MINIMAX_BASE_RESP_1004")
                .hasMessageContaining("invalid api key");
    }

    @Test
    void failsClosedOnMalformedToolArguments() {
        var fixture = fixture();
        fixture.server.expect(requestTo("https://api.minimax.io/v1/chat/completions"))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{
                          "role":"assistant","content":"",
                          "tool_calls":[{"id":"call_1","type":"function","function":{
                            "name":"get_payment_timeline","arguments":"not-json"}}]}}]}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client.complete("s", "u", tools()))
                .isInstanceOf(MiniMaxModelException.class)
                .hasMessageContaining("MINIMAX_MALFORMED_TOOL_ARGUMENTS");
    }

    private com.fasterxml.jackson.databind.node.ArrayNode tools() {
        var tools = MAPPER.createArrayNode();
        var function = MAPPER.createObjectNode();
        function.put("name", "get_payment_timeline");
        tools.addObject().put("type", "function").set("function", function);
        return tools;
    }

    private Fixture fixture() {
        var builder = RestClient.builder()
                .baseUrl("https://api.minimax.io/v1")
                .defaultHeader("Authorization", "Bearer test-key");
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new HttpMiniMaxChatClient(
                builder.build(), MAPPER, "MiniMax-M2", 1.0, 4096);
        return new Fixture(client, server);
    }

    private record Fixture(HttpMiniMaxChatClient client, MockRestServiceServer server) {
    }
}
