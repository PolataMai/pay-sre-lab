package io.paysre.control.investigation;

import io.paysre.control.evidence.Evidence;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Per-root-cause validation contract. A new fault family only has to
 * register a {@link RootCausePolicy} implementation alongside its
 * scenario YAML; neither the conclusion validator nor the model
 * adapters need to change.
 */
public interface RootCausePolicy {

    RootCauseCode rootCause();

    /**
     * Names of runbooks the agent is allowed to recommend for this root
     * cause. An empty set means the policy is advisory only — the model
     * should escalate or conclude without naming a runbook.
     */
    Set<String> allowedRunbooks();

    /**
     * Whether every conclusion for this root cause must require human
     * review before any recommended runbook can be executed.
     */
    boolean requiresHumanReview();

    /**
     * Verify the root-cause-specific shape of the conclusion: runbook
     * allow-list, human-review flag, and evidence sufficiency rules.
     * Structural checks (incident match, confidence range, evidence
     * uniqueness, impact match) stay in the {@link ConclusionValidator}.
     */
    void validateConclusion(
            IncidentContext context, InvestigationConclusion conclusion, List<Evidence> referenced);

    record IncidentContext(String incidentId) {
        public IncidentContext {
            Objects.requireNonNull(incidentId, "incidentId");
        }
    }
}