package io.paysre.control.remediation;

import java.util.Objects;

public final class RemediationBackendException extends RuntimeException {

    private final String code;

    public RemediationBackendException(String code, String message) {
        super(code + ": " + message);
        this.code = Objects.requireNonNull(code, "code");
    }

    public String code() {
        return code;
    }
}
