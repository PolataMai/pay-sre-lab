package io.paysre.control.investigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.paysre.contracts.Money;
import io.paysre.control.evidence.Evidence;
import io.paysre.control.evidence.EvidenceRepository;
import io.paysre.control.incident.Incident;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RootCausePolicyCatalogTest {

    @Test
    void defaultsExposeTheChannelTimeoutPolicy() {
        var catalog = RootCausePolicyCatalog.defaults();
        var policy = catalog.forRootCause(RootCauseCode.CHANNEL_TIMEOUT_RESPONSE_LOST);

        assertThat(policy.rootCause())
                .isEqualTo(RootCauseCode.CHANNEL_TIMEOUT_RESPONSE_LOST);
        assertThat(policy.allowedRunbooks())
                .containsExactly("query-and-sync-unknown-payments");
        assertThat(policy.requiresHumanReview()).isTrue();
    }

    @Test
    void defaultsExposeTheDeclineSpikeAdvisoryPolicy() {
        var catalog = RootCausePolicyCatalog.defaults();
        var policy = catalog.forRootCause(RootCauseCode.CHANNEL_DECLINE_SPIKE);

        assertThat(policy.allowedRunbooks()).isEmpty();
        assertThat(policy.requiresHumanReview()).isTrue();
    }

    @Test
    void defaultsExposeTheCodeMappingErrorAdvisoryPolicy() {
        var catalog = RootCausePolicyCatalog.defaults();
        var policy = catalog.forRootCause(RootCauseCode.CHANNEL_CODE_MAPPING_ERROR);

        assertThat(policy.allowedRunbooks()).isEmpty();
        assertThat(policy.requiresHumanReview()).isTrue();
    }

    @Test
    void describeProducesDeterministicPolicySummary() {
        var description = RootCausePolicyCatalog.defaults().describe();

        assertThat(description).contains(
                "- rootCause=CHANNEL_TIMEOUT_RESPONSE_LOST, "
                        + "allowedRunbooks=[query-and-sync-unknown-payments], "
                        + "requiresHumanReview=true");
        assertThat(description).contains(
                "- rootCause=CHANNEL_DECLINE_SPIKE, "
                        + "allowedRunbooks=[], "
                        + "requiresHumanReview=true");
        assertThat(description).contains(
                "- rootCause=CHANNEL_CODE_MAPPING_ERROR, "
                        + "allowedRunbooks=[], "
                        + "requiresHumanReview=true");
    }

    @Test
    void builderRequiresAtLeastOnePolicy() {
        assertThatThrownBy(() -> RootCausePolicyCatalog.builder().build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least one policy");
    }

    @Test
    void unknownRootCauseIsRejectedThroughTheConclusionValidator() {
        // A policy that always rejects — confirms the catalog is
        // consulted before the generic structural checks would pass.
        var catalog = RootCausePolicyCatalog.builder()
                .register(new RejectingPolicy()).build();
        var repository = new InMemoryEvidenceRepository();
        var incident = newIncident("INC-CATALOG-1");
        var impact = repository.seed("E-IMPACT", incident.incidentId(),
                "INCIDENT_IMPACT", content -> content
                        .put("affectedPaymentCount", 1)
                        .put("totalAmount", new BigDecimal("10.00"))
                        .put("currency", "CNY"));
        var metrics = repository.seed("E-METRICS", incident.incidentId(),
                "SERVICE_METRICS", content -> content
                        .putObject("series")
                        .putArray("samples")
                        .add(2));
        var timeline = repository.seed("E-TIMELINE", incident.incidentId(),
                "PAYMENT_TIMELINE", content -> content
                        .putArray("events"));
        var conclusion = new InvestigationConclusion(
                incident.incidentId(),
                RootCauseCode.CHANNEL_TIMEOUT_RESPONSE_LOST,
                new BigDecimal("0.95"),
                List.of(impact.evidenceId(), metrics.evidenceId(),
                        timeline.evidenceId()),
                1,
                new Money(new BigDecimal("10.00"), java.util.Currency.getInstance("CNY")),
                "any-runbook",
                true);

        assertThatThrownBy(() -> new ConclusionValidator(repository, catalog)
                .validate(incident, conclusion))
                .isInstanceOf(InvalidConclusionException.class)
                .hasMessageContaining("not in catalog");
    }

    @Test
    void channelTimeoutPolicyStillEnforcesRunbookAllowList() {
        var catalog = RootCausePolicyCatalog.defaults();
        var repository = new InMemoryEvidenceRepository();
        var incident = newIncident("INC-CATALOG-2");
        var impact = repository.seed("E-IMPACT-2", incident.incidentId(),
                "INCIDENT_IMPACT", content -> content
                        .put("affectedPaymentCount", 1)
                        .put("totalAmount", new BigDecimal("10.00"))
                        .put("currency", "CNY"));
        var metrics = repository.seed("E-METRICS-2", incident.incidentId(),
                "SERVICE_METRICS", content -> content
                        .putObject("series")
                        .putArray("samples")
                        .add(2));
        var timeline = repository.seed("E-TIMELINE-2", incident.incidentId(),
                "PAYMENT_TIMELINE", content -> content
                        .putArray("events"));
        var wrong = new InvestigationConclusion(
                incident.incidentId(),
                RootCauseCode.CHANNEL_TIMEOUT_RESPONSE_LOST,
                new BigDecimal("0.95"),
                List.of(impact.evidenceId(), metrics.evidenceId(),
                        timeline.evidenceId()),
                1,
                new Money(new BigDecimal("10.00"), java.util.Currency.getInstance("CNY")),
                "not-the-allowed-runbook",
                true);

        assertThatThrownBy(() -> new ConclusionValidator(repository, catalog)
                .validate(incident, wrong))
                .isInstanceOf(InvalidConclusionException.class)
                .hasMessageContaining("runbook");
    }

    private static Incident newIncident(String id) {
        return Incident.detected(
                id, "payment-service:CHANNEL_A:payment_unknown_current",
                Instant.parse("2026-07-19T00:00:00Z"));
    }

    private static final class RejectingPolicy implements RootCausePolicy {
        @Override
        public RootCauseCode rootCause() {
            return RootCauseCode.CHANNEL_TIMEOUT_RESPONSE_LOST;
        }

        @Override
        public Set<String> allowedRunbooks() {
            return Set.of();
        }

        @Override
        public boolean requiresHumanReview() {
            return false;
        }

        @Override
        public void validateConclusion(
                RootCausePolicy.IncidentContext context,
                InvestigationConclusion conclusion,
                List<Evidence> referenced) {
            throw new InvalidConclusionException("root cause is not in catalog");
        }
    }

    /**
     * Minimal in-memory evidence repository stub.
     */
    private static final class InMemoryEvidenceRepository implements EvidenceRepository {

        private final Map<String, Evidence> store = new LinkedHashMap<>();

        @Override
        public List<Evidence> findByIncidentId(String incidentId) {
            return store.values().stream()
                    .filter(item -> item.incidentId().equals(incidentId))
                    .toList();
        }

        @Override
        public Optional<Evidence> findById(String evidenceId) {
            return Optional.ofNullable(store.get(evidenceId));
        }

        @Override
        public Evidence save(Evidence evidence) {
            store.put(evidence.evidenceId(), evidence);
            return evidence;
        }

        Evidence seed(String id, String incidentId, String type,
                      java.util.function.Consumer<ObjectNode> contentBuilder) {
            var content = JsonNodeFactory.instance.objectNode();
            contentBuilder.accept(content);
            var evidence = new Evidence(id, incidentId, type, "tool", 1, content,
                    "sha256-placeholder", Instant.now());
            store.put(id, evidence);
            return evidence;
        }
    }
}