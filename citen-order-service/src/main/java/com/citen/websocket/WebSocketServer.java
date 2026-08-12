package com.citen.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.websocket.CloseReason;
import javax.websocket.EndpointConfig;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

@Component
@ServerEndpoint(value = "/ws/order/{labId}", configurator = WebSocketHandshakeConfigurator.class)
public class WebSocketServer {

    private static final Logger LOG = LoggerFactory.getLogger(WebSocketServer.class);
    private static final ConcurrentHashMap<Long, CopyOnWriteArraySet<Session>> LAB_SESSIONS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, CopyOnWriteArraySet<Session>> USER_SESSIONS = new ConcurrentHashMap<>();

    @OnOpen
    public void onOpen(Session session, EndpointConfig config, @PathParam("labId") Long labId) throws IOException {
        Long userId = parseUserId(config.getUserProperties().get(WebSocketHandshakeConfigurator.USER_ID_PROPERTY));
        if (userId == null) {
            session.close(new CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY, "unauthorized"));
            return;
        }
        session.getUserProperties().put(WebSocketHandshakeConfigurator.USER_ID_PROPERTY, userId);
        LAB_SESSIONS.computeIfAbsent(labId, key -> new CopyOnWriteArraySet<>()).add(session);
        USER_SESSIONS.computeIfAbsent(userId, key -> new CopyOnWriteArraySet<>()).add(session);
        LOG.info("websocket connected, userId={}, labId={}, sessionId={}", userId, labId, session.getId());
    }

    @OnMessage
    public void onMessage(String message, Session session, @PathParam("labId") Long labId) {
        LOG.debug("websocket message received, labId={}, sessionId={}, message={}", labId, session.getId(), message);
    }

    @OnClose
    public void onClose(Session session, @PathParam("labId") Long labId) {
        removeSession(labId, session);
    }

    @OnError
    public void onError(Session session, Throwable error, @PathParam("labId") Long labId) {
        removeSession(labId, session);
        LOG.error("websocket error, labId={}, sessionId={}",
                labId, session == null ? null : session.getId(), error);
    }

    public static void sendToUser(Long userId, String message) {
        send(USER_SESSIONS.get(userId), message);
    }

    public static void sendToLab(Long labId, String message) {
        send(LAB_SESSIONS.get(labId), message);
    }

    private static void send(Set<Session> sessions, String message) {
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        for (Session session : sessions) {
            if (session == null || !session.isOpen()) {
                continue;
            }
            session.getAsyncRemote().sendText(message, result -> {
                if (!result.isOK()) {
                    LOG.error("websocket push failed, sessionId={}", session.getId(), result.getException());
                }
            });
        }
    }

    private static void removeSession(Long labId, Session session) {
        if (session == null) {
            return;
        }
        removeFromMap(LAB_SESSIONS, labId, session);
        removeFromMap(USER_SESSIONS, parseUserId(
                session.getUserProperties().get(WebSocketHandshakeConfigurator.USER_ID_PROPERTY)), session);
    }

    private static void removeFromMap(ConcurrentHashMap<Long, CopyOnWriteArraySet<Session>> sessionMap,
                                      Long key, Session session) {
        if (key == null) {
            return;
        }
        CopyOnWriteArraySet<Session> sessions = sessionMap.get(key);
        if (sessions == null) {
            return;
        }
        sessions.remove(session);
        if (sessions.isEmpty()) {
            sessionMap.remove(key, sessions);
        }
    }

    private static Long parseUserId(Object value) {
        try {
            return value == null ? null : Long.valueOf(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
