package io.paysre.control.observability;

import java.util.Arrays;

public enum LogEvent {
    CHANNEL_FAULT_RULE_REPLACED,
    CHANNEL_FINAL_STATE_PERSISTED,
    PAYMENT_STATE_CHANGED,
    ALERT_INGESTED,
    INCIDENT_CREATED,
    INCIDENT_ALERT_AGGREGATED,
    INCIDENT_TOOL_EXECUTED,
    INCIDENT_TOOL_FAILED,
    INCIDENT_INVESTIGATION_COMPLETED,
    INCIDENT_INVESTIGATION_ESCALATED;

    static LogEvent fromValue(String value) {
        if (value == null) {
            return null;
        }
        return Arrays.stream(values())
                .filter(event -> event.name().equals(value))
                .findFirst()
                .orElse(null);
    }
}
