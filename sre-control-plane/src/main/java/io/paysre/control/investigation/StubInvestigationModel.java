package io.paysre.control.investigation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.paysre.contracts.Money;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

public final class StubInvestigationModel implements InvestigationModel {

    private static final String RUNBOOK = "query-and-sync-unknown-payments";

    private final ObjectMapper objectMapper;

    public StubInvestigationModel(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public InvestigationDecision decide(InvestigationContext context) {
        return switch (context.toolResults().size()) {
            case 0 -> new InvestigationDecision.CallTool(
                    "get_payment_timeline",
                    objectMapper.createObjectNode()
                            .put("paymentId", context.seed().representativePaymentId()));
            case 1 -> new InvestigationDecision.CallTool(
                    "query_channel_final_state",
                    objectMapper.createObjectNode()
                            .put("paymentId", context.seed().representativePaymentId()));
            case 2 -> new InvestigationDecision.CallTool(
                    "calculate_incident_impact",
                    objectMapper.createObjectNode()
                            .put("channel", context.seed().channel())
                            .put("from", context.seed().from().toString())
                            .put("to", context.seed().to().toString()));
            default -> conclude(context);
        };
    }

    private InvestigationDecision conclude(InvestigationContext context) {
        var impact = context.evidence().stream()
                .filter(item -> item.evidenceType().equals("INCIDENT_IMPACT"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("impact evidence is missing"));
        var content = impact.content();
        var amount = content.path("totalAmount").decimalValue();
        var currency = Currency.getInstance(content.path("currency").asText());
        var conclusion = new InvestigationConclusion(
                context.incident().incidentId(),
                RootCauseCode.CHANNEL_TIMEOUT_RESPONSE_LOST,
                new BigDecimal("0.95"),
                context.evidence().stream().map(item -> item.evidenceId()).toList(),
                content.path("affectedPaymentCount").asLong(),
                new Money(amount, currency),
                RUNBOOK,
                true);
        return new InvestigationDecision.Conclude(conclusion);
    }
}
