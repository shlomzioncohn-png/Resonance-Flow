package com.rnd.bff.client;

import com.rnd.bff.exception.EmailTakenException;
import com.rnd.bff.exception.InvalidCredentialsException;
import com.rnd.bff.exception.ServiceUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * HTTP client for Inbox's auth endpoints.
 * Calls POST /api/v1/auth/register and POST /api/v1/auth/verify on Inbox.
 * 
 * All auth requests must include X-Actor-Id header (service account with SYSTEM_ADMIN role).
 * Responses contain user info (id, displayName, role) used by BFF to create JWT.
 * 
 * Maps Inbox exceptions to BFF exceptions and translates HTTP errors.
 * 
 * Mock mode (inbox.mock-enabled=true): returns hardcoded test users instead of calling Inbox.
 */
@Service
public class InboxUsersClient {

    private static final Logger logger = LoggerFactory.getLogger(InboxUsersClient.class);
    
    private static final String ACTOR_HEADER = "X-Actor-Id";
    private static final String AUTH_REGISTER_PATH = "/api/v1/auth/register";
    private static final String AUTH_VERIFY_PATH = "/api/v1/auth/verify";

    private final String inboxServiceUrl;
    private final UUID serviceAccountId;
    private final RestTemplate restTemplate;
    private final boolean mockEnabled;
    
    // Mock data storage (in-memory, for testing only)
    private final Map<String, MockUser> mockUsers = new HashMap<>();

    public InboxUsersClient(
            @Value("${inbox.service.url:http://inbox-service:8080}") String inboxServiceUrl,
            @Value("${inbox.service-account-id:00000000-0000-0000-0000-000000000002}") String serviceAccountIdStr,
            @Value("${inbox.connect-timeout-ms:2000}") long connectTimeoutMs,
            @Value("${inbox.read-timeout-ms:10000}") long readTimeoutMs,
            @Value("${inbox.mock-enabled:false}") boolean mockEnabled) {
        this.inboxServiceUrl = inboxServiceUrl;
        this.serviceAccountId = UUID.fromString(serviceAccountIdStr);
        this.mockEnabled = mockEnabled;

        // JDK HttpClient with configured timeouts
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofMillis(connectTimeoutMs)).build());
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        this.restTemplate = new RestTemplate(requestFactory);

        logger.info("InboxUsersClient initialized: Inbox at {}, service account: {}, mock-enabled: {}",
                inboxServiceUrl, serviceAccountId, mockEnabled);
        
        // Initialize mock users for testing
        if (mockEnabled) {
            initializeMockUsers();
        }
    }

    /**
     * Register a new user with email and password.
     * Calls POST /api/v1/auth/register on Inbox (or returns mock if mock-enabled).
     *
     * @param email user's email
     * @param password user's plaintext password
     * @param displayName user's display name
     * @return user info (id, displayName, role)
     * @throws EmailTakenException if email is already registered (409 from Inbox)
     * @throws ServiceUnavailableException if Inbox is unreachable or returns 5xx
     */
    public InboxAuthUserResponse register(String email, String password, String displayName) {
        if (mockEnabled) {
            return mockRegister(email, password, displayName);
        }
        
        try {
            String url = inboxServiceUrl + AUTH_REGISTER_PATH;
            
            InboxAuthRegisterRequest request = new InboxAuthRegisterRequest(email, password, displayName);
            HttpEntity<InboxAuthRegisterRequest> entity = new HttpEntity<>(request, buildHeaders());
            
            logger.debug("Calling Inbox: POST {}", url);
            InboxAuthUserResponse response = restTemplate.postForObject(
                    url,
                    entity,
                    InboxAuthUserResponse.class);
            
            logger.info("User registered via Inbox: {}", response.getId());
            return response;
        } catch (HttpClientErrorException.Conflict e) {
            logger.warn("Email already taken");
            throw new EmailTakenException(email);
        } catch (HttpClientErrorException e) {
            logger.warn("Inbox returned error: {} {}", e.getStatusCode(), e.getMessage());
            throw new ServiceUnavailableException("Inbox", "registration", e);
        } catch (Exception e) {
            logger.error("Failed to reach Inbox for registration", e);
            throw new ServiceUnavailableException("Inbox", "registration", e);
        }
    }

    /**
     * Verify user credentials (email/username + password).
     * Calls POST /api/v1/auth/verify on Inbox (or returns mock if mock-enabled).
     * 
     * @param login user's email or username
     * @param password user's plaintext password
     * @return user info (id, displayName, role) if credentials are valid
     * @throws InvalidCredentialsException if credentials are invalid (401 from Inbox)
     * @throws ServiceUnavailableException if Inbox is unreachable or returns 5xx
     */
    public InboxAuthUserResponse verify(String login, String password) {
        if (mockEnabled) {
            return mockVerify(login, password);
        }
        
        try {
            String url = inboxServiceUrl + AUTH_VERIFY_PATH;
            
            InboxAuthVerifyRequest request = new InboxAuthVerifyRequest(login, password);
            HttpEntity<InboxAuthVerifyRequest> entity = new HttpEntity<>(request, buildHeaders());
            
            logger.debug("Calling Inbox: POST {}", url);
            InboxAuthUserResponse response = restTemplate.postForObject(
                    url,
                    entity,
                    InboxAuthUserResponse.class);
            
            logger.info("User verified via Inbox: {}", response.getId());
            return response;
        } catch (HttpClientErrorException.Unauthorized e) {
            logger.warn("Invalid credentials for: {}", login);
            throw new InvalidCredentialsException();
        } catch (HttpClientErrorException e) {
            logger.warn("Inbox returned error: {} {}", e.getStatusCode(), e.getMessage());
            throw new ServiceUnavailableException("Inbox", "verification", e);
        } catch (Exception e) {
            logger.error("Failed to reach Inbox for verification", e);
            throw new ServiceUnavailableException("Inbox", "verification", e);
        }
    }

    /**
     * Mock: Register a new user in memory
     */
    private InboxAuthUserResponse mockRegister(String email, String password, String displayName) {
        // Check if email already exists
        if (mockUsers.values().stream().anyMatch(u -> u.email.equals(email))) {
            logger.warn("[MOCK] Email already taken: {}", email);
            throw new EmailTakenException(email);
        }
        
        UUID userId = UUID.randomUUID();
        MockUser mockUser = new MockUser(userId, email, password, displayName, "EMPLOYEE");
        mockUsers.put(email, mockUser);
        
        logger.info("[MOCK] User registered: {} ({})", userId, email);
        return new InboxAuthUserResponse(userId, displayName, "EMPLOYEE");
    }

    /**
     * Mock: Verify user credentials in memory
     */
    private InboxAuthUserResponse mockVerify(String login, String password) {
        // Try to find by email first, then by username (for admin)
        MockUser mockUser = mockUsers.values().stream()
                .filter(u -> u.email.equals(login) || u.displayName.toLowerCase().equals(login.toLowerCase()))
                .findFirst()
                .orElse(null);
        
        if (mockUser == null || !mockUser.password.equals(password)) {
            logger.warn("[MOCK] Invalid credentials for: {}", login);
            throw new InvalidCredentialsException();
        }
        
        logger.info("[MOCK] User verified: {} ({})", mockUser.id, login);
        return new InboxAuthUserResponse(mockUser.id, mockUser.displayName, mockUser.role);
    }

    /**
     * Initialize mock users for testing
     */
    private void initializeMockUsers() {
        // Test user: employee
        UUID empId = UUID.nameUUIDFromBytes("test-employee".getBytes());
        mockUsers.put("emp@example.com", new MockUser(empId, "emp@example.com", "password", "Test Employee", "EMPLOYEE"));
        
        // Test user: manager
        UUID mgmtId = UUID.nameUUIDFromBytes("test-manager".getBytes());
        mockUsers.put("mgr@example.com", new MockUser(mgmtId, "mgr@example.com", "password", "Test Manager", "MANAGER"));
        
        logger.info("[MOCK] Initialized {} mock users", mockUsers.size());
    }

    /**
     * Build HTTP headers for calls to Inbox, including the service account identity.
     */
    private HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(ACTOR_HEADER, serviceAccountId.toString());
        return headers;
    }

    /**
     * Internal mock user record (for testing only)
     */
    private static class MockUser {
        UUID id;
        String email;
        String password;
        String displayName;
        String role;

        MockUser(UUID id, String email, String password, String displayName, String role) {
            this.id = id;
            this.email = email;
            this.password = password;
            this.displayName = displayName;
            this.role = role;
        }
    }
}
