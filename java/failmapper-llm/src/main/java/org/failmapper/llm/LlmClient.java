package org.failmapper.llm;

/** Minimal LLM completion interface; implementations own retries and timeouts. */
public interface LlmClient {

    /**
     * @return the assistant message text
     * @throws LlmException on unrecoverable transport/API failure (after retries)
     */
    String complete(String systemPrompt, String userPrompt);

    class LlmException extends RuntimeException {
        public LlmException(String message, Throwable cause) {
            super(message, cause);
        }

        public LlmException(String message) {
            super(message);
        }
    }
}
