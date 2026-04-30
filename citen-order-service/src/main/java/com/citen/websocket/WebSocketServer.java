package com.citen.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

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
@ServerEndpoint("/ws/order/{labId}")
public class WebSocketServer {

    private static final Logger LOG = LoggerFactory.getLogger(WebSocketServer.class);

    private static final ConcurrentHashMap<Long, CopyOnWriteArraySet<Session>> SHOP_SESSION_MAP = new ConcurrentHashMap<>();

    @OnOpen
    public void onOpen(Session session, @PathParam("labId") Long labId) {
        SHOP_SESSION_MAP.computeIfAbsent(labId, key -> new CopyOnWriteArraySet<>()).add(session);
        LOG.info("websocket connected, labId={}, sessionId={}", labId, session.getId());
    }

    @OnMessage
    public void onMessage(String message, Session session, @PathParam("labId") Long labId) {
        LOG.info("websocket message received, labId={}, sessionId={}, message={}", labId, session.getId(), message);
        sendText(session, "server received: " + message);
    }

    @OnClose
    public void onClose(Session session, @PathParam("labId") Long labId) {
        removeSession(labId, session);
        LOG.info("websocket closed, labId={}, sessionId={}", labId, session.getId());
    }

    @OnError
    public void onError(Session session, Throwable error, @PathParam("labId") Long labId) {
        removeSession(labId, session);
        LOG.error("websocket error, labId={}, sessionId={}", labId, session == null ? null : session.getId(), error);
    }

    public static void sendToShop(Long labId, String message) {
        Set<Session> sessions = SHOP_SESSION_MAP.get(labId);
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        for (Session session : sessions) {
            sendText(session, message);
        }
    }

    private static void sendText(Session session, String message) {
        if (session == null || !session.isOpen()) {
            return;
        }
        try {
            session.getBasicRemote().sendText(message);
        } catch (IOException e) {
            LOG.error("websocket push failed, sessionId={}, message={}", session.getId(), message, e);
        }
    }

    private static void removeSession(Long labId, Session session) {
        if (labId == null || session == null) {
            return;
        }
        CopyOnWriteArraySet<Session> sessions = SHOP_SESSION_MAP.get(labId);
        if (sessions == null) {
            return;
        }
        sessions.remove(session);
        if (sessions.isEmpty()) {
            SHOP_SESSION_MAP.remove(labId);
        }
    }
}
