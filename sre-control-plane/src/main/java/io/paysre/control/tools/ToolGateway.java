package io.paysre.control.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.paysre.control.evidence.Evidence;
import io.paysre.control.evidence.EvidenceRepository;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ToolGateway {

    private static final Logger LOGGER = LoggerFactory.getLogger(ToolGateway.class);

    private final Map<String, ToolHandler<?, ?>> handlers;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;
    private final EvidenceRepository evidenceRepository;
    private final ToolAuditRepository auditRepository;
    private final Clock clock;
    private final Supplier<String> evidenceIds;

    public ToolGateway(
            List<ToolHandler<?, ?>> handlers,
            ObjectMapper objectMapper,
            ExecutorService executor,
            EvidenceRepository evidenceRepository,
            ToolAuditRepository auditRepository,
            Clock clock,
            Supplier<String> evidenceIds) {
        this.handlers = indexHandlers(handlers);
        this.objectMapper = objectMapper;
        this.executor = executor;
        this.evidenceRepository = evidenceRepository;
        this.auditRepository = auditRepository;
        this.clock = clock;
        this.evidenceIds = evidenceIds;
    }

    public ToolResult execute(
            String incidentId, String agentId, String toolName, JsonNode arguments) {
        long started = System.nanoTime();
        var handler = handlers.get(toolName);
        if (handler == null) {
            return failure(
                    incidentId, agentId, toolName, 0, "UNKNOWN_TOOL", started);
        }

        Object input;
        try {
            input = objectMapper.treeToValue(arguments, handler.inputType());
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            return failure(
                    incidentId,
                    agentId,
                    toolName,
                    handler.definition().version(),
                    "INVALID_ARGUMENTS",
                    started);
        }

        java.util.concurrent.Future<Object> future;
        try {
            future = executor.submit(() -> invoke(handler, input));
        } catch (RejectedExecutionException exception) {
            return failure(
                    incidentId,
                    agentId,
                    toolName,
                    handler.definition().version(),
                    "TOOL_CAPACITY_EXHAUSTED",
                    started);
        }
        Object output;
        try {
            output = future.get(
                    handler.definition().timeout().toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            return failure(
                    incidentId,
                    agentId,
                    toolName,
                    handler.definition().version(),
                    "TOOL_TIMEOUT",
                    started);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return failure(
                    incidentId,
                    agentId,
                    toolName,
                    handler.definition().version(),
                    "TOOL_INTERRUPTED",
                    started);
        } catch (ExecutionException exception) {
            return failure(
                    incidentId,
                    agentId,
                    toolName,
                    handler.definition().version(),
                    "TOOL_EXECUTION_FAILED",
                    started);
        }

        JsonNode content = objectMapper.valueToTree(output);
        byte[] serialized;
        try {
            serialized = objectMapper.writeValueAsBytes(content);
        } catch (JsonProcessingException exception) {
            return failure(
                    incidentId,
                    agentId,
                    toolName,
                    handler.definition().version(),
                    "RESULT_SERIALIZATION_FAILED",
                    started);
        }
        if (serialized.length > handler.definition().maximumResultBytes()) {
            return failure(
                    incidentId,
                    agentId,
                    toolName,
                    handler.definition().version(),
                    "RESULT_TOO_LARGE",
                    started);
        }

        String evidenceId = evidenceIds.get();
        var item = new Evidence(
                evidenceId,
                incidentId,
                evidenceType(toolName),
                toolName,
                handler.definition().version(),
                content,
                sha256(serialized),
                clock.instant());
        evidenceRepository.save(item);
        Duration duration = elapsed(started);
        var result = new ToolResult(toolName, true, List.of(evidenceId), duration, null);
        auditRepository.record(new ToolInvocation(
                UUID.randomUUID().toString(),
                incidentId,
                agentId,
                toolName,
                handler.definition().version(),
                true,
                result.evidenceIds(),
                duration,
                null,
                clock.instant()));
        LOGGER.atInfo()
                .addKeyValue("event", "INCIDENT_TOOL_EXECUTED")
                .addKeyValue("incidentId", incidentId)
                .addKeyValue("toolName", toolName)
                .addKeyValue("toolVersion", handler.definition().version())
                .addKeyValue("success", true)
                .addKeyValue("evidenceCount", result.evidenceIds().size())
                .addKeyValue("durationMs", duration.toMillis())
                .log("Incident tool executed");
        return result;
    }

    @SuppressWarnings("unchecked")
    private Object invoke(ToolHandler<?, ?> handler, Object input) {
        return ((ToolHandler<Object, Object>) handler).execute(input);
    }

    private ToolResult failure(
            String incidentId,
            String agentId,
            String toolName,
            int version,
            String errorCode,
            long started) {
        Duration duration = elapsed(started);
        var result = new ToolResult(toolName, false, List.of(), duration, errorCode);
        auditRepository.record(new ToolInvocation(
                UUID.randomUUID().toString(),
                incidentId,
                agentId,
                toolName,
                version,
                false,
                List.of(),
                duration,
                errorCode,
                clock.instant()));
        LOGGER.atWarn()
                .addKeyValue("event", "INCIDENT_TOOL_FAILED")
                .addKeyValue("incidentId", incidentId)
                .addKeyValue("toolName", toolName)
                .addKeyValue("toolVersion", version)
                .addKeyValue("success", false)
                .addKeyValue("errorCode", errorCode)
                .addKeyValue("durationMs", duration.toMillis())
                .log("Incident tool failed");
        return result;
    }

    private Duration elapsed(long started) {
        return Duration.ofNanos(Math.max(0, System.nanoTime() - started));
    }

    private Map<String, ToolHandler<?, ?>> indexHandlers(List<ToolHandler<?, ?>> handlers) {
        var indexed = new LinkedHashMap<String, ToolHandler<?, ?>>();
        for (var handler : handlers) {
            var previous = indexed.put(handler.definition().name(), handler);
            if (previous != null) {
                throw new IllegalArgumentException(
                        "duplicate tool handler: " + handler.definition().name());
            }
        }
        return Map.copyOf(indexed);
    }

    private String evidenceType(String toolName) {
        return switch (toolName) {
            case "get_payment_timeline" -> "PAYMENT_TIMELINE";
            case "query_channel_final_state" -> "CHANNEL_FINAL_STATE";
            case "calculate_incident_impact" -> "INCIDENT_IMPACT";
            default -> toolName.toUpperCase(java.util.Locale.ROOT);
        };
    }

    private String sha256(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }
}
