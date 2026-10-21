package com.rnd.inbox.shared.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "inbox.sql-logging")
public class SqlLoggingProperties {

    private boolean enabled = true;
    // Off by default: bound values can be sensitive (e.g. an OTP hash). Enable only for local development.
    private boolean includeParameters = false;
    private long slowQueryThresholdMs = 200;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isIncludeParameters() {
        return includeParameters;
    }

    public void setIncludeParameters(boolean includeParameters) {
        this.includeParameters = includeParameters;
    }

    public long getSlowQueryThresholdMs() {
        return slowQueryThresholdMs;
    }

    public void setSlowQueryThresholdMs(long slowQueryThresholdMs) {
        this.slowQueryThresholdMs = slowQueryThresholdMs;
    }
}
