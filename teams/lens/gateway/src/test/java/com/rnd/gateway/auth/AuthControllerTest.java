package com.rnd.gateway.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Unit tests for AuthController.
 * 
 * Tests cover:
 * - User login (valid, invalid credentials, rate limiting)
 * - Rate limiting enforcement
 */
public class AuthControllerTest {

    private MockMvc mockMvc;
    
    @Mock
    private AuthService authService;
    
    @Mock
    private RateLimitingConfig.RateLimiter loginRateLimiter;
    
    private AuthController authController;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        authController = new AuthController(authService, loginRateLimiter);
        mockMvc = MockMvcBuilders.standaloneSetup(authController).build();
    }

    @Test
    public void testLoginSuccess() throws Exception {
        // Given
        UUID userId = UUID.randomUUID();
        when(loginRateLimiter.allowRequest(anyString())).thenReturn(true);
        // any(): AuthLoginRequestDto has no equals(), so a new instance would never match
        when(authService.login(any(AuthLoginRequestDto.class)))
            .thenReturn(new AuthLoginResponseDto(
                3600, userId, "Test User", "EMPLOYEE"
            ));

        // When & Then
        mockMvc.perform(post("/auth/login")
            .contentType("application/json")
            .content("{\"login\":\"test@example.com\",\"password\":\"Password123!\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.expiresIn").value(3600))
            .andExpect(jsonPath("$.user.id").value(userId.toString()))
            .andExpect(jsonPath("$.user.displayName").value("Test User"))
            .andExpect(jsonPath("$.user.role").value("EMPLOYEE"));
    }

    @Test
    public void testLoginRateLimitExceededAfterFailedAttempts() throws Exception {
        // Given
        when(loginRateLimiter.allowRequest(anyString())).thenReturn(false);
        when(authService.login(any(AuthLoginRequestDto.class)))
            .thenThrow(new BffAuthClient.InvalidCredentialsException());

        // When & Then
        mockMvc.perform(post("/auth/login")
            .contentType("application/json")
            .content("{\"login\":\"test@example.com\",\"password\":\"WrongPassword\"}"))
            .andExpect(status().isTooManyRequests());
    }
}
