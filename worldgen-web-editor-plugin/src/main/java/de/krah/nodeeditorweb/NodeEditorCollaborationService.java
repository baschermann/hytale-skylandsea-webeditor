package de.krah.nodeeditorweb;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live collaboration for the node editor: SSE fan-out + POST publish. Each browser tab has a
 * {@code sessionId}; profile (name, color) and messages are relayed to all other subscribers.
 */
@Service
public class NodeEditorCollaborationService {

    private static final class Session {
        final String sessionId;
        final SseEmitter emitter;
        volatile String name = "Editor";
        volatile String color = "#6b7280";

        Session(String sessionId, SseEmitter emitter) {
            this.sessionId = sessionId;
            this.emitter = emitter;
        }
    }

    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();

    public static boolean isValidSessionId(String id) {
        if (id == null || id.isEmpty()) {
            return false;
        }
        if (id.length() > 64) {
            return false;
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            boolean ok = (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F') || (c >= '0' && c <= '9') || c == '-';
            if (!ok) {
                return false;
            }
        }
        try {
            UUID.fromString(id);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String jsonEscape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\':
                    sb.append("\\\\");
                    break;
                case '"':
                    sb.append("\\\"");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    private static String buildRosterJson() {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"type\":\"roster\",\"participants\":[");
        boolean first = true;
        for (Session s : SESSIONS.values()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"sessionId\":\"").append(jsonEscape(s.sessionId)).append("\",");
            sb.append("\"name\":\"").append(jsonEscape(s.name)).append("\",");
            sb.append("\"color\":\"").append(jsonEscape(s.color)).append("\"}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private static void sendJson(SseEmitter emitter, String eventName, String json) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(json));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void broadcastRoster() {
        String json = buildRosterJson();
        List<Session> dead = new ArrayList<>();
        for (Session s : SESSIONS.values()) {
            try {
                sendJson(s.emitter, "collab", json);
            } catch (RuntimeException e) {
                dead.add(s);
            }
        }
        for (Session s : dead) {
            SESSIONS.remove(s.sessionId, s);
        }
    }

    private static void broadcastToOthers(String excludeSessionId, String json) {
        List<Session> dead = new ArrayList<>();
        for (Session s : SESSIONS.values()) {
            if (s.sessionId.equals(excludeSessionId)) {
                continue;
            }
            try {
                sendJson(s.emitter, "collab", json);
            } catch (RuntimeException e) {
                dead.add(s);
            }
        }
        for (Session s : dead) {
            SESSIONS.remove(s.sessionId, s);
        }
    }

    /**
     * Prefix client JSON object with {@code from} session id. Client body must be a JSON object {@code {...}}.
     */
    private static String envelopeFromSession(String sessionId, String clientJson) {
        if (clientJson == null) {
            return null;
        }
        String t = clientJson.trim();
        if (!t.startsWith("{") || !t.endsWith("}")) {
            return null;
        }
        return "{\"from\":\"" + jsonEscape(sessionId) + "\"," + t.substring(1);
    }

    public static SseEmitter subscribe(String sessionId) {
        if (!isValidSessionId(sessionId)) {
            throw new IllegalArgumentException("Invalid sessionId");
        }
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        Session session = new Session(sessionId, emitter);
        Session prev = SESSIONS.put(sessionId, session);
        if (prev != null) {
            try {
                prev.emitter.complete();
            } catch (Exception ignored) {
            }
        }

        emitter.onCompletion(() -> {
            SESSIONS.remove(sessionId, session);
            broadcastRoster();
        });
        emitter.onTimeout(() -> {
            SESSIONS.remove(sessionId, session);
            broadcastRoster();
        });
        emitter.onError(e -> {
            SESSIONS.remove(sessionId, session);
            broadcastRoster();
        });

        try {
            emitter.send(SseEmitter.event().name("collab").data("{\"type\":\"hello\"}"));
        } catch (IOException e) {
            SESSIONS.remove(sessionId, session);
            throw new RuntimeException(e);
        }

        broadcastRoster();
        return emitter;
    }

    public static void updateProfile(String sessionId, String name, String color) {
        Session s = SESSIONS.get(sessionId);
        if (s == null) {
            return;
        }
        if (name != null) {
            String n = name.trim();
            if (!n.isEmpty() && n.length() <= 64) {
                s.name = n;
            }
        }
        if (color != null) {
            String c = color.trim();
            if (c.length() <= 32 && c.matches("#[0-9a-fA-F]{6}")) {
                s.color = c;
            }
        }
        broadcastRoster();
    }

    public static String publish(String publisherSessionId, byte[] bodyBytes) {
        Session pub = SESSIONS.get(publisherSessionId);
        if (pub == null) {
            return "not connected";
        }
        if (bodyBytes == null || bodyBytes.length == 0) {
            return "empty body";
        }
        if (bodyBytes.length > 12 * 1024 * 1024) {
            return "body too large";
        }
        String clientJson = new String(bodyBytes, StandardCharsets.UTF_8);
        String out = envelopeFromSession(publisherSessionId, clientJson);
        if (out == null) {
            return "invalid json envelope";
        }
        broadcastToOthers(publisherSessionId, out);
        return "ok";
    }
}
