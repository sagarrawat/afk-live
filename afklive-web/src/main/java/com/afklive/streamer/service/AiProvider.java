package com.afklive.streamer.service;

/**
 * One text model behind {@link AiService}: a prompt in, text (or JSON as text) out, with the tokens the call
 * used. Implementations never throw, never log the prompt or the key, and answer null on any failure (an
 * error, a timeout, no credit, a rate limit), so callers fall back to the creator's own words.
 */
public interface AiProvider {

    /**
     * One answer. {@code text} may be null when the call was billed but gave nothing usable (a refusal, an
     * empty answer); the tokens still count toward the monthly AI budget.
     */
    record Reply(String text, long inputTokens, long outputTokens) {
    }

    /** "anthropic" or "gemini": stored with the usage and shown in admin. */
    String name();

    /** The model id sent with every call. */
    String model();

    /** @param json ask for one JSON object (the title writer); otherwise plain text */
    Reply complete(String prompt, boolean json);

    /** A number from a JSON answer (Integer, Long or Double), 0 when absent. */
    static long tokens(Object v) {
        return v instanceof Number n ? Math.max(0, n.longValue()) : 0;
    }
}
