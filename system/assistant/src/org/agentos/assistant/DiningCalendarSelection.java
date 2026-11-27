package org.agentos.assistant;

import java.io.IOException;
import java.util.List;

/** A calendar choice is local to one operation and must still be eligible when used. */
final class DiningCalendarSelection {
    record Calendar(
            long id,
            String name,
            String account,
            String owner,
            String type,
            int primary,
            int access,
            int synced,
            int deleted) {}

    static boolean eligible(Calendar c) {
        return c.id() > 0
                && c.account() != null
                && !c.account().isBlank()
                && c.account().equalsIgnoreCase(c.owner())
                && "com.google".equals(c.type())
                && c.primary() == 1
                && c.access() >= 500
                && c.synced() == 1
                && c.deleted() == 0;
    }

    static Long requestedId(Object value) throws IOException {
        if (!(value instanceof Integer) && !(value instanceof Long))
            throw failed("DINING_CALENDAR_SELECTION_INVALID");
        long id = ((Number) value).longValue();
        if (id <= 0) throw failed("DINING_CALENDAR_SELECTION_INVALID");
        return id;
    }

    static Calendar choose(List<Calendar> calendars, Long requested) throws IOException {
        List<Calendar> eligible =
                calendars.stream().filter(DiningCalendarSelection::eligible).toList();
        if (requested != null) {
            if (requested <= 0) throw failed("DINING_CALENDAR_SELECTION_INVALID");
            List<Calendar> matches = eligible.stream().filter(c -> c.id() == requested).toList();
            if (matches.size() != 1) throw failed("DINING_CALENDAR_SELECTION_INVALID");
            return matches.get(0);
        }
        if (eligible.isEmpty()) throw failed("DINING_CALENDAR_UNAVAILABLE");
        if (eligible.size() != 1) throw failed("DINING_CALENDAR_AMBIGUOUS");
        return eligible.get(0);
    }

    private static IOException failed(String code) {
        return new IOException(code);
    }

    private DiningCalendarSelection() {}
}
