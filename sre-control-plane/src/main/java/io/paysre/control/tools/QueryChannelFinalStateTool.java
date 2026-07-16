package io.paysre.control.tools;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.Objects;

public final class QueryChannelFinalStateTool
        implements ToolHandler<QueryChannelFinalStateTool.Input, JsonNode> {

    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "query_channel_final_state",
            1,
            "Read the synthetic channel's final state for a payment",
            Duration.ofSeconds(2),
            65_536,
            ToolRisk.READ_ONLY);

    private final ChannelReadClient channelClient;

    public QueryChannelFinalStateTool(ChannelReadClient channelClient) {
        this.channelClient = channelClient;
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public Class<Input> inputType() {
        return Input.class;
    }

    @Override
    public JsonNode execute(Input input) {
        return channelClient.finalState(input.paymentId());
    }

    public record Input(String paymentId) {
        public Input {
            Objects.requireNonNull(paymentId, "paymentId");
        }
    }
}
