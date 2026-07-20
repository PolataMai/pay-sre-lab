package io.paysre.control.investigation.minimax;

import java.util.Objects;

public final class MiniMaxModelException extends RuntimeException {

    private final String code;

    public MiniMaxModelException(String code, String message) {
        super(code + ": " + message);
        this.code = Objects.requireNonNull(code, "code");
    }

    public String code() {
        return code;
    }
}
