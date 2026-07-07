package com.kb.user.service;

import com.kb.common.dto.LoginRequest;
import com.kb.common.dto.RegisterRequest;
import com.kb.common.exception.BusinessException;
import com.kb.common.vo.UserVO;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;MODE=MySQL",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.datasource.read-write-splitting.enabled=false",
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class UserServiceImplTest {

    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private UserService userService;

    private static final String TEST_USERNAME = "integration_test_user";
    private static final String TEST_PASSWORD = "testPassword123";

    // ── 1. Register ──────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("register should create a new user with role=user")
    void register_shouldCreateUser() {
        RegisterRequest req = new RegisterRequest();
        req.setUsername(TEST_USERNAME);
        req.setPassword(TEST_PASSWORD);

        UserVO vo = userService.register(req);

        assertNotNull(vo);
        assertNotNull(vo.getId());
        assertEquals(TEST_USERNAME, vo.getUsername());
        assertEquals("user", vo.getRole());
    }

    // ── 2. Register duplicate ────────────────────────────────────────

    @Test
    @Order(2)
    @DisplayName("register duplicate username should throw BusinessException")
    void register_duplicateUsername_shouldThrow() {
        RegisterRequest req = new RegisterRequest();
        req.setUsername(TEST_USERNAME);
        req.setPassword(TEST_PASSWORD);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.register(req));

        assertEquals("用户名已存在", ex.getMessage());
    }

    // ── 3. Login ─────────────────────────────────────────────────────

    @Test
    @Order(3)
    @DisplayName("login should return token, tokenType, userId, username, and role")
    void login_shouldReturnTokenAndUserInfo() {
        LoginRequest req = new LoginRequest();
        req.setUsername(TEST_USERNAME);
        req.setPassword(TEST_PASSWORD);

        Map<String, Object> result = userService.login(req);

        assertNotNull(result);
        assertNotNull(result.get("token"));
        assertFalse(result.get("token").toString().isBlank());
        assertEquals("Bearer", result.get("tokenType"));
        assertNotNull(result.get("userId"));
        assertEquals(TEST_USERNAME, result.get("username"));
        assertEquals("user", result.get("role"));
    }

    // ── 4. Login wrong password ──────────────────────────────────────

    @Test
    @Order(4)
    @DisplayName("login with wrong password should throw BusinessException")
    void login_wrongPassword_shouldThrow() {
        LoginRequest req = new LoginRequest();
        req.setUsername(TEST_USERNAME);
        req.setPassword("wrongPassword");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.login(req));

        assertEquals("用户名或密码错误", ex.getMessage());
    }

    // ── 5. Login nonexistent user ────────────────────────────────────

    @Test
    @Order(5)
    @DisplayName("login with nonexistent username should throw BusinessException")
    void login_nonexistentUser_shouldThrow() {
        LoginRequest req = new LoginRequest();
        req.setUsername("nonexistent_user");
        req.setPassword("anyPassword");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.login(req));

        assertEquals("用户名或密码错误", ex.getMessage());
    }
}
