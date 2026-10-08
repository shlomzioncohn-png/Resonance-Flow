package com.rnd.gateway.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

/**
 * HTTP client for BFF's internal auth endpoints.
 * Calls POST /internal/auth/register and POST /internal/auth/verify on BFF.
 * 
 * The BFF forwards these requests to Inbox and maps roles.
 * Gateway does not call Inbox directly - only through BFF.
 * 
 * Error responses from BFF are translated to Gateway exceptions.
 * 
 * Injects X-User-Id and X-User-Role headers from current user context for authenticated requests.
 */
@Service
public class BffAuthClient {

    private static final Logger logger = LoggerFactory.getLogger(BffAuthClient.class);
    
    private static final String AUTH_REGISTER_PATH = "/internal/auth/register";
    private static final String AUTH_VERIFY_PATH = "/internal/auth/verify";

    private final String bffServiceUrl;
    private final RestTemplate restTemplate;
    private final CurrentUserContext currentUserContext;

    public BffAuthClient(
            @Value("${bff.service.url:http://localhost:8082}") String bffServiceUrl,
            RestTemplate restTemplate,
            CurrentUserContext currentUserContext) {
        this.bffServiceUrl = bffServiceUrl;
        this.restTemplate = restTemplate;
        this.currentUserContext = currentUserContext;
        logger.info("BffAuthClient initialized: BFF at {}", bffServiceUrl);
    }

    /**
     * Register a new user by forwarding to BFF.
     * Calls POST /internal/auth/register on BFF.
     *
     * @param email user's email
     * @param password user's plaintext password
     * @param displayName user's display name
     * @return user info (id, displayName, role)
     * @throws EmailTakenException if email is already registered (409 from BFF)
     * @throws ServiceUnavailableException if BFF is unreachable or returns 5xx
     */
    public BffAuthUserResponse register(String email, String password, String displayName) {
        try {
            String url = bffServiceUrl + AUTH_REGISTER_PATH;
            
            BffAuthRegisterRequest request = new BffAuthRegisterRequest(email, password, displayName);
            HttpEntity<BffAuthRegisterRequest> entity = new HttpEntity<>(request, buildHeaders());
            
            logger.debug("Calling BFF: POST {}", url);
            BffAuthUserResponse response = restTemplate.postForObject(
                    url,
                    entity,
                    BffAuthUserResponse.class);
            
            logger.info("User registered via BFF: {}", response.getId());
            return response;
        } catch (HttpClientErrorException.Conflict e) {
            logger.warn("Email already taken");
            throw new EmailTakenException(email);
        } catch (HttpClientErrorException e) {
            logger.warn("BFF returned error: {} {}", e.getStatusCode(), e.getMessage());
            throw new com.rnd.gateway.exception.ServiceUnavailableException("BFF", "registration", e);
        } catch (Exception e) {
            logger.error("Failed to reach BFF for registration", e);
            throw new com.rnd.gateway.exception.ServiceUnavailableException("BFF", "registration", e);
        }
    }

    /**
     * Verify user credentials by forwarding to BFF.
     * Calls POST /internal/auth/verify on BFF.
     * 
     * @param login user's email or username
     * @param password user's plaintext password
     * @return user info (id, displayName, role) if credentials are valid
     * @throws InvalidCredentialsException if credentials are invalid (401 from BFF)
     * @throws ServiceUnavailableException if BFF is unreachable or returns 5xx
     */
    public BffAuthUserResponse verify(String login, String password) {
        try {
            String url = bffServiceUrl + AUTH_VERIFY_PATH;
            
            BffAuthVerifyRequest request = new BffAuthVerifyRequest(login, password);
            HttpEntity<BffAuthVerifyRequest> entity = new HttpEntity<>(request, buildHeaders());
            
            logger.debug("Calling BFF: POST {}", url);
            BffAuthUserResponse response = restTemplate.postForObject(
                    url,
                    entity,
                    BffAuthUserResponse.class);
            
            logger.info("User verified via BFF: {}", response.getId());
            return response;
        } catch (HttpClientErrorException.Unauthorized e) {
            logger.warn("Invalid credentials for: {}", login);
            throw new InvalidCredentialsException();
        } catch (HttpClientErrorException e) {
            logger.warn("BFF returned error: {} {}", e.getStatusCode(), e.getMessage());
            throw new com.rnd.gateway.exception.ServiceUnavailableException("BFF", "verification", e);
        } catch (Exception e) {
            logger.error("Failed to reach BFF for verification", e);
            throw new com.rnd.gateway.exception.ServiceUnavailableException("BFF", "verification", e);
        }
    }

    /**
     * Build HTTP headers for calls to BFF.
     * Includes standard headers and conditionally includes X-User-Id and X-User-Role
     * if the current user is authenticated.
     */
    private HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        
        // Add current user headers if available
        if (currentUserContext.getUserId().isPresent()) {
            headers.set("X-User-Id", currentUserContext.getUserId().get().toString());
        }
        if (currentUserContext.getRole().isPresent()) {
            headers.set("X-User-Role", currentUserContext.getRole().get());
        }
        
        return headers;
    }

    /**
     * Exception indicating email is already taken (409 from BFF).
     */
    public static class EmailTakenException extends RuntimeException {
        public EmailTakenException(String email) {
            super("Email already registered: " + email);
        }
    }

    /**
     * Exception indicating credentials are invalid (401 from BFF).
     */
    public static class InvalidCredentialsException extends RuntimeException {
        public InvalidCredentialsException() {
            super("Invalid credentials");
        }
    }
}
