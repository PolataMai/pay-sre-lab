package io.paysre.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

class DeploymentConfigurationTest {

    @Test
    void composeDefinesHealthyIsolatedServicesAndPinnedJavaRuntime() throws IOException {
        var root = findRepositoryRoot();
        Map<String, Object> compose;
        try (var input = Files.newInputStream(root.resolve("deploy/compose.yaml"))) {
            compose = new Yaml(new SafeConstructor(new LoaderOptions())).load(input);
        }
        var services = map(compose, "services");

        assertThat(services.keySet()).containsExactlyInAnyOrder(
                "payment-db",
                "control-db",
                "log-volume-init",
                "prometheus",
                "loki",
                "tempo",
                "otel-collector",
                "grafana",
                "channel-simulator",
                "payment-service",
                "sre-control-plane");
        for (String service : java.util.List.of(
                "channel-simulator", "payment-service", "sre-control-plane")) {
            var healthcheck = map(map(services, service), "healthcheck");
            assertThat(healthcheck.get("test").toString())
                    .contains("/actuator/health/readiness");
            assertThat(map(services, service).get("networks").toString())
                    .contains("pay-sre");
        }
        assertThat(map(services, "prometheus").get("image"))
                .isEqualTo("prom/prometheus:v3.12.0");
        assertThat(map(services, "loki").get("image"))
                .isEqualTo("grafana/loki:3.7.2");
        assertThat(map(services, "tempo").get("image"))
                .isEqualTo("grafana/tempo:2.10.5");
        assertThat(map(services, "otel-collector").get("image"))
                .isEqualTo("otel/opentelemetry-collector-contrib:0.156.0");
        assertThat(map(services, "grafana").get("image"))
                .isEqualTo("grafana/grafana:13.1.0");
        for (String service : java.util.List.of(
                "prometheus", "loki", "tempo", "otel-collector", "grafana")) {
            assertThat(map(services, service)).containsKeys("healthcheck", "deploy");
            assertThat(map(map(map(services, service), "deploy"), "resources"))
                    .containsKey("limits");
        }
        for (String service : java.util.List.of(
                "channel-simulator", "payment-service", "sre-control-plane")) {
            var environment = map(map(services, service), "environment");
            assertThat(environment)
                    .containsEntry("OTEL_TRACES_EXPORT_ENABLED", true)
                    .containsEntry(
                            "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT",
                            "http://otel-collector:4318/v1/traces");
            assertThat(environment.get("LOGGING_FILE_NAME").toString())
                    .startsWith("/var/log/paysre/");
            assertThat(map(services, service).get("volumes").toString())
                    .contains("application-logs:/var/log/paysre");
        }
        assertThat(map(compose, "networks")).containsKey("pay-sre");
        assertThat(map(compose, "volumes").keySet())
                .contains(
                        "application-logs",
                        "prometheus-data",
                        "loki-data",
                        "tempo-data",
                        "grafana-data");

        assertObservabilityConfigurations(root);

        var dockerfile = Files.readString(root.resolve("deploy/Dockerfile"));
        var runtimeDockerfile = Files.readString(root.resolve("deploy/Dockerfile.runtime"));
        assertThat(dockerfile)
                .contains("maven:3.9.16-eclipse-temurin-21-alpine")
                .contains("USER paysre");
        assertThat(runtimeDockerfile)
                .contains("eclipse-temurin:21-jre-alpine")
                .contains("USER paysre");
    }

    private void assertObservabilityConfigurations(Path root) throws IOException {
        var prometheus = Files.readString(
                root.resolve("observability/prometheus/prometheus.yml"));
        var alerts = Files.readString(
                root.resolve("observability/prometheus/alerts.yml"));
        var collector = Files.readString(
                root.resolve("observability/otel-collector/config.yaml"));
        var loki = Files.readString(root.resolve("observability/loki/config.yaml"));
        var tempo = Files.readString(root.resolve("observability/tempo/config.yaml"));
        var datasources = Files.readString(root.resolve(
                "observability/grafana/provisioning/datasources/datasources.yaml"));
        var dashboard = Files.readString(
                root.resolve("observability/grafana/dashboards/payment-incident.json"));

        assertThat(prometheus)
                .contains("payment-service:8080", "channel-simulator:8081", "sre-control-plane:8082")
                .doesNotContain("paymentId", "payment.id");
        assertThat(alerts)
                .contains("PaymentUnknownHigh", "payment_unknown_current", "channel");
        assertThat(collector)
                .contains("filelog/paysre", "/var/log/paysre/*.json", "memory_limiter")
                .contains("http://loki:3100/otlp", "http://tempo:4318")
                .doesNotContain("/var/lib/docker/containers");
        assertThat(loki).contains("allow_structured_metadata: true", "retention_period: 2h");
        assertThat(tempo).contains("block_retention: 2h", "0.0.0.0:4318");
        assertThat(datasources).contains("uid: prometheus", "uid: loki", "uid: tempo");
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().readTree(dashboard)
                        .path("panels"))
                .hasSizeGreaterThanOrEqualTo(5);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Map<String, Object> values, String name) {
        return (Map<String, Object>) values.get(name);
    }

    private Path findRepositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                    && Files.isDirectory(current.resolve("deploy"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("cannot locate pay-sre-lab repository root");
    }
}
