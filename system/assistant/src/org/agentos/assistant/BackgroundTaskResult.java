package org.agentos.assistant;

/** A failed or cancelled task must not be presented as its last optimistic progress message. */
final class BackgroundTaskResult {
    static String message(String status,String stateMessage,String reply) {
        String state=stateMessage==null?"":stateMessage;
        String lastReply=reply==null?"":reply;
        if((status.equals("failed") || status.equals("cancelled")) && !state.isBlank())return state;
        return lastReply.isBlank()?state:lastReply;
    }
}
