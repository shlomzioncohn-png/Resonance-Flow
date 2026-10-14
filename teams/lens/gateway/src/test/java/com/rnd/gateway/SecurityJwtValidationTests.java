package com.rnd.gateway;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.rnd.gateway.dto.TaskListResponseDTO;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the Gateway rejects every request without a valid JWT — and that
 * rejected requests never reach the BFF.
 *
 * Unlike the other test classes (which use spring-security-test's jwt()
 * post-processor and skip real decoding), this class exercises the real
 * validation path: a MockWebServer stands in for Keycloak's JWKS endpoint,
 * and tokens are actually RS256-signed, so signature, expiry and issuer are
 * checked exactly as they are against Keycloak.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Gateway JWT validation")
class SecurityJwtValidationTests {

    private static final String ISSUER = "http://keycloak.test/realms/lens";
    private static final String KEY_ID = "lens-signing-key";
    private static final String TASKS_URL = "/api/workspace/tasks";

    /** The realm's real signing key — its public half is served by the JWKS stub. */
    private static RSAKey realmKey;
    /** An attacker's key, published nowhere, reusing the realm key's kid. */
    private static RSAKey attackerKey;
    private static MockWebServer jwksServer;

    @Autowired
    private MockMvc mockMvc;

    /** Stands in for the BFF — lets each test assert whether a request got through. */
    @MockBean
    private RestTemplate restTemplate;

    @BeforeAll
    static void startJwksServer() throws IOException, JOSEException {
        realmKey = new RSAKeyGenerator(2048).keyID(KEY_ID).generate();
        attackerKey = new RSAKeyGenerator(2048).keyID(KEY_ID).generate();

        String jwks = new JWKSet(realmKey.toPublicJWK()).toString();
        jwksServer = new MockWebServer();
        jwksServer.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody(jwks);
            }
        });
        jwksServer.start();
    }

    @AfterAll
    static void stopJwksServer() throws IOException {
        jwksServer.shutdown();
    }

    @DynamicPropertySource
    static void registerJwtConfig(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> "http://localhost:" + jwksServer.getPort() + "/realms/lens/protocol/openid-connect/certs");
    }

    @BeforeEach
    void setUp() {
        reset(restTemplate);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String signToken(RSAKey signingKey, String issuer, Instant expiresAt) throws JOSEException {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("user-123")
                .issuer(issuer)
                .issueTime(Date.from(expiresAt.minus(Duration.ofMinutes(5))))
                .expirationTime(Date.from(expiresAt))
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(),
                claims);
        jwt.sign(new RSASSASigner(signingKey));
        return jwt.serialize();
    }

    private static String validToken() throws JOSEException {
        return signToken(realmKey, ISSUER, Instant.now().plus(Duration.ofMinutes(5)));
    }

    private ResultActions getTasks(String authorizationHeader) throws Exception {
        return mockMvc.perform(get(TASKS_URL).header(HttpHeaders.AUTHORIZATION, authorizationHeader));
    }

    /** Every rejection must be a JSON 401 in the contract's format, with BFF never called. */
    private void assertRejected(ResultActions result) throws Exception {
        result.andExpect(status().isUnauthorized())
              .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer")))
              .andExpect(jsonPath("$.status").value("ERROR"))
              .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"))
              .andExpect(jsonPath("$.path").value(TASKS_URL));
        verifyNoInteractions(restTemplate);
    }

    // -------------------------------------------------------------------------
    // Accepted
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Valid token → request is forwarded to BFF")
    void validTokenIsForwardedToBff() throws Exception {
        when(restTemplate.exchange(contains("workspace/tasks"), eq(HttpMethod.GET), any(),
                eq(TaskListResponseDTO.class)))
                .thenReturn(ResponseEntity.ok(new TaskListResponseDTO()));

        getTasks("Bearer " + validToken())
                .andExpect(status().isOk());
    }

    // -------------------------------------------------------------------------
    // Rejected — must never reach BFF
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("No Authorization header → 401")
    void missingTokenIsRejected() throws Exception {
        assertRejected(mockMvc.perform(get(TASKS_URL)));
    }

    @Test
    @DisplayName("Malformed token → 401")
    void malformedTokenIsRejected() throws Exception {
        assertRejected(getTasks("Bearer not-a-jwt"));
    }

    @Test
    @DisplayName("Expired token → 401")
    void expiredTokenIsRejected() throws Exception {
        String expired = signToken(realmKey, ISSUER, Instant.now().minus(Duration.ofMinutes(10)));
        assertRejected(getTasks("Bearer " + expired));
    }

    @Test
    @DisplayName("Forged token (signed with a key Keycloak doesn't publish) → 401")
    void forgedSignatureIsRejected() throws Exception {
        String forged = signToken(attackerKey, ISSUER, Instant.now().plus(Duration.ofMinutes(5)));
        assertRejected(getTasks("Bearer " + forged));
    }

    @Test
    @DisplayName("Tampered token (payload changed after signing) → 401")
    void tamperedPayloadIsRejected() throws Exception {
        String[] parts = validToken().split("\\.");
        String tamperedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"sub\":\"someone-else\",\"iss\":\"" + ISSUER + "\",\"exp\":"
                        + Instant.now().plus(Duration.ofHours(1)).getEpochSecond() + "}").getBytes());
        assertRejected(getTasks("Bearer " + parts[0] + "." + tamperedPayload + "." + parts[2]));
    }

    @Test
    @DisplayName("Token from another issuer → 401")
    void wrongIssuerIsRejected() throws Exception {
        String otherRealm = signToken(realmKey, "http://keycloak.test/realms/other",
                Instant.now().plus(Duration.ofMinutes(5)));
        assertRejected(getTasks("Bearer " + otherRealm));
    }

    // -------------------------------------------------------------------------
    // Public routes
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("CORS preflight on an /api route isn't blocked by auth (browsers never send a token on OPTIONS)")
    void corsPreflightIsNotBlocked() throws Exception {
        mockMvc.perform(options(TASKS_URL)
                        .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization"))
                .andExpect(status().isOk());
        verifyNoInteractions(restTemplate);
    }

    @Test
    @DisplayName("Health check stays public (no token needed)")
    void healthIsPublic() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk());
    }
}
