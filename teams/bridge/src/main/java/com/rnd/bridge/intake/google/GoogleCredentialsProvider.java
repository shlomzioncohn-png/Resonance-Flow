package com.rnd.bridge.intake.google;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.rnd.bridge.intake.config.GoogleOAuthProperties;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * One installed-app OAuth2 flow shared by every real Google reader (Calendar/Gmail/Drive) so
 * credential loading isn't duplicated three times - see plan.md "Real reader specifics". The
 * first call for a given scope set opens a browser for one-time user consent and caches the
 * resulting refresh token under tokensDirectory; every call after that (including after a
 * restart) refreshes silently from the cached token, no browser involved.
 */
@Component
public class GoogleCredentialsProvider {

    private final GoogleOAuthProperties properties;
    private final Map<List<String>, Credential> credentialsByScope = new ConcurrentHashMap<>();

    public GoogleCredentialsProvider(GoogleOAuthProperties properties) {
        this.properties = properties;
    }

    public Credential credentials(List<String> scopes) {
        return credentialsByScope.computeIfAbsent(scopes, this::authorize);
    }

    private Credential authorize(List<String> scopes) {
        Path clientSecretsPath = resolveClientSecretsPath();
        try {
            // Deliberately NOT GoogleNetHttpTransport.newTrustedTransport() - that pins to a
            // trust store bundled inside the Google client libraries, which ignores the JDK's
            // system cacerts and therefore breaks under SSL-inspecting proxies (see plan.md /
            // conversation notes on the NetFree dev-environment fix). Plain NetHttpTransport
            // uses the system default SSLContext, matching how RealSlackSourceReader's HTTP
            // calls already work behind the same proxy.
            HttpTransport httpTransport = new NetHttpTransport();
            var jsonFactory = GsonFactory.getDefaultInstance();
            GoogleClientSecrets clientSecrets;
            try (BufferedReader reader = Files.newBufferedReader(clientSecretsPath, StandardCharsets.UTF_8)) {
                clientSecrets = GoogleClientSecrets.load(jsonFactory, reader);
            }
            var flow = new GoogleAuthorizationCodeFlow.Builder(httpTransport, jsonFactory, clientSecrets, scopes)
                    .setDataStoreFactory(new FileDataStoreFactory(resolveTokensDirectory().toFile()))
                    .setAccessType("offline")
                    .build();
            return new AuthorizationCodeInstalledApp(flow, new LocalServerReceiver()).authorize("kamatech");
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to obtain Google OAuth credentials for scopes " + scopes
                            + " - if this is the first run, a browser window should have opened for consent",
                    e);
        }
    }

    private Path resolveClientSecretsPath() {
        String path = properties.clientSecretsPath();
        if (path == null || path.isBlank()) {
            throw new IllegalStateException(
                    "bridge.intake.google.client-secrets-path must be set when any Google source runs in real mode");
        }
        Path resolved = Path.of(path);
        if (!Files.isRegularFile(resolved)) {
            throw new IllegalStateException("bridge.intake.google.client-secrets-path does not exist: " + resolved);
        }
        return resolved;
    }

    private Path resolveTokensDirectory() {
        String dir = properties.tokensDirectory();
        if (dir == null || dir.isBlank()) {
            throw new IllegalStateException(
                    "bridge.intake.google.tokens-directory must be set when any Google source runs in real mode");
        }
        return Path.of(dir);
    }
}
