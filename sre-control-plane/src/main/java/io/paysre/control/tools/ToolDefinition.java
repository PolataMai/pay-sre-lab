package io.paysre.control.tools;

import java.time.Duration;

public record ToolDefinition(
        String name,
        int version,
        String description,
        Duration timeout,
        int maximumResultBytes,
        ToolRisk risk) {

    public ToolDefinition {
        if (version < 1) {
            throw new IllegalArgumentException("tool version must be positive");
        }
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("tool timeout must be positive");
        }
        if (maximumResultBytes < 1 || maximumResultBytes > 65_536) {
            throw new IllegalArgumentException("maximum result bytes must be between 1 and 65536");
        }
        if (risk != ToolRisk.READ_ONLY) {
            throw new IllegalArgumentException("foundation tools must be read only");
        }
    }
}
