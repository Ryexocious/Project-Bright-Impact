package utils;

import java.util.UUID;

/**
 * Simple in-memory session manager for the desktop app.
 *
 * Long-term: replace / augment with secure Firebase Auth or a server-issued token.
 */
public class SessionManager {
    private static String currentUserId;
    private static String currentUserRole;
    private static String currentSessionId;

    /** Create a new session for userId and role, returns generated sessionId. */
    public static String createSession(String userId, String role) {
        currentUserId = userId;
        currentUserRole = role;
        currentSessionId = UUID.randomUUID().toString();
        return currentSessionId;
    }

    /** Set an existing session (useful if you want to restore from disk). */
    public static void setSession(String userId, String role, String sessionId) {
        currentUserId = userId;
        currentUserRole = role;
        currentSessionId = sessionId;
    }

    public static String getCurrentUserId() { return currentUserId; }
    public static String getCurrentUserRole() { return currentUserRole; }
    public static String getCurrentSessionId() { return currentSessionId; }

    public static boolean isLoggedIn() {
        return currentUserId != null && currentSessionId != null;
    }

    public static void clear() {
        currentUserId = null;
        currentUserRole = null;
        currentSessionId = null;
    }
}
