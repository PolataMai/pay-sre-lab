package io.paysre.control.tools;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

public record ToolInvocation(
        String invocationId,
        String incidentId,
        String agentId,
        String toolName,
        int toolVersion,
        boolean successful,
        List<String> evidenceIds,
        Duration duration,
        String errorCode,
        Instant invokedAt) {

    public ToolInvocation {
        evidenceIds = List.copyOf(evidenceIds);
    }
}
