package io.paysre.control.remediation;

import java.time.Instant;
import java.util.Objects;

public record ActionInvocation(
        String auditId,
        String incidentId,
        String executionId,
        String action,
        String actor,
        boolean allowed,
        String reasonCode,
        Instant occurredAt) {

    public ActionInvocation {
        Objects.requireNonNull(auditId, "auditId");
        Objects.requireNonNull(incidentId, "incidentId");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
