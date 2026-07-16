package io.paysre.control.tools;

import io.paysre.control.observability.LokiReadClient;
import io.paysre.control.observability.StructuredLogResult;
import io.paysre.control.observability.StructuredLogSearch;
import java.time.Duration;
import java.util.Objects;

public final class SearchStructuredLogsTool
        implements ToolHandler<StructuredLogSearch, StructuredLogResult> {

    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "search_structured_logs",
            1,
            "Read projected payment events from Loki using allowlisted filters",
            Duration.ofSeconds(4),
            65_536,
            ToolRisk.READ_ONLY);

    private final LokiReadClient client;

    public SearchStructuredLogsTool(LokiReadClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public Class<StructuredLogSearch> inputType() {
        return StructuredLogSearch.class;
    }

    @Override
    public StructuredLogResult execute(StructuredLogSearch input) {
        return client.search(input);
    }
}
