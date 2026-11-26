package org.agentos.assistant;

import java.io.IOException;
import java.util.*;

/** A bounded census of native RecyclerView adapter positions, never recycled View identities. */
final class DiningPollGroupSearch {
    static final String INCOMPLETE = "POLL_GROUP_SEARCH_INCOMPLETE";

    enum Kind {
        FILTER,
        CHATS,
        CHAT,
        MESSAGES,
        MESSAGE,
        UNKNOWN
    }

    enum Move {
        FORWARD,
        BACKWARD,
        VERIFIED
    }

    record Item(int position, Kind kind, String name, String fingerprint, boolean fullyVisible) {}

    record Page(
            String query,
            int window,
            int total,
            boolean backward,
            boolean forward,
            List<Item> items) {}

    /** Only a not-yet-populated native result list is retryable; proof changes are not. */
    static final class Pending extends IOException {
        Pending() {
            super(INCOMPLETE);
        }
    }

    static final class Stable {
        private String previous;

        boolean accept(String stamp, String prior, boolean moved) {
            if (stamp == null) {
                previous = null;
                return false;
            }
            boolean ready = (!moved || !stamp.equals(prior)) && stamp.equals(previous);
            previous = stamp;
            return ready;
        }
    }

    interface Read<T> {
        T get() throws Exception;
    }

    interface Build<S, T> {
        T get(S source) throws Exception;
    }

    interface Check<T> {
        void check(T page) throws Exception;
    }

    interface Stamp<T> {
        String get(T page);
    }

    interface Now {
        long get();
    }

    interface Pause {
        void sleep(long millis) throws Exception;
    }

    /**
     * Row ancestry can be transient during a RecyclerView scroll; no other proof uses this
     * conversion.
     */
    static <T> T pageAncestry(Read<T> read) throws Exception {
        try {
            return read.get();
        } catch (IOException layout) {
            if (!"POLL_GROUP_TARGET_UNVERIFIED".equals(layout.getMessage())) throw layout;
            throw new Pending();
        }
    }

    static final class Identity {
        private final String target;
        private int window;

        Identity(String target, int window) {
            this.target = target;
            this.window = window;
        }

        void check(String query, int currentWindow) throws IOException {
            if (!target.equals(query)
                    || currentWindow < 0
                    || window >= 0 && window != currentWindow) throw incomplete();
            window = currentWindow;
        }
    }

    /** Hard source/identity checks and final validation never enter the layout retry catch. */
    static <S, T> T settle(
            Read<S> source,
            Build<S, T> build,
            Check<T> validate,
            Stamp<T> stamp,
            Now now,
            Pause pause,
            long deadline,
            String prior,
            boolean moved)
            throws Exception {
        Stable stable = new Stable();
        while (now.get() < deadline) {
            S current = source.get();
            T page;
            try {
                page = build.get(current);
            } catch (IOException layout) {
                if (!INCOMPLETE.equals(layout.getMessage())) throw layout;
                stable.accept(null, prior, moved);
                pause.sleep(90);
                continue;
            }
            validate.check(page);
            if (stable.accept(stamp.get(page), prior, moved)) return page;
            pause.sleep(90);
        }
        throw incomplete();
    }

    static final class Census {
        private final String target;
        private final NavigableMap<Integer, Item> rows = new TreeMap<>();
        private final Set<Integer> reverse = new HashSet<>();
        private int window = -1, total = -1, chats = -1, end = -1, match = -1, pages;
        private boolean returning, verified;

        Census(String target) {
            this.target = target;
        }

        Move accept(Page page) throws IOException {
            validate(page);
            if (++pages > 30) throw incomplete();
            if (chats < 0) {
                if (page.backward()) throw incomplete();
                for (Item item : page.items())
                    if (item.kind() == Kind.CHATS) {
                        if (chats >= 0) throw incomplete();
                        chats = item.position();
                    }
                if (chats < 0) throw incomplete();
            }
            for (Item item : page.items()) {
                if (item.position() < chats) {
                    if (item.kind() != Kind.FILTER) throw incomplete();
                    continue;
                }
                if (item.position() == chats) {
                    if (item.kind() != Kind.CHATS) throw incomplete();
                    continue;
                }
                if (item.kind() == Kind.MESSAGES) {
                    if (end >= 0 && end != item.position()) throw incomplete();
                    end = item.position();
                    break;
                }
                if (end >= 0 && item.position() >= end) break;
                if (item.kind() != Kind.CHAT) throw incomplete();
                Item before = rows.get(item.position());
                if (before != null && !same(before, item)) throw incomplete();
                if (returning && before == null) throw incomplete();
                rows.put(item.position(), item);
                if (target.equals(item.name())) {
                    if (match >= 0 && match != item.position())
                        throw new IOException("POLL_GROUP_AMBIGUOUS");
                    match = item.position();
                }
                if (returning) reverse.add(item.position());
            }
            if (end < 0
                    && !page.forward()
                    && !page.items().isEmpty()
                    && page.items().get(page.items().size() - 1).position() == total - 1)
                end = total;
            if (!returning) {
                if (end < 0) {
                    if (!page.forward()) throw incomplete();
                    return Move.FORWARD;
                }
                requireComplete(rows.keySet());
                if (match < 0) throw new IOException("POLL_GROUP_TARGET_UNVERIFIED");
                returning = true;
                for (Item item : page.items())
                    if (item.position() > chats && item.position() < end)
                        reverse.add(item.position());
            }
            if (page.backward()) return Move.BACKWARD;
            boolean header =
                    page.items().stream()
                            .anyMatch(
                                    item -> item.position() == chats && item.kind() == Kind.CHATS);
            if (!header) throw incomplete();
            requireComplete(reverse);
            verified = true;
            return Move.VERIFIED;
        }

        int targetPosition() {
            return match;
        }

        /** Re-read the original adapter position and semantics before the only row click. */
        Item selection(Page page) throws IOException {
            if (!verified) throw incomplete();
            validate(page);
            Item selected = null;
            for (Item item : page.items()) {
                if (item.position() <= chats || item.position() >= end) continue;
                Item before = rows.get(item.position());
                if (before == null || !same(before, item)) throw incomplete();
                if (item.position() == match && item.fullyVisible()) selected = item;
            }
            return selected;
        }

        private void requireComplete(Set<Integer> positions) throws IOException {
            if (end <= chats || end > total || end - chats > 256) throw incomplete();
            for (int i = chats + 1; i < end; i++) if (!positions.contains(i)) throw incomplete();
        }

        private void validate(Page page) throws IOException {
            String query = page.query();
            if (query != null && query.startsWith("\u200b")) query = query.substring(1);
            if (!target.equals(query)
                    || page.window() < 0
                    || page.total() < 1
                    || page.total() > 2000
                    || page.items() == null
                    || page.items().isEmpty()) throw incomplete();
            if (window < 0) {
                window = page.window();
                total = page.total();
            }
            if (window != page.window() || total != page.total()) throw incomplete();
            int prior = -1;
            for (Item item : page.items()) {
                if (item == null
                        || item.position() <= prior
                        || item.position() < 0
                        || item.position() >= total
                        || item.kind() == null
                        || item.name() == null
                        || item.fingerprint() == null
                        || item.fingerprint().isEmpty()) throw incomplete();
                prior = item.position();
            }
        }
    }

    private static boolean same(Item a, Item b) {
        return a.position() == b.position()
                && a.kind() == b.kind()
                && a.name().equals(b.name())
                && a.fingerprint().equals(b.fingerprint());
    }

    static IOException incomplete() {
        return new IOException(INCOMPLETE);
    }
}
