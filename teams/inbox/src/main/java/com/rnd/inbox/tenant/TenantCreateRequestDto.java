package com.rnd.inbox.tenant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class TenantCreateRequestDto {

    @NotBlank
    @Size(max = 255)
    private String name;

    @NotBlank
    @Size(max = 100)
    private String slug;

    @Size(max = 100)
    private String timezone;

    @Size(max = 20)
    private String localeDefault;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public String getLocaleDefault() {
        return localeDefault;
    }

    public void setLocaleDefault(String localeDefault) {
        this.localeDefault = localeDefault;
    }
}
