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
        assertThat(map(compose, "networks")).containsKey("pay-sre");

        var dockerfile = Files.readString(root.resolve("deploy/Dockerfile"));
        var runtimeDockerfile = Files.readString(root.resolve("deploy/Dockerfile.runtime"));
        assertThat(dockerfile)
                .contains("maven:3.9.16-eclipse-temurin-21-alpine")
                .contains("USER paysre");
        assertThat(runtimeDockerfile)
                .contains("eclipse-temurin:21-jre-alpine")
                .contains("USER paysre");
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
