package io.paysre.control.remediation;

import java.util.Objects;

public final class RunbookExecutionException extends RuntimeException {

    private final String code;

    public RunbookExecutionException(String code, String message) {
        super(code + ": " + message);
        this.code = Objects.requireNonNull(code, "code");
    }

    public String code() {
        return code;
    }
}
