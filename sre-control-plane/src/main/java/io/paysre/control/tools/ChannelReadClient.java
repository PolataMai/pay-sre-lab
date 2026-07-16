package io.paysre.control.tools;

import com.fasterxml.jackson.databind.JsonNode;

public interface ChannelReadClient {

    JsonNode finalState(String paymentId);
}
