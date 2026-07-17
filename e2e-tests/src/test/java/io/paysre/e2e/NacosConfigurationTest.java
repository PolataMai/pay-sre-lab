package io.paysre.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Guards the Nacos integration invariant: repository files may only contain
 * environment placeholders and templates, never a real server address,
 * namespace or credential.
 */
class NacosConfigurationTest {

    private static final Map<String, String> SERVICE_RESOURCES = Map.of(
            "payment-service", "payment-system/payment-service/src/main/resources",
            "channel-simulator", "payment-system/channel-simulator/src/main/resources",
            "sre-control-plane", "sre-control-plane/src/main/resources");

    @Test
    void nacosProfileFilesOnlyReferenceEnvironmentPlaceholders() throws IOException {
        var root = findRepositoryRoot();
        for (var entry : SERVICE_RESOURCES.entrySet()) {
            var profileFile = root.resolve(entry.getValue()).resolve("application-nacos.yml");
            assertThat(profileFile).as("nacos profile config for " + entry.getKey()).exists();
            var config = loadYaml(profileFile);

            var importValue = map(map(config, "spring"), "config").get("import").toString();
            assertThat(importValue)
                    .startsWith("nacos:")
                    .contains("${NACOS_DATA_ID:" + entry.getKey() + ".yml}")
                    .contains("group=${NACOS_GROUP:pay-sre-lab}");

            var nacos = map(map(map(config, "spring"), "cloud"), "nacos");
            assertThat(nacos.get("server-addr"))
                    .as("server address must be an env placeholder without default")
                    .isEqualTo("${NACOS_SERVER_ADDR}");
            assertThat(nacos.get("username")).isEqualTo("${NACOS_USERNAME:}");
            assertThat(nacos.get("password")).isEqualTo("${NACOS_PASSWORD:}");
            assertThat(map(nacos, "config").get("namespace"))
                    .isEqualTo("${NACOS_NAMESPACE:}");
        }
    }

    @Test
    void mainConfigurationsKeepTheStarterDormantWithoutTheNacosProfile()
            throws IOException {
        var root = findRepositoryRoot();
        for (var resources : SERVICE_RESOURCES.values()) {
            var config = loadYaml(root.resolve(resources).resolve("application.yml"));
            var importCheck = map(map(map(map(map(
                    config, "spring"), "cloud"), "nacos"), "config"), "import-check");
            assertThat(importCheck.get("enabled"))
                    .as("import-check must be disabled in " + resources)
                    .isEqualTo(false);
        }
    }

    @Test
    void envTemplateAndComposeCarryOnlyPlaceholdersForNacos() throws IOException {
        var root = findRepositoryRoot();

        var envExample = Files.readString(root.resolve("deploy/env.example"));
        assertThat(envExample).contains("SPRING_PROFILES_ACTIVE=");
        for (String line : envExample.lines().toList()) {
            if (!line.startsWith("NACOS_")) {
                continue;
            }
            var value = line.substring(line.indexOf('=') + 1).trim();
            assertThat(value)
                    .as("env.example NACOS values must stay templates: " + line)
                    .satisfiesAnyOf(
                            v -> assertThat(v).isEmpty(),
                            v -> assertThat(v).matches("<[^<>]+>"),
                            v -> assertThat(v).isEqualTo("pay-sre-lab"));
        }
        assertThat(envExample)
                .contains("NACOS_SERVER_ADDR=")
                .contains("NACOS_USERNAME=")
                .contains("NACOS_PASSWORD=");

        Map<String, Object> compose = loadYaml(root.resolve("deploy/compose.yaml"));
        var services = map(compose, "services");
        for (String service : List.of(
                "payment-service", "channel-simulator", "sre-control-plane")) {
            var environment = map(map(services, service), "environment");
            assertThat(environment.get("SPRING_PROFILES_ACTIVE"))
                    .isEqualTo("${SPRING_PROFILES_ACTIVE:-}");
            assertThat(environment.get("NACOS_SERVER_ADDR"))
                    .isEqualTo("${NACOS_SERVER_ADDR:-}");
            assertThat(environment.get("NACOS_NAMESPACE"))
                    .isEqualTo("${NACOS_NAMESPACE:-}");
            assertThat(environment.get("NACOS_GROUP"))
                    .isEqualTo("${NACOS_GROUP:-pay-sre-lab}");
            assertThat(environment.get("NACOS_USERNAME"))
                    .isEqualTo("${NACOS_USERNAME:-}");
            assertThat(environment.get("NACOS_PASSWORD"))
                    .isEqualTo("${NACOS_PASSWORD:-}");
        }
    }

    @Test
    void localEnvFilesStayOutOfGit() throws IOException {
        var root = findRepositoryRoot();
        var gitignore = Files.readString(root.resolve(".gitignore"));
        assertThat(gitignore).contains("deploy/.env").contains(".env");
    }

    private Map<String, Object> loadYaml(Path file) throws IOException {
        try (var input = Files.newInputStream(file)) {
            return new Yaml(new SafeConstructor(new LoaderOptions())).load(input);
        }
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
