package io.paysre.control.tools;

import io.paysre.control.observability.DistributedTrace;
import io.paysre.control.observability.DistributedTraceQuery;
import io.paysre.control.observability.TempoReadClient;
import java.time.Duration;
import java.util.Objects;

public final class GetDistributedTraceTool
        implements ToolHandler<DistributedTraceQuery, DistributedTrace> {

    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "get_distributed_trace",
            1,
            "Read a projected distributed trace from Tempo by validated trace ID",
            Duration.ofSeconds(4),
            65_536,
            ToolRisk.READ_ONLY);

    private final TempoReadClient client;

    public GetDistributedTraceTool(TempoReadClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public Class<DistributedTraceQuery> inputType() {
        return DistributedTraceQuery.class;
    }

    @Override
    public DistributedTrace execute(DistributedTraceQuery input) {
        return client.get(input);
    }
}
