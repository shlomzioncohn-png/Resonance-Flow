package com.rnd.bridge.media.classification;

import java.util.List;

/**
 * One single-turn call to a chat model. Kept separate from {@link ModelMediaClassifier} so the
 * prompt/parsing logic is testable without HTTP, and the transport (provider) is swappable.
 */
public interface ChatCompletionClient {

    /**
     * @param pngImages images to attach to the user message, in order; empty for text only
     * @return the model's reply text, unparsed
     */
    String complete(String systemPrompt, String userText, List<byte[]> pngImages);
}
