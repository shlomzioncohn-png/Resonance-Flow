package com.rnd.bridge.ai;

/**
 * The model's reply plus what it used. {@code model} is the model that actually answered - after
 * a fallback, that is not the usage's primary model. Token counts are null when the provider
 * didn't report them: unknown, never 0.
 */
public record LlmResponse(String text, String model, Integer promptTokens, Integer completionTokens) {
}
