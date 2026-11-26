package org.agentos.assistant;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Confirmed native receipts rendered with the existing Home feed vocabulary, never a second feed.
 */
final class DiningReservationFeedCard {
    private DiningReservationFeedCard() {}

    /**
     * Actions must be supplied by the installed native edit/share adapters. This factory
     * deliberately has no made-up deep link or default button with an unconnected action.
     */
    static Map<String, Object> create(
            DiningPollBookingFlow.State state,
            Map<String, Object> editIntent,
            Map<String, Object> shareIntent) {
        DiningPollBookingFlow.Receipt receipt = DiningPollBookingFlow.receipt(state);
        editIntent = intent(editIntent);
        shareIntent = intent(shareIntent);
        Map<String, Object> elements = new LinkedHashMap<>();
        elements.put(
                "header",
                node("AppHeader", map("title", "OpenTable", "trailing", "Reservation confirmed")));
        elements.put(
                "name",
                node(
                        "Text",
                        map(
                                "text",
                                receipt.restaurantName(),
                                "variant",
                                "body-xl-medium",
                                "lines",
                                2)));
        elements.put(
                "when",
                node(
                        "Text",
                        map(
                                "text",
                                scheduled(receipt),
                                "variant",
                                "body-md",
                                "tone",
                                "secondary")));
        List<String> copy = new ArrayList<>(List.of("name", "when"));
        // Generic agent cards are readable through feed/list. Keep the booking-management
        // code in the native receipt and the user's explicit share preview.
        String detail = receipt.seating();
        if (!detail.isEmpty()) {
            elements.put(
                    "detail",
                    node("Text", map("text", detail, "variant", "body-sm", "tone", "tertiary")));
            copy.add("detail");
        }
        elements.put("copy", node("Column", map("gap", 6, "flex", 1), copy.toArray(String[]::new)));
        List<String> content = new ArrayList<>();
        if (!receipt.photoUrl().isEmpty()) {
            elements.put(
                    "photo",
                    node(
                            "Image",
                            map(
                                    "src",
                                    receipt.photoUrl(),
                                    "alt",
                                    receipt.restaurantName(),
                                    "width",
                                    80,
                                    "height",
                                    80,
                                    "radius",
                                    12,
                                    "fit",
                                    "cover")));
            content.add("photo");
        }
        content.add("copy");
        elements.put(
                "content",
                node(
                        "Row",
                        map("gap", 12, "padding", 12, "align", "center"),
                        content.toArray(String[]::new)));
        elements.put("inset", node("Inset", map("fill", "content-100", "radius", 12), "content"));
        elements.put("edit", button("Open OpenTable", "secondary", "edit"));
        elements.put("share", button("Share to group", "primary", "share"));
        elements.put("buttons", node("Row", map("gap", 8), "edit", "share"));
        elements.put("card", node("Column", map("gap", 12), "header", "inset", "buttons"));
        String id =
                "dining.reservation."
                        + DiningPollBookingFlow.key("card", state.booking().key()).substring(5);
        long createdAt = receipt.evidence().capturedAt().toEpochMilli();
        return map(
                "schema",
                "taku.feed-card/1",
                "id",
                id,
                "source",
                map("id", "agent", "app", "OpenTable", "package", "com.opentable"),
                "layout",
                map("span", "full", "surface", "card"),
                "rank",
                2.0 + createdAt * 1e-14,
                "createdAt",
                createdAt,
                "pinned",
                false,
                "summary",
                "Reservation confirmed: " + receipt.restaurantName() + " · " + scheduled(receipt),
                "pin",
                map(
                        "label",
                        cut(receipt.restaurantName(), 24),
                        "detail",
                        cut(scheduled(receipt), 32),
                        "icon",
                        map(
                                "kind",
                                "image",
                                "src",
                                "asset:feed/icons/check.svg",
                                "shape",
                                "rounded")),
                "ui",
                map(
                        "catalog",
                        "taku-feed-1",
                        "spec",
                        map("root", "card", "elements", elements),
                        "state",
                        map()),
                "actions",
                map(
                        "edit",
                        map("label", "Open OpenTable", "intent", editIntent),
                        "share",
                        map("label", "Share to group", "intent", shareIntent)));
    }

