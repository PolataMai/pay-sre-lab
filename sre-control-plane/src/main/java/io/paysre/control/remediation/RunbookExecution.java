package io.paysre.control.remediation;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Objects;

/**
 * One controlled run of an allowlisted runbook for an incident. The
 * aggregate enforces the approval workflow: a proposal must be approved
 * by a second human before it runs, and results are immutable once final.
 */
public final class RunbookExecution {

    private final String executionId;
    private final String incidentId;
    private final String runbook;
    private final String requestedBy;
    private RunbookExecutionStatus status;
    private String approvedBy;
    private JsonNode result;
    private String error;
    private final Instant createdAt;
    private Instant updatedAt;
    private long version;
    private boolean persisted;

    private RunbookExecution(
            String executionId,
            String incidentId,
            String runbook,
            String requestedBy,
            Instant createdAt) {
        this.executionId = Objects.requireNonNull(executionId, "executionId");
        this.incidentId = Objects.requireNonNull(incidentId, "incidentId");
        this.runbook = Objects.requireNonNull(runbook, "runbook");
        this.requestedBy = Objects.requireNonNull(requestedBy, "requestedBy");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = createdAt;
        this.status = RunbookExecutionStatus.PENDING_APPROVAL;
    }

    public static RunbookExecution proposed(
            String executionId,
            String incidentId,
            String runbook,
            String requestedBy,
            Instant now) {
        return new RunbookExecution(executionId, incidentId, runbook, requestedBy, now);
    }

    public static RunbookExecution restore(
            String executionId,
            String incidentId,
            String runbook,
            RunbookExecutionStatus status,
            String requestedBy,
            String approvedBy,
            JsonNode result,
            String error,
            Instant createdAt,
            Instant updatedAt,
            long version) {
        var execution = new RunbookExecution(
                executionId, incidentId, runbook, requestedBy, createdAt);
        execution.status = Objects.requireNonNull(status, "status");
        execution.approvedBy = approvedBy;
        execution.result = result;
        execution.error = error;
        execution.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        execution.version = version;
        execution.persisted = true;
        return execution;
    }

    public void approve(String approver, Instant now) {
        require(RunbookExecutionStatus.PENDING_APPROVAL);
        Objects.requireNonNull(approver, "approver");
        if (approver.equals(requestedBy)) {
            throw new IllegalArgumentException(
                    "approver must differ from the proposer");
        }
        this.approvedBy = approver;
        transition(RunbookExecutionStatus.RUNNING, now);
    }

    public void succeed(JsonNode result, Instant now) {
        require(RunbookExecutionStatus.RUNNING);
        this.result = Objects.requireNonNull(result, "result").deepCopy();
        transition(RunbookExecutionStatus.SUCCEEDED, now);
    }

    public void fail(String error, Instant now) {
        require(RunbookExecutionStatus.RUNNING);
        this.error = Objects.requireNonNull(error, "error");
        transition(RunbookExecutionStatus.FAILED, now);
    }

    private void require(RunbookExecutionStatus expected) {
        if (status != expected) {
            throw new IllegalStateException(
                    "expected " + expected + " but was " + status);
        }
    }

    private void transition(RunbookExecutionStatus target, Instant now) {
        status = target;
        updatedAt = Objects.requireNonNull(now, "now");
    }

    public String executionId() {
        return executionId;
    }

    public String incidentId() {
        return incidentId;
    }

    public String runbook() {
        return runbook;
    }

    public RunbookExecutionStatus status() {
        return status;
    }

    public String requestedBy() {
        return requestedBy;
    }

    public String approvedBy() {
        return approvedBy;
    }

    public JsonNode result() {
        return result;
    }

    public String error() {
        return error;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public long version() {
        return version;
    }

    public boolean persisted() {
        return persisted;
    }

    public void markPersisted(long newVersion) {
        version = newVersion;
        persisted = true;
    }
}
