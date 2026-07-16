package io.paysre.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class ChannelTimeoutButSuccessE2ETest {

    private static final Path ROOT = findRepositoryRoot();
    private static final Network NETWORK = Network.newNetwork();

    @Container
    private static final PostgreSQLContainer PAYMENT_DATABASE = postgres("payment-db");

    @Container
    private static final PostgreSQLContainer CONTROL_DATABASE = postgres("control-db");

    @Container
    private static final GenericContainer<?> CHANNEL = new GenericContainer<>(serviceImage(
                    "paysre-channel-e2e:local",
                    ROOT.resolve("payment-system/channel-simulator/target/"
                            + "channel-simulator-0.1.0-SNAPSHOT.jar")))
            .withNetwork(NETWORK)
            .withNetworkAliases("channel-simulator")
            .withExposedPorts(8081)
            .withEnv("CHANNEL_SIMULATOR_PORT", "8081")
            .waitingFor(readiness(8081));

    @Container
    private static final GenericContainer<?> PAYMENT = new GenericContainer<>(serviceImage(
                    "paysre-payment-e2e:local",
                    ROOT.resolve("payment-system/payment-service/target/"
                            + "payment-service-0.1.0-SNAPSHOT.jar")))
            .withNetwork(NETWORK)
            .withNetworkAliases("payment-service")
            .withExposedPorts(8080)
            .withEnv("PAYMENT_SERVICE_PORT", "8080")
            .withEnv("PAYMENT_DB_URL", "jdbc:postgresql://payment-db:5432/paysre")
            .withEnv("PAYMENT_DB_USERNAME", "paysre")
            .withEnv("PAYMENT_DB_PASSWORD", "paysre")
            .withEnv("CHANNEL_BASE_URL", "http://channel-simulator:8081")
            .dependsOn(PAYMENT_DATABASE, CHANNEL)
            .waitingFor(readiness(8080));

    @Container
    private static final GenericContainer<?> CONTROL = new GenericContainer<>(serviceImage(
                    "paysre-control-e2e:local",
                    ROOT.resolve("sre-control-plane/target/"
                            + "sre-control-plane-0.1.0-SNAPSHOT.jar")))
            .withNetwork(NETWORK)
            .withNetworkAliases("sre-control-plane")
            .withExposedPorts(8082)
            .withEnv("CONTROL_PLANE_PORT", "8082")
            .withEnv("CONTROL_DB_URL", "jdbc:postgresql://control-db:5432/paysre")
            .withEnv("CONTROL_DB_USERNAME", "paysre")
            .withEnv("CONTROL_DB_PASSWORD", "paysre")
            .withEnv("PAYMENT_SERVICE_BASE_URL", "http://payment-service:8080")
            .withEnv("CHANNEL_SIMULATOR_BASE_URL", "http://channel-simulator:8081")
            .dependsOn(CONTROL_DATABASE, PAYMENT, CHANNEL)
            .waitingFor(readiness(8082));

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Test
    void identifiesAChannelSuccessWhoseSynchronousResponseWasLost() throws Exception {
        var groundTruth = ScenarioGroundTruth.load(
                "/fault-scenarios/channel-timeout-but-success-v1.yaml");
        Instant startedAt = Instant.now();
        installFault(groundTruth, startedAt);
        var paymentIds = createUnknownPayments(groundTruth);
        String incidentId = createIncident(groundTruth, startedAt);

        var conclusion = postJson(
                controlUri("/api/incidents/" + incidentId + "/investigations"),
                objectMapper.createObjectNode()
                        .put("representativePaymentId", paymentIds.get(0))
                        .put("channel", groundTruth.fault().channel())
                        .put("from", startedAt.minusSeconds(300).toString())
                        .put("to", startedAt.plusSeconds(300).toString()),
                200);
        var evidence = getJson(
                controlUri("/api/incidents/" + incidentId + "/evidence"), 200);
        Set<String> evidenceTypes = StreamSupport.stream(evidence.spliterator(), false)
                .map(item -> item.path("evidenceType").asText())
                .collect(Collectors.toSet());
        var actual = new ScenarioEvaluator.ActualInvestigation(
                conclusion.path("rootCause").asText(),
                evidenceTypes,
                evidence.size(),
                conclusion.path("recommendedRunbook").asText(),
                conclusion.path("requiresHumanReview").asBoolean());
        var score = new ScenarioEvaluator().evaluate(groundTruth.expected(), actual);

        assertThat(paymentIds).hasSize(groundTruth.traffic().payments());
        assertThat(conclusion.path("affectedPaymentCount").asLong())
                .isEqualTo(groundTruth.traffic().payments());
        assertThat(score.passed()).isTrue();
    }

    private void installFault(ScenarioGroundTruth groundTruth, Instant now) throws Exception {
        var fault = objectMapper.createObjectNode()
                .put("channel", groundTruth.fault().channel())
                .put("type", groundTruth.fault().type())
                .put("probability", groundTruth.fault().probability())
                .put("activeFrom", now.minusSeconds(30).toString())
                .put("activeUntil", now.plus(groundTruth.fault().activeFor()).toString())
                .put("randomSeed", groundTruth.seed());
        putJson(channelUri("/api/admin/faults/" + groundTruth.fault().channel()), fault, 200);
    }

    private java.util.List<String> createUnknownPayments(ScenarioGroundTruth groundTruth)
            throws Exception {
        var paymentIds = new ArrayList<String>();
        for (int index = 0; index < groundTruth.traffic().payments(); index++) {
            var command = objectMapper.createObjectNode()
                    .put("orderId", "ORDER-" + index)
                    .put("merchantId", "MERCHANT-001")
                    .put("idempotencyKey", "SCENARIO-" + groundTruth.id() + "-" + index);
            command.putObject("money")
                    .put("amount", groundTruth.traffic().amount())
                    .put("currency", groundTruth.traffic().currency());
            var payment = postJson(paymentUri("/api/payments"), command, 201);
            assertThat(payment.path("status").asText()).isEqualTo("UNKNOWN");
            paymentIds.add(payment.path("paymentId").asText());
        }
        return paymentIds;
    }

    private String createIncident(ScenarioGroundTruth groundTruth, Instant startedAt)
            throws Exception {
        var alert = objectMapper.createObjectNode()
                .put("status", "firing");
        var item = alert.putArray("alerts").addObject()
                .put("status", "firing")
                .put("startsAt", startedAt.toString())
                .put("fingerprint", groundTruth.id() + "-alert");
        item.putObject("labels")
                .put("service", "payment-service")
                .put("alertname", "PaymentUnknownHigh")
                .put("channel", groundTruth.fault().channel())
                .put("severity", "high");
        item.putObject("annotations")
                .put("observedValue", groundTruth.traffic().payments())
                .put("threshold", 1);
        var incidents = postJson(
                controlUri("/api/alerts/alertmanager"), alert, 200);
        return incidents.get(0).path("incidentId").asText();
    }

    private JsonNode getJson(URI uri, int expectedStatus) throws Exception {
        return exchange(HttpRequest.newBuilder(uri).GET().build(), expectedStatus);
    }

    private JsonNode postJson(URI uri, JsonNode body, int expectedStatus) throws Exception {
        return exchange(jsonRequest(uri, "POST", body), expectedStatus);
    }

    private void putJson(URI uri, JsonNode body, int expectedStatus) throws Exception {
        exchange(jsonRequest(uri, "PUT", body), expectedStatus);
    }

    private HttpRequest jsonRequest(URI uri, String method, JsonNode body)
            throws IOException {
        return HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(body)))
                .build();
    }

    private JsonNode exchange(HttpRequest request, int expectedStatus) throws Exception {
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode())
                .withFailMessage("%s returned %s: %s",
                        request.uri(), response.statusCode(), response.body())
                .isEqualTo(expectedStatus);
        return response.body().isBlank()
                ? objectMapper.createObjectNode()
                : objectMapper.readTree(response.body());
    }

    private URI channelUri(String path) {
        return URI.create("http://" + CHANNEL.getHost() + ":" + CHANNEL.getMappedPort(8081) + path);
    }

    private URI paymentUri(String path) {
        return URI.create("http://" + PAYMENT.getHost() + ":" + PAYMENT.getMappedPort(8080) + path);
    }

    private URI controlUri(String path) {
        return URI.create("http://" + CONTROL.getHost() + ":" + CONTROL.getMappedPort(8082) + path);
    }

    private static PostgreSQLContainer postgres(String alias) {
        return new PostgreSQLContainer("postgres:17-alpine")
                .withDatabaseName("paysre")
                .withUsername("paysre")
                .withPassword("paysre")
                .withNetwork(NETWORK)
                .withNetworkAliases(alias);
    }

    private static ImageFromDockerfile serviceImage(String name, Path jar) {
        return new ImageFromDockerfile(name, true)
                .withFileFromPath("Dockerfile", ROOT.resolve("deploy/Dockerfile.runtime"))
                .withFileFromPath("app.jar", jar);
    }

    private static org.testcontainers.containers.wait.strategy.WaitStrategy readiness(int port) {
        return Wait.forHttp("/actuator/health/readiness")
                .forPort(port)
                .forStatusCode(200)
                .withStartupTimeout(Duration.ofMinutes(3));
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                    && Files.isDirectory(current.resolve("payment-system"))
                    && Files.isDirectory(current.resolve("sre-control-plane"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("cannot locate pay-sre-lab repository root");
    }
}
