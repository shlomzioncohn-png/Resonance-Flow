package com.rnd.inbox.auth.controller;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rnd.inbox.auth.dto.AuthUserResponseDto;
import com.rnd.inbox.auth.dto.AuthVerifyRequestDto;
import com.rnd.inbox.auth.exception.InvalidCredentialsException;
import com.rnd.inbox.auth.exception.TooManyLoginAttemptsException;
import com.rnd.inbox.auth.service.AuthService;
import com.rnd.inbox.shared.config.AppConfig;
import com.rnd.inbox.shared.config.WebMvcConfig;
import com.rnd.inbox.user.model.UserRole;
import com.rnd.inbox.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@Import({AppConfig.class, WebMvcConfig.class})
class AuthControllerTest {

    private static final String VERIFY_PATH = "/api/v1/auth/verify";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AuthService authService;

    @MockBean
    private UserRepository userRepository;

    private UUID userId;
    private UUID tenantId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        tenantId = UUID.randomUUID();
    }

    private String body(String login, String password) throws Exception {
        AuthVerifyRequestDto request = new AuthVerifyRequestDto();
        request.setLogin(login);
        request.setPassword(password);
        return objectMapper.writeValueAsString(request);
    }

    @Test
    void verify_withValidCredentials_returns200WithUser() throws Exception {
        when(authService.verify(any())).thenReturn(
                new AuthUserResponseDto(userId, "Rachel Cohen", UserRole.EMPLOYEE, tenantId));

        mockMvc.perform(post(VERIFY_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("rachel", "MyPass123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(userId.toString()))
                .andExpect(jsonPath("$.display_name").value("Rachel Cohen"))
                .andExpect(jsonPath("$.role").value("EMPLOYEE"))
                .andExpect(jsonPath("$.tenant_id").value(tenantId.toString()))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.password_hash").doesNotExist());
    }

    @Test
    void verify_withMissingFields_returns400() throws Exception {
        mockMvc.perform(post(VERIFY_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));

        verify(authService, never()).verify(any());
    }

    @Test
    void verify_withInvalidCredentials_returns401() throws Exception {
        when(authService.verify(any())).thenThrow(new InvalidCredentialsException());

        mockMvc.perform(post(VERIFY_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("rachel", "wrong")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.message").value("Invalid login or password"));
    }

    @Test
    void verify_whenLoginIsLocked_returns429() throws Exception {
        when(authService.verify(any())).thenThrow(new TooManyLoginAttemptsException());

        mockMvc.perform(post(VERIFY_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("rachel", "MyPass123")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("TOO_MANY_LOGIN_ATTEMPTS"));
    }

    @Test
    void verify_withPasswordOver72Bytes_returns400() throws Exception {
        mockMvc.perform(post(VERIFY_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("rachel", "א".repeat(40))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field_errors[0].field").value("password"));

        verify(authService, never()).verify(any());
    }
}
