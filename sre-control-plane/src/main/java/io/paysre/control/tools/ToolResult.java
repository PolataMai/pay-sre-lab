package io.paysre.control.tools;

import java.time.Duration;
import java.util.List;

public record ToolResult(
        String toolName,
        boolean successful,
        List<String> evidenceIds,
        Duration duration,
        String errorCode) {

    public ToolResult {
        evidenceIds = List.copyOf(evidenceIds);
    }
}
