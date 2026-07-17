package io.paysre.control.remediation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.paysre.control.evidence.EvidenceRepository;
import io.paysre.control.incident.Incident;
import io.paysre.control.investigation.InvestigationConclusion;
import java.util.Objects;
import java.util.Optional;

/**
 * The only allowlisted runbook of this slice. Its remediation scope is
 * locked to the paymentIds recorded in the INCIDENT_IMPACT evidence the
 * conclusion references; nothing is recomputed at execution time.
 */
public final class QueryAndSyncUnknownPaymentsRunbook implements Runbook {

    public static final String NAME = "query-and-sync-unknown-payments";

    private final EvidenceRepository evidenceRepository;
    private final PaymentWriteClient paymentWriteClient;
    private final ObjectMapper objectMapper;

    public QueryAndSyncUnknownPaymentsRunbook(
            EvidenceRepository evidenceRepository,
            PaymentWriteClient paymentWriteClient,
            ObjectMapper objectMapper) {
        this.evidenceRepository = Objects.requireNonNull(evidenceRepository, "evidenceRepository");
        this.paymentWriteClient = Objects.requireNonNull(paymentWriteClient, "paymentWriteClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public JsonNode execute(Incident incident, InvestigationConclusion conclusion) {
        var impact = conclusion.evidenceIds().stream()
                .map(evidenceRepository::findById)
                .flatMap(Optional::stream)
                .filter(item -> item.evidenceType().equals("INCIDENT_IMPACT"))
                .filter(item -> item.incidentId().equals(incident.incidentId()))
                .findFirst()
                .orElseThrow(() -> new RunbookExecutionException(
                        "IMPACT_EVIDENCE_MISSING",
                        "the conclusion references no incident impact evidence"));
        var targets = impact.content().path("paymentIds");
        if (!targets.isArray()) {
            throw new RunbookExecutionException(
                    "IMPACT_TARGETS_MISSING",
                    "incident impact evidence carries no payment ID list");
        }

        int synced = 0;
        int stillUnknown = 0;
        int alreadyFinal = 0;
        var result = objectMapper.createObjectNode();
        result.put("runbook", NAME);
        var payments = result.putArray("payments");
        for (JsonNode target : targets) {
            var paymentId = target.asText();
            var sync = paymentWriteClient.sync(paymentId);
            switch (sync.outcome()) {
                case "SYNCED" -> synced++;
                case "STILL_UNKNOWN" -> stillUnknown++;
                default -> alreadyFinal++;
            }
            payments.addObject()
                    .put("paymentId", paymentId)
                    .put("outcome", sync.outcome())
                    .put("currentStatus", sync.currentStatus());
        }
        result.put("targetCount", targets.size());
        result.put("synced", synced);
        result.put("stillUnknown", stillUnknown);
        result.put("alreadyFinal", alreadyFinal);
        return result;
    }
}
