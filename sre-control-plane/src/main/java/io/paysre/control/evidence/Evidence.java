package io.paysre.control.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Objects;

public record Evidence(
        String evidenceId,
        String incidentId,
        String evidenceType,
        String sourceTool,
        int sourceToolVersion,
        JsonNode content,
        String sha256,
        Instant collectedAt) {

    public Evidence {
        Objects.requireNonNull(evidenceId, "evidenceId");
        Objects.requireNonNull(incidentId, "incidentId");
        Objects.requireNonNull(evidenceType, "evidenceType");
        Objects.requireNonNull(sourceTool, "sourceTool");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(sha256, "sha256");
        Objects.requireNonNull(collectedAt, "collectedAt");
        content = content.deepCopy();
    }
}
