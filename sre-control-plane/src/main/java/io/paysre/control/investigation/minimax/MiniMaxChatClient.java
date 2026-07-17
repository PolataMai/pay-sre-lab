package io.paysre.control.investigation.minimax;

import com.fasterxml.jackson.databind.node.ArrayNode;

@FunctionalInterface
public interface MiniMaxChatClient {

    MiniMaxAssistantTurn complete(String systemPrompt, String userPayload, ArrayNode tools);
}
