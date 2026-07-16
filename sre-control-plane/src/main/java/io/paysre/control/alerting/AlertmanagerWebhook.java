package io.paysre.control.alerting;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record AlertmanagerWebhook(String status, List<Alert> alerts) {

    public AlertmanagerWebhook {
        alerts = alerts == null ? List.of() : List.copyOf(alerts);
    }

    public record Alert(
            String status,
            Map<String, String> labels,
            Map<String, String> annotations,
            Instant startsAt,
            String fingerprint) {

        public Alert {
            labels = labels == null ? Map.of() : Map.copyOf(labels);
            annotations = annotations == null ? Map.of() : Map.copyOf(annotations);
        }
    }
}
