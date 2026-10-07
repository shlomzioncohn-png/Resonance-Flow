package com.rnd.inbox.shared.messaging.kafka;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.function.Function;

public class JsonEventParser<T> implements Function<String, T> {

    private final ObjectMapper objectMapper;
    private final Class<T> eventType;

    public JsonEventParser(ObjectMapper objectMapper, Class<T> eventType) {
        this.objectMapper = objectMapper;
        this.eventType = eventType;
    }

    @Override
    public T apply(String raw) {
        try {
            return objectMapper.readValue(raw, eventType);
        } catch (JsonParseException e) {
            throw new MalformedEventException(e.getMessage(), e);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }
}
