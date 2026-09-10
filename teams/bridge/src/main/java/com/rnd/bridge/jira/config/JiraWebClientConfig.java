package com.rnd.bridge.jira.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class JiraWebClientConfig {

    @Value("${jira.api.base-url:https://example.atlassian.net}")
    private String jiraApiBaseUrl;

    @Value("${jira.api.token:}")
    private String jiraApiToken;

    @Bean
    @Qualifier("jiraWebClient")
    public WebClient jiraWebClient() {
        WebClient.Builder builder = WebClient.builder()
                .baseUrl(jiraApiBaseUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);

        if (!jiraApiToken.isEmpty()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + jiraApiToken);
        }

        return builder.build();
    }
}
