package com.kb.common.utils;

import com.kb.common.constant.JwtConstants;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

class JwtUtilsTest {

    // ── generateToken ────────────────────────────────────────────────

    @Test
    @DisplayName("generateToken should produce a non-null, non-empty string")
    void generateToken_shouldReturnNonEmptyString() {
        String token = JwtUtils.generateToken(1L, "user");

        assertNotNull(token);
        assertFalse(token.isBlank());
    }

    // ── parseToken + claims extraction ───────────────────────────────

    @Test
    @DisplayName("parseToken should return Claims containing correct userId and role")
    void parseToken_shouldReturnCorrectClaims() {
        String token = JwtUtils.generateToken(42L, "admin");

        Claims claims = JwtUtils.parseToken(token);

        assertEquals("42", claims.getSubject());
        assertEquals("admin", claims.get("role", String.class));
    }

    @Test
    @DisplayName("getUserId should extract the userId passed to generateToken")
    void getUserId_shouldMatchInput() {
        String token = JwtUtils.generateToken(100L, "user");

        Claims claims = JwtUtils.parseToken(token);
        Long userId = JwtUtils.getUserId(claims);

        assertEquals(100L, userId);
    }

    @Test
    @DisplayName("getUserRole should extract the role passed to generateToken")
    void getUserRole_shouldMatchInput() {
        String token = JwtUtils.generateToken(7L, "user");

        Claims claims = JwtUtils.parseToken(token);

        assertEquals("user", JwtUtils.getUserRole(claims));
    }

    // ── Different roles ──────────────────────────────────────────────

    @Test
    @DisplayName("token should preserve role \"user\" correctly")
    void token_withUserRole() {
        String token = JwtUtils.generateToken(1L, "user");

        Claims claims = JwtUtils.parseToken(token);

        assertEquals("user", JwtUtils.getUserRole(claims));
    }

    @Test
    @DisplayName("token should preserve role \"admin\" correctly")
    void token_withAdminRole() {
        String token = JwtUtils.generateToken(1L, "admin");

        Claims claims = JwtUtils.parseToken(token);

        assertEquals("admin", JwtUtils.getUserRole(claims));
    }

    // ── Invalid / empty token ────────────────────────────────────────

    @Test
    @DisplayName("parseToken with empty string should throw")
    void parseToken_withEmptyString_shouldThrow() {
        assertThrows(RuntimeException.class, () -> JwtUtils.parseToken(""));
    }

    @Test
    @DisplayName("parseToken with garbage string should throw")
    void parseToken_withInvalidToken_shouldThrow() {
        assertThrows(JwtException.class, () -> JwtUtils.parseToken("not.a.valid.jwt"));
    }

    @Test
    @DisplayName("parseToken with null should throw")
    void parseToken_withNull_shouldThrow() {
        assertThrows(IllegalArgumentException.class, () -> JwtUtils.parseToken(null));
    }

    // ── Expiry ───────────────────────────────────────────────────────

    @Test
    @DisplayName("fresh token should expire in the future, within 7 days")
    void tokenExpiry_shouldBeWithin7Days() {
        long before = System.currentTimeMillis();
        String token = JwtUtils.generateToken(1L, "user");
        long after = System.currentTimeMillis();

        Claims claims = JwtUtils.parseToken(token);
        Date expiration = claims.getExpiration();

        // Expiration must be in the future
        assertTrue(expiration.after(new Date()));

        // Expiration should be roughly JwtConstants.EXPIRATION_MS from now (±1 second tolerance)
        long expectedExpiry = before + JwtConstants.EXPIRATION_MS;
        long tolerance = 1000; // 1 second tolerance for clock jitter
        assertTrue(Math.abs(expiration.getTime() - expectedExpiry) < tolerance,
                "expiration should be ~7 days from now (within 1s tolerance)");
    }

    // ── isExpired ────────────────────────────────────────────────────

    @Test
    @DisplayName("isExpired should return false for a freshly generated token")
    void isExpired_freshToken_shouldBeFalse() {
        String token = JwtUtils.generateToken(1L, "user");
        Claims claims = JwtUtils.parseToken(token);

        assertFalse(JwtUtils.isExpired(claims));
    }

    // ── getRemainingTtl ──────────────────────────────────────────────

    @Test
    @DisplayName("getRemainingTtl should be > 0 for a freshly generated token")
    void getRemainingTtl_freshToken_shouldBePositive() {
        String token = JwtUtils.generateToken(1L, "user");
        Claims claims = JwtUtils.parseToken(token);

        long ttl = JwtUtils.getRemainingTtl(claims);

        assertTrue(ttl > 0);
        assertTrue(ttl <= JwtConstants.EXPIRATION_MS);
    }
}
