package io.paysre.control.tools;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.Objects;

public final class GetPaymentTimelineTool
        implements ToolHandler<GetPaymentTimelineTool.Input, JsonNode> {

    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "get_payment_timeline",
            1,
            "Read the ordered business state transitions of a synthetic payment",
            Duration.ofSeconds(2),
            65_536,
            ToolRisk.READ_ONLY);

    private final PaymentReadClient paymentClient;

    public GetPaymentTimelineTool(PaymentReadClient paymentClient) {
        this.paymentClient = paymentClient;
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
        return paymentClient.timeline(input.paymentId());
    }

    public record Input(String paymentId) {
        public Input {
            Objects.requireNonNull(paymentId, "paymentId");
        }
    }
}
