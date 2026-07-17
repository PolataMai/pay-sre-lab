package io.paysre.control.remediation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.paysre.contracts.Money;
import io.paysre.control.evidence.Evidence;
import io.paysre.control.evidence.EvidenceRepository;
import io.paysre.control.incident.Incident;
import io.paysre.control.investigation.InvestigationConclusion;
import io.paysre.control.investigation.RootCauseCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class QueryAndSyncUnknownPaymentsRunbookTest {

    private static final Instant NOW = Instant.parse("2026-07-17T04:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final EvidenceRepository evidence = mock(EvidenceRepository.class);
    private final PaymentWriteClient payments = mock(PaymentWriteClient.class);
    private final QueryAndSyncUnknownPaymentsRunbook runbook =
            new QueryAndSyncUnknownPaymentsRunbook(evidence, payments, MAPPER);

    @Test
    void syncsExactlyThePaymentsRecordedInTheImpactEvidence() {
        when(evidence.findById("EVD-IMPACT"))
                .thenReturn(Optional.of(impactEvidence("INC-1", "P1", "P2", "P3")));
        when(evidence.findById("EVD-OTHER")).thenReturn(Optional.empty());
        when(payments.sync("P1")).thenReturn(sync("P1", "SYNCED", "SUCCESS"));
        when(payments.sync("P2")).thenReturn(sync("P2", "STILL_UNKNOWN", "UNKNOWN"));
        when(payments.sync("P3")).thenReturn(sync("P3", "NOT_UNKNOWN", "SUCCESS"));

        var result = runbook.execute(incident(), conclusion("EVD-OTHER", "EVD-IMPACT"));

        assertThat(result.path("targetCount").asInt()).isEqualTo(3);
        assertThat(result.path("synced").asInt()).isEqualTo(1);
        assertThat(result.path("stillUnknown").asInt()).isEqualTo(1);
        assertThat(result.path("alreadyFinal").asInt()).isEqualTo(1);
        assertThat(result.path("payments")).hasSize(3);
    }

    @Test
    void failsWhenTheConclusionReferencesNoImpactEvidence() {
        when(evidence.findById("EVD-OTHER")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> runbook.execute(incident(), conclusion("EVD-OTHER")))
                .isInstanceOf(RunbookExecutionException.class)
                .hasMessageContaining("IMPACT_EVIDENCE_MISSING");
    }

    @Test
    void failsWhenImpactEvidenceBelongsToAnotherIncident() {
        when(evidence.findById("EVD-IMPACT"))
                .thenReturn(Optional.of(impactEvidence("INC-OTHER", "P1")));

        assertThatThrownBy(() -> runbook.execute(incident(), conclusion("EVD-IMPACT")))
                .isInstanceOf(RunbookExecutionException.class)
                .hasMessageContaining("IMPACT_EVIDENCE_MISSING");
    }

    @Test
    void propagatesBackendFailuresSoTheExecutionFailsClosed() {
        when(evidence.findById("EVD-IMPACT"))
                .thenReturn(Optional.of(impactEvidence("INC-1", "P1")));
        when(payments.sync("P1")).thenThrow(
                new RemediationBackendException("PAYMENT_SERVICE_UNREACHABLE", "down"));

        assertThatThrownBy(() -> runbook.execute(incident(), conclusion("EVD-IMPACT")))
                .isInstanceOf(RemediationBackendException.class);
    }

    private Incident incident() {
        return Incident.detected("INC-1", "payment-unknown:CHANNEL_A", NOW);
    }

    private InvestigationConclusion conclusion(String... evidenceIds) {
        return new InvestigationConclusion(
                "INC-1",
                RootCauseCode.CHANNEL_TIMEOUT_RESPONSE_LOST,
                new BigDecimal("0.95"),
                List.of(evidenceIds),
                5,
                new Money(new BigDecimal("50.00"), Currency.getInstance("CNY")),
                QueryAndSyncUnknownPaymentsRunbook.NAME,
                true);
    }

    private Evidence impactEvidence(String incidentId, String... paymentIds) {
        var content = MAPPER.createObjectNode();
        content.put("affectedPaymentCount", paymentIds.length);
        content.put("totalAmount", new BigDecimal("50.00"));
        content.put("currency", "CNY");
        var ids = content.putArray("paymentIds");
        for (String paymentId : paymentIds) {
            ids.add(paymentId);
        }
        return new Evidence(
                "EVD-IMPACT",
                incidentId,
                "INCIDENT_IMPACT",
                "calculate_incident_impact",
                1,
                content,
                "hash",
                NOW);
    }

    private PaymentStateSync sync(String paymentId, String outcome, String currentStatus) {
        return new PaymentStateSync(paymentId, "UNKNOWN", currentStatus, "SUCCESS", outcome);
    }
}
