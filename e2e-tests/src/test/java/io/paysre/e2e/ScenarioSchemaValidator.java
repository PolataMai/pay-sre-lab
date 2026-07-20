package io.paysre.e2e;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Lightweight semantic validator for fault-scenario YAML files.
 * The plan's R9 milestone asks for a community-grade
 * scenario-contribution contract: a contributor dropping a new YAML
 * into {@code fault-scenarios/} must satisfy the rules below before
 * the scenario is admitted into the benchmark. The validator runs as
 * a unit test against every YAML in the resource directory so a
 * regression is caught by the next CI run.
 */
final class ScenarioSchemaValidator {

    private static final Set<String> KNOWN_FAULT_TYPES = Set.of(
            "NONE", "TIMEOUT_BUT_SUCCESS", "TIMEOUT_BUT_FAILED",
            "DECLINE_ALL", "CALLBACK_LOST", "CALLBACK_DUPLICATED");

    private static final Set<String> KNOWN_ROOT_CAUSES = Set.of(
            "CHANNEL_TIMEOUT_RESPONSE_LOST",
            "CHANNEL_DECLINE_SPIKE",
            "CHANNEL_CODE_MAPPING_ERROR",
            "CHANNEL_CALLBACK_LOST",
            "ROUTING_MISCONFIGURED");

    private final ScenarioGroundTruth truth;

    ScenarioSchemaValidator(ScenarioGroundTruth truth) {
        this.truth = Objects.requireNonNull(truth, "truth");
    }

    void validate() {
        var expected = truth.expected();
        if (expected == null) {
            throw new IllegalStateException(
                    "scenario " + truth.id() + " is missing the 'expected' block");
        }
        if (expected.minimumEvidenceCount() < expected.requiredEvidenceTypes().size()) {
            throw new IllegalStateException(
                    "scenario " + truth.id() + " declares "
                            + expected.minimumEvidenceCount()
                            + " minimum evidence but requires "
                            + expected.requiredEvidenceTypes().size()
                            + " distinct types — the minimum must be at least as large");
        }
        if (expected.requiredEvidenceTypes().stream().distinct().count()
                != expected.requiredEvidenceTypes().size()) {
            throw new IllegalStateException(
                    "scenario " + truth.id()
                            + " lists duplicate evidence types in requiredEvidenceTypes");
        }
        if (!KNOWN_FAULT_TYPES.contains(truth.fault().type())) {
            throw new IllegalStateException(
                    "scenario " + truth.id() + " declares unknown fault.type "
                            + truth.fault().type() + "; known: " + KNOWN_FAULT_TYPES);
        }
        if (!KNOWN_ROOT_CAUSES.contains(expected.rootCause())) {
            throw new IllegalStateException(
                    "scenario " + truth.id() + " declares unknown rootCause "
                            + expected.rootCause() + "; known: " + KNOWN_ROOT_CAUSES);
        }
        if (expected.advisory()) {
            if (expected.remediation() != null) {
                throw new IllegalStateException(
                        "scenario " + truth.id()
                                + " is advisory but still defines a remediation block");
            }
            if (expected.recommendedRunbook() != null
                    && !expected.recommendedRunbook().isEmpty()) {
                throw new IllegalStateException(
                        "scenario " + truth.id()
                                + " is advisory but recommends a runbook ("
                                + expected.recommendedRunbook() + ")");
            }
        } else {
            if (expected.remediation() == null) {
                throw new IllegalStateException(
                        "scenario " + truth.id()
                                + " is not advisory and must declare a remediation block");
            }
            if (expected.recommendedRunbook() == null
                    || expected.recommendedRunbook().isEmpty()) {
                throw new IllegalStateException(
                        "scenario " + truth.id()
                                + " is not advisory and must declare a recommendedRunbook");
            }
        }
    }

    static List<String> knownFaultTypes() {
        return List.copyOf(KNOWN_FAULT_TYPES);
    }

    static List<String> knownRootCauses() {
        return List.copyOf(KNOWN_ROOT_CAUSES);
    }
}