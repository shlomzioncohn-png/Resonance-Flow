package com.rnd.bridge.media.classification;

/**
 * One single-turn call to a chat model. Kept separate from {@link ModelMediaClassifier} so the
 * prompt/parsing logic is testable without HTTP, and the transport (provider) is swappable.
 */
public interface ChatCompletionClient {

    /**
     * @param pngImage an image to attach to the user message, or null for text only
     * @return the model's reply text, unparsed
     */
    String complete(String systemPrompt, String userText, byte[] pngImage);
}
