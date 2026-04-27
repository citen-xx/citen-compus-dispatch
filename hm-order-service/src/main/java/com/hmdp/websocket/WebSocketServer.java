package com.hmdp.websocket;

import lombok.extern.slf4j.Slf4j;
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

@Slf4j
@Component
@ServerEndpoint("/ws/order/{shopId}")
public class WebSocketServer {

    private static final ConcurrentHashMap<Long, CopyOnWriteArraySet<Session>> SHOP_SESSION_MAP = new ConcurrentHashMap<>();

    @OnOpen
    public void onOpen(Session session, @PathParam("shopId") Long shopId) {
        SHOP_SESSION_MAP.computeIfAbsent(shopId, key -> new CopyOnWriteArraySet<>()).add(session);
        log.info("websocket connected, shopId={}, sessionId={}", shopId, session.getId());
    }

    @OnMessage
    public void onMessage(String message, Session session, @PathParam("shopId") Long shopId) {
        log.info("websocket message received, shopId={}, sessionId={}, message={}", shopId, session.getId(), message);
        sendText(session, "server received: " + message);
    }

    @OnClose
    public void onClose(Session session, @PathParam("shopId") Long shopId) {
        removeSession(shopId, session);
        log.info("websocket closed, shopId={}, sessionId={}", shopId, session.getId());
    }

    @OnError
    public void onError(Session session, Throwable error, @PathParam("shopId") Long shopId) {
        removeSession(shopId, session);
        log.error("websocket error, shopId={}, sessionId={}", shopId, session == null ? null : session.getId(), error);
    }

    public static void sendToShop(Long shopId, String message) {
        Set<Session> sessions = SHOP_SESSION_MAP.get(shopId);
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
            log.error("websocket push failed, sessionId={}, message={}", session.getId(), message, e);
        }
    }

    private static void removeSession(Long shopId, Session session) {
        if (shopId == null || session == null) {
            return;
        }
        CopyOnWriteArraySet<Session> sessions = SHOP_SESSION_MAP.get(shopId);
        if (sessions == null) {
            return;
        }
        sessions.remove(session);
        if (sessions.isEmpty()) {
            SHOP_SESSION_MAP.remove(shopId);
        }
    }
}