    /** A real WhatsApp preview may use this caption; creating it is not sending it. */
    static String shareCaption(DiningPollBookingFlow.State state) {
        DiningPollBookingFlow.Receipt receipt = DiningPollBookingFlow.receipt(state);
        String text =
                "Reservation confirmed: "
                        + receipt.restaurantName()
                        + "\n"
                        + scheduled(receipt)
                        + " (restaurant local time, "
                        + receipt.when().getZone().getId()
                        + ")";
        if (!receipt.seating().isEmpty()) text += "\n" + receipt.seating();
        if (!receipt.confirmationCode().isEmpty())
            text += "\nConfirmation: " + receipt.confirmationCode();
        if (!receipt.restaurantUrl().isEmpty()) text += "\n" + receipt.restaurantUrl();
        return text;
    }

    /** Opens WhatsApp's recipient picker; only the user can select a destination and send. */
    static Map<String, Object> whatsappShareIntent(DiningPollBookingFlow.State state) {
        DiningPollBookingFlow.Receipt receipt = DiningPollBookingFlow.receipt(state);
        String text =
                "Reservation confirmed: "
                        + receipt.restaurantName()
                        + "\n"
                        + scheduled(receipt)
                        + " (restaurant local time, "
                        + receipt.when().getZone().getId()
                        + ")";
        return map("type", "share-text", "package", "com.whatsapp", "text", text);
    }

    private static String scheduled(DiningPollBookingFlow.Receipt receipt) {
        return receipt.when().format(DateTimeFormatter.ofPattern("yyyy-MM-dd · HH:mm", Locale.ROOT))
                + " · "
                + receipt.people()
                + " guests";
    }

    private static Map<String, Object> intent(Map<String, Object> value) {
        if (value == null || !(value.get("type") instanceof String type))
            throw new DiningPollBookingFlow.Invalid("ACTION_ADAPTER_REQUIRED");
        String required =
                switch (type) {
                    case "ask-taku" -> "prompt";
                    case "share-text" -> "text";
                    case "open-app" -> "package";
                    case "open-url" -> "url";
                    case "panel" -> "method";
                    default -> throw new DiningPollBookingFlow.Invalid("ACTION_ADAPTER_REQUIRED");
                };
        if (!(value.get(required) instanceof String text) || text.isBlank())
            throw new DiningPollBookingFlow.Invalid("ACTION_ADAPTER_REQUIRED");
        if (type.equals("share-text")
                && (!"com.whatsapp".equals(value.get("package")) || text.length() > 16_000))
            throw new DiningPollBookingFlow.Invalid("ACTION_ADAPTER_REQUIRED");
        if (type.equals("open-url") && !text.startsWith("https://"))
            throw new DiningPollBookingFlow.Invalid("INVALID_SOURCE_URL");
        if (type.equals("panel")
                && (!List.of("panels/dining-poll/share", "panels/dining-poll/edit").contains(text)
                        || !(value.get("args") instanceof Map<?, ?>)))
            throw new DiningPollBookingFlow.Invalid("ACTION_ADAPTER_REQUIRED");
        return Map.copyOf(value);
    }

    private static Map<String, Object> button(String label, String variant, String action) {
        Map<String, Object> node =
                new LinkedHashMap<>(
                        node("Button", map("label", label, "variant", variant, "flex", 1)));
        node.put("on", map("press", map("action", "dispatch", "params", map("actionId", action))));
        return Map.copyOf(node);
    }

    private static Map<String, Object> node(
            String type, Map<String, Object> props, String... children) {
        return map("type", type, "props", props, "children", List.of(children));
    }

    private static String cut(String value, int limit) {
        if (value.length() <= limit) return value;
        int end = limit;
        if (Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end);
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) values.put((String) pairs[i], pairs[i + 1]);
        return java.util.Collections.unmodifiableMap(values);
    }
}
