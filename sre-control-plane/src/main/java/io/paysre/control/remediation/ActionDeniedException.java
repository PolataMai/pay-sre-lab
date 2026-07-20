package io.paysre.control.remediation;

import java.util.Objects;

public final class ActionDeniedException extends RuntimeException {

    private final String code;

    public ActionDeniedException(String code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    public String code() {
        return code;
    }
}
