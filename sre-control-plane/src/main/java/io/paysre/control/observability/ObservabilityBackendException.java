package io.paysre.control.observability;

import java.util.Objects;

public final class ObservabilityBackendException extends RuntimeException {

    public enum Code {
        BACKEND_UNAVAILABLE,
        MALFORMED_RESPONSE,
        NOT_FOUND,
        LIMIT_EXCEEDED
    }

    private final Code code;

    public ObservabilityBackendException(Code code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    public ObservabilityBackendException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    public Code code() {
        return code;
    }
}
