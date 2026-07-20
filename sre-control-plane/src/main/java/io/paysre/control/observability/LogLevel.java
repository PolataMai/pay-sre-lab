package io.paysre.control.observability;

import java.util.Arrays;

public enum LogLevel {
    TRACE,
    DEBUG,
    INFO,
    WARN,
    ERROR,
    FATAL,
    UNKNOWN;

    String minimumRegex() {
        return switch (this) {
            case TRACE -> "TRACE|DEBUG|INFO|WARN|ERROR|FATAL";
            case DEBUG -> "DEBUG|INFO|WARN|ERROR|FATAL";
            case INFO -> "INFO|WARN|ERROR|FATAL";
            case WARN -> "WARN|ERROR|FATAL";
            case ERROR -> "ERROR|FATAL";
            case FATAL -> "FATAL";
            case UNKNOWN -> throw new IllegalArgumentException(
                    "UNKNOWN is not a valid minimum log level");
        };
    }

    static LogLevel fromValue(String value) {
        if (value == null) {
            return UNKNOWN;
        }
        return Arrays.stream(values())
                .filter(level -> level.name().equalsIgnoreCase(value))
                .findFirst()
                .orElse(UNKNOWN);
    }
}
