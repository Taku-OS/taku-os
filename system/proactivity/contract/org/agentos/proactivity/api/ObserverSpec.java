package org.agentos.proactivity.api;

/** Public v1 settled-event rule. IDs and event types belong to the producer package. */
public record ObserverSpec(String id, String name, String eventType, long quietMs,
        long cooldownMs, int maxRecords, long lookbackMs, String title, String summary) {
    public ObserverSpec {
        identifier(id); identifier(eventType);
        text(name,80); text(title,80); text(summary,240);
        if(quietMs<100 || quietMs>10000 || cooldownMs<1000 || cooldownMs>3600000
                || maxRecords<1 || maxRecords>16 || lookbackMs<1000 || lookbackMs>600000)
            throw new IllegalArgumentException("Rule limits are outside v1 capabilities");
    }
    public static void identifier(String value) {
        if(value==null || !value.matches("[A-Za-z][A-Za-z0-9_.-]{2,119}"))
            throw new IllegalArgumentException("Invalid identifier");
    }
    private static void text(String value,int max) {
        if(value==null || value.isBlank() || value.length()>max)
            throw new IllegalArgumentException("Invalid display text");
    }
}
