package com.kb.common.constant;

public class JwtConstants {

    /**
     * JWT signing secret — MUST be overridden via JWT_SECRET env var in production.
     * Default is for local development only.
     */
    public static final String SECRET = System.getenv().getOrDefault(
            "JWT_SECRET", "knowledge-platform-jwt-secret-key-2026-min-32-chars!!");
    public static final long EXPIRATION_MS = 7 * 24 * 60 * 60 * 1000L;

    public static final String HEADER_NAME = "Authorization";
    public static final String TOKEN_PREFIX = "Bearer ";
    public static final String HEADER_USER_ID = "X-User-Id";
    public static final String HEADER_USER_ROLE = "X-User-Role";

    private JwtConstants() {}
}
