package io.paysre.e2e;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

record ScenarioGroundTruth(
        String id,
        long seed,
        Fault fault,
        Traffic traffic,
        Expected expected) {

    ScenarioGroundTruth {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(fault, "fault");
        Objects.requireNonNull(traffic, "traffic");
        Objects.requireNonNull(expected, "expected");
    }

    static ScenarioGroundTruth load(String classpathResource) {
        try (InputStream input = ScenarioGroundTruth.class.getResourceAsStream(classpathResource)) {
            if (input == null) {
                throw new IllegalArgumentException(
                        "scenario resource does not exist: " + classpathResource);
            }
            var yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
            Map<String, Object> root = yaml.load(input);
            return new ScenarioGroundTruth(
                    string(root, "id"),
                    number(root, "seed").longValueExact(),
                    fault(map(root, "fault")),
                    traffic(map(root, "traffic")),
                    expected(map(root, "expected")));
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("cannot close scenario resource", exception);
        }
    }

    private static Fault fault(Map<String, Object> values) {
        return new Fault(
                string(values, "channel"),
                string(values, "type"),
                number(values, "probability"),
                Duration.parse(string(values, "activeFor")));
    }

    private static Traffic traffic(Map<String, Object> values) {
        return new Traffic(
                number(values, "payments").intValueExact(),
                number(values, "amount"),
                string(values, "currency"));
    }

    private static Expected expected(Map<String, Object> values) {
        Object required = values.get("requiredEvidenceTypes");
        if (!(required instanceof List<?> items)) {
            throw new IllegalArgumentException("requiredEvidenceTypes must be a list");
        }
        var remediation = map(values, "remediation");
        return new Expected(
                string(values, "rootCause"),
                number(values, "minimumEvidenceCount").intValueExact(),
                items.stream().map(String::valueOf).toList(),
                string(values, "recommendedRunbook"),
                booleanValue(values, "requiresHumanReview"),
                new Remediation(
                        string(remediation, "runbookExecutionStatus"),
                        string(remediation, "finalPaymentStatus")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> values, String name) {
        Object value = values.get(name);
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalArgumentException(name + " must be an object");
        }
        return (Map<String, Object>) value;
    }

    private static String string(Map<String, Object> values, String name) {
        Object value = values.get(name);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.toString();
    }

    private static BigDecimal number(Map<String, Object> values, String name) {
        Object value = values.get(name);
        if (value == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be numeric", exception);
        }
    }

    private static boolean booleanValue(Map<String, Object> values, String name) {
        Object value = values.get(name);
        if (!(value instanceof Boolean result)) {
            throw new IllegalArgumentException(name + " must be boolean");
        }
        return result;
    }

    record Fault(String channel, String type, BigDecimal probability, Duration activeFor) {
    }

    record Traffic(int payments, BigDecimal amount, String currency) {
    }

    record Expected(
            String rootCause,
            int minimumEvidenceCount,
            List<String> requiredEvidenceTypes,
            String recommendedRunbook,
            boolean requiresHumanReview,
            Remediation remediation) {

        Expected {
            requiredEvidenceTypes = List.copyOf(requiredEvidenceTypes);
            Objects.requireNonNull(remediation, "remediation");
        }
    }

    record Remediation(String runbookExecutionStatus, String finalPaymentStatus) {
    }
}
