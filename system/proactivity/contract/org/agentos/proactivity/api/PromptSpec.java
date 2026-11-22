package org.agentos.proactivity.api;

/** Optional user choice. Actions are fixed to accept/cancel; no executable target is declared. */
public record PromptSpec(String acceptLabel, String declineLabel) {
    public PromptSpec {
        label(acceptLabel); label(declineLabel);
    }
    private static void label(String value) {
        if(value==null || value.isBlank() || value.length()>80)
            throw new IllegalArgumentException("Invalid prompt label");
    }
}
