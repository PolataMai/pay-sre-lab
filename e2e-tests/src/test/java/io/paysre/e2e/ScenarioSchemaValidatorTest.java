package io.paysre.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ScenarioSchemaValidatorTest {

    @Test
    void everyShippedScenarioPassesValidation() {
        for (var resource : new String[]{
                "/fault-scenarios/channel-timeout-but-success-v1.yaml",
                "/fault-scenarios/channel-timeout-but-failed-v1.yaml",
                "/fault-scenarios/channel-decline-spike-v1.yaml",
                "/fault-scenarios/channel-code-mapping-error-v1.yaml",
                "/fault-scenarios/channel-callback-lost-v1.yaml",
                "/fault-scenarios/routing-misconfigured-v1.yaml"}) {
            new ScenarioSchemaValidator(ScenarioGroundTruth.load(resource)).validate();
        }
    }

    @Test
    void exposesTheKnownFaultAndRootCauseCatalog() {
        assertThat(ScenarioSchemaValidator.knownFaultTypes())
                .contains("NONE", "TIMEOUT_BUT_SUCCESS", "TIMEOUT_BUT_FAILED",
                        "DECLINE_ALL", "CALLBACK_LOST", "CALLBACK_DUPLICATED");
        assertThat(ScenarioSchemaValidator.knownRootCauses())
                .contains("CHANNEL_TIMEOUT_RESPONSE_LOST", "CHANNEL_DECLINE_SPIKE",
                        "CHANNEL_CODE_MAPPING_ERROR", "CHANNEL_CALLBACK_LOST",
                        "ROUTING_MISCONFIGURED");
    }

    @Test
    void loaderPassesExistingScenariosThrough() {
        // Smoke test: just loading the YAML must succeed.
        ScenarioGroundTruth.load("/fault-scenarios/channel-decline-spike-v1.yaml");
    }

    @Test
    void blankAdvisoryRunbookIsAccepted() {
        // Advisory scenarios with empty recommendedRunbook must validate.
        new ScenarioSchemaValidator(ScenarioGroundTruth.load(
                "/fault-scenarios/channel-callback-lost-v1.yaml")).validate();
    }

    @Test
    void reloaderHelperReflectsScenarios() {
        // The validator relies on the loader; this test makes the
        // contract explicit and surfaces a useful failure if either
        // side drifts.
        var scenario = ScenarioGroundTruth.load(
                "/fault-scenarios/routing-misconfigured-v1.yaml");
        assertThat(scenario.expected().advisory()).isTrue();
        assertThat(scenario.expected().remediation()).isNull();
    }

    @Test
    void minimumEvidenceCountMustCoverRequiredTypes() {
        var truth = new ScenarioGroundTruth(
                "ad-hoc",
                1L,
                new ScenarioGroundTruth.Fault("CHANNEL_A", "NONE",
                        new java.math.BigDecimal("1.00"),
                        java.time.Duration.ofMinutes(2)),
                new ScenarioGroundTruth.Traffic(5, new java.math.BigDecimal("10.00"), "CNY"),
                new ScenarioGroundTruth.Expected(
                        "ROUTING_MISCONFIGURED",
                        0, // intentionally too small
                        java.util.List.of("INCIDENT_IMPACT", "PAYMENT_TIMELINE",
                                "SERVICE_METRICS"),
                        "",
                        true,
                        true,
                        null));

        assertThatThrownBy(() -> new ScenarioSchemaValidator(truth).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("minimum evidence");
    }
}