package io.paysre.control.investigation.minimax;

import java.util.List;

public record MiniMaxAssistantTurn(String content, List<MiniMaxToolCall> toolCalls) {

    public MiniMaxAssistantTurn {
        content = content == null ? "" : content;
        toolCalls = List.copyOf(toolCalls);
    }
}
