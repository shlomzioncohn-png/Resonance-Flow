package com.rnd.inbox.tenant;

import com.rnd.inbox.validation.OptionalNotBlank;
import jakarta.validation.constraints.Size;
import org.openapitools.jackson.nullable.JsonNullable;

public class TenantUpdateRequestDto {

    @OptionalNotBlank
    @Size(max = 255)
    private String name;

    @Size(max = 100)
    private JsonNullable<String> timezone = JsonNullable.undefined();

    @Size(max = 20)
    private JsonNullable<String> localeDefault = JsonNullable.undefined();

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public JsonNullable<String> getTimezone() {
        return timezone;
    }

    public void setTimezone(JsonNullable<String> timezone) {
        this.timezone = timezone;
    }

    public JsonNullable<String> getLocaleDefault() {
        return localeDefault;
    }

    public void setLocaleDefault(JsonNullable<String> localeDefault) {
        this.localeDefault = localeDefault;
    }
}
