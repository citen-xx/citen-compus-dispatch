package com.citen.websocket;

import javax.websocket.HandshakeResponse;
import javax.websocket.server.HandshakeRequest;
import javax.websocket.server.ServerEndpointConfig;
import java.util.List;
import java.util.Map;

public class WebSocketHandshakeConfigurator extends ServerEndpointConfig.Configurator {

    static final String USER_ID_PROPERTY = "userId";

    @Override
    public void modifyHandshake(ServerEndpointConfig config,
                                HandshakeRequest request,
                                HandshakeResponse response) {
        String userId = firstHeaderIgnoreCase(request.getHeaders(), "x-user-id");
        if (userId != null) {
            config.getUserProperties().put(USER_ID_PROPERTY, userId);
        }
    }

    private String firstHeaderIgnoreCase(Map<String, List<String>> headers, String expectedName) {
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(expectedName)
                    && entry.getValue() != null && !entry.getValue().isEmpty()) {
                return entry.getValue().get(0);
            }
        }
        return null;
    }
}
