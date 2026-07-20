package io.paysre.control.investigation.minimax;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

public record MiniMaxToolCall(String id, String name, JsonNode arguments) {

    public MiniMaxToolCall {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(arguments, "arguments");
        arguments = arguments.deepCopy();
    }
}
