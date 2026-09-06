package com.rnd.inbox.config;

import jakarta.validation.valueextraction.ExtractedValue;
import jakarta.validation.valueextraction.UnwrapByDefault;
import jakarta.validation.valueextraction.ValueExtractor;
import org.openapitools.jackson.nullable.JsonNullable;

/**
 * Lets Bean Validation (@Size, @Email, ...) reach through {@link JsonNullable}
 * to the wrapped value, the same way it already does for {@code Optional}.
 * Without this, constraints on a {@code JsonNullable<String>} field are never
 * checked, because the validator sees a {@code JsonNullable} instance instead
 * of a {@code String}. Discovered automatically via the ServiceLoader entry
 * in META-INF/services.
 */
@UnwrapByDefault
public class JsonNullableValueExtractor implements ValueExtractor<JsonNullable<@ExtractedValue ?>> {

    @Override
    public void extractValues(JsonNullable<?> originalValue, ValueReceiver receiver) {
        if (originalValue.isPresent()) {
            receiver.value(null, originalValue.get());
        }
    }
}
