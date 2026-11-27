package org.agentos.assistant;

import java.io.IOException;
import java.util.*;

/** The exact target predicates shared by native AX frames and captured-frame host tests. */
final class DiningPollSendTarget {
    record Row(
            String name,
            String packageName,
            String labelClass,
            boolean insideResultList,
            boolean clickable,
            boolean enabled,
            boolean clickAction) {}

    record SearchNode(
            String id, String packageName, int windowId, boolean visible, boolean refreshed) {}

    /** Only a proved Messages ancestry is excluded; an unknown same-name label is never ignored. */
    static boolean messageSearchResult(
            String labelClass, int resultWindowId, boolean reachedResultList, List<SearchNode> path)
            throws IOException {
        if (!reachedResultList
                || path == null
                || path.size() < 2
                || path.size() > 24
                || !"android.widget.TextView".equals(labelClass))
            throw new IOException("POLL_GROUP_TARGET_UNVERIFIED");
        String prefix = "com.whatsapp:id/";
        if (path.get(0) == null
                || path.get(path.size() - 1) == null
                || !(prefix + "conversations_row_contact_name").equals(path.get(0).id())
                || !(prefix + "result_list").equals(path.get(path.size() - 1).id()))
            throw new IOException("POLL_GROUP_TARGET_UNVERIFIED");
        int contacts = 0, messages = 0;
        for (int i = 0; i < path.size(); i++) {
            SearchNode node = path.get(i);
            if (node == null
                    || !node.refreshed()
                    || !node.visible()
                    || node.windowId() != resultWindowId
                    || !"com.whatsapp".equals(node.packageName()))
                throw new IOException("POLL_GROUP_TARGET_UNVERIFIED");
            if ((prefix + "contact_row_container").equals(node.id())) contacts++;
            if ((prefix + "search_message_container_header").equals(node.id())) messages++;
            if (i < path.size() - 1 && (prefix + "result_list").equals(node.id()))
                throw new IOException("POLL_GROUP_TARGET_UNVERIFIED");
        }
        if (contacts == 1 && messages == 0) return false;
        if (contacts == 0 && messages == 1) return true;
        throw new IOException("POLL_GROUP_TARGET_UNVERIFIED");
    }

    static int uniqueResult(
            String query, String target, String listClass, boolean scrollable, List<Row> rows)
            throws IOException {
        String readback = query.startsWith("\u200b") ? query.substring(1) : query;
        if (!readback.equals(target)) throw new IOException("POLL_SEARCH_NOT_READY");
        if (!"androidx.recyclerview.widget.RecyclerView".equals(listClass) || scrollable)
            throw new IOException("POLL_GROUP_AMBIGUOUS");
        int match = -1;
        for (int i = 0; i < rows.size(); i++)
            if (target.equals(rows.get(i).name())) {
                if (match >= 0) throw new IOException("POLL_GROUP_AMBIGUOUS");
                match = i;
            }
        if (match < 0) throw new IOException("POLL_GROUP_TARGET_UNVERIFIED");
        Row row = rows.get(match);
        if (!"com.whatsapp".equals(row.packageName())
                || !"android.widget.TextView".equals(row.labelClass())
                || !row.insideResultList()
                || !row.clickable()
                || !row.enabled()
                || !row.clickAction()) throw new IOException("POLL_GROUP_TARGET_UNVERIFIED");
        return match;
    }

    record SearchSectionItem(
            String id,
            String className,
            String packageName,
            int windowId,
            boolean refreshed,
            boolean visible,
            boolean fullyVisible,
            int top,
            int bottom,
            List<String> titles,
            int names,
            boolean pollFilter) {}

    record SearchSections(
            int windowId,
            boolean refreshed,
            boolean visible,
            boolean backward,
            List<SearchSectionItem> items) {}

    /** A scrolling Messages section must not hide another Chats result. */
    static boolean completeChats(SearchSections sections, int rows) {
        if (sections == null
                || !sections.refreshed()
                || !sections.visible()
                || sections.backward()
                || sections.items() == null
                || sections.items().size() < 3
                || rows < 1) return false;
        String prefix = "com.whatsapp:id/";
        int i = 0, count = 0, lastTop = Integer.MIN_VALUE;
        SearchSectionItem first = sections.items().get(0);
        if (first != null && "android.widget.HorizontalScrollView".equals(first.className())) {
            if (!sectionItem(first, sections.windowId())
                    || !first.id().isEmpty()
                    || !first.pollFilter()
                    || !first.titles().isEmpty()
                    || first.names() != 0) return false;
            lastTop = first.top();
            i++;
        }
        if (i >= sections.items().size()) return false;
        SearchSectionItem chats = sections.items().get(i++);
        if (!sectionItem(chats, sections.windowId())
                || !chats.id().isEmpty()
                || !"android.widget.FrameLayout".equals(chats.className())
                || !chats.titles().equals(List.of("CHATS"))
                || chats.names() != 0
                || chats.top() < lastTop) return false;
        lastTop = chats.top();
        for (; i < sections.items().size(); i++) {
            SearchSectionItem item = sections.items().get(i);
            if (!sectionItem(item, sections.windowId()) || item.top() < lastTop) return false;
            lastTop = item.top();
            if (item.id().isEmpty()
                    && "android.widget.FrameLayout".equals(item.className())
                    && item.titles().equals(List.of("MESSAGES"))
                    && item.names() == 0) return count == rows;
            if (!(prefix + "contact_row_container").equals(item.id())
                    || !"android.widget.RelativeLayout".equals(item.className())
                    || !item.titles().isEmpty()
                    || item.names() != 1
                    || item.pollFilter()) return false;
            count++;
        }
        return false;
    }

    private static boolean sectionItem(SearchSectionItem item, int window) {
        return item != null
                && item.refreshed()
                && item.visible()
                && item.fullyVisible()
                && item.windowId() == window
                && "com.whatsapp".equals(item.packageName())
                && item.id() != null
                && item.titles() != null
                && item.bottom() > item.top();
    }

    static int uniqueResult(
            String query,
            String target,
            String listClass,
            boolean scrollable,
            List<Row> rows,
            SearchSections sections)
            throws IOException {
        return uniqueResult(
                query,
                target,
                listClass,
                scrollable && !completeChats(sections, rows.size()),
                rows);
    }

    static void requireGroupActivity(String activity) throws IOException {
        if (!activity.equals("com.whatsapp.chatinfo.group.GroupChatInfoActivity"))
            throw new IOException("POLL_GROUP_TYPE_UNVERIFIED");
    }

    static void emptyComposer(
            String text,
            boolean showingHint,
            int sendCount,
            int voiceCount,
            String voiceClass,
            boolean voiceEnabled)
            throws IOException {
        if (sendCount != 0) throw new IOException("POLL_EXISTING_DRAFT_PRESERVED");
        if (voiceCount != 1 || !"android.widget.ImageButton".equals(voiceClass) || !voiceEnabled)
            throw new IOException("POLL_COMPOSER_UNVERIFIED");
        // WhatsApp's custom placeholder omits the Android hint flag. Only its exact
        // observed text plus the empty-composer voice/send signature may stand for empty.
        if (!showingHint && !text.isEmpty() && !text.equals("发消息"))
            throw new IOException("POLL_EXISTING_DRAFT_PRESERVED");
    }

    static void emptySearch(
            String text, boolean showingHint, int clearCount, boolean blankSearchView)
            throws IOException {
        if (clearCount != 0) throw new IOException("POLL_SEARCH_UNVERIFIED");
        if (showingHint || text.isEmpty()) return;
        if (text.equals("Search…") && blankSearchView) return;
        throw new IOException("POLL_SEARCH_UNVERIFIED");
    }

    static void control(
            boolean refreshed,
            boolean visible,
            boolean enabled,
            String actualClass,
            String expectedClass,
            boolean clickable,
            boolean actionPresent,
            int action)
            throws IOException {
        if (!refreshed) throw new IOException("POLL_CONTROL_CHANGED__REFRESH");
        if (!visible) throw new IOException("POLL_CONTROL_CHANGED__NOT_VISIBLE");
        if (!enabled) throw new IOException("POLL_CONTROL_CHANGED__DISABLED");
        if (!expectedClass.equals(actualClass))
            throw new IOException("POLL_CONTROL_CHANGED__CLASS");
        if (!clickable) throw new IOException("POLL_CONTROL_CHANGED__NOT_CLICKABLE");
        if (!actionPresent) throw new IOException("POLL_CONTROL_CHANGED__MISSING_ACTION_" + action);
    }

    static void requireForm(
            String question,
            List<String> options,
            boolean checked,
            String expectedQuestion,
            List<String> expected)
            throws IOException {
        if (!expectedQuestion.equals(question)) throw new IOException("POLL_QUESTION_CHANGED");
        if (checked) throw new IOException("POLL_MODE_UNVERIFIED");
        if (options.size() < expected.size()) throw new IOException("POLL_OPTIONS_INCOMPLETE");
        for (int i = 0; i < options.size(); i++)
            if (!(i < expected.size() ? expected.get(i) : "").equals(options.get(i)))
                throw new IOException("POLL_OPTIONS_CHANGED");
    }

    static boolean settledForm(
            String question,
            List<String> options,
            boolean checked,
            String expectedQuestion,
            List<String> expected)
            throws IOException {
        boolean waiting = false;
        if (!expectedQuestion.equals(question)) {
            if (!question.isEmpty()) throw new IOException("POLL_QUESTION_CHANGED");
            waiting = true;
        }
        if (options.size() < expected.size()) waiting = true;
        for (int i = 0; i < options.size(); i++) {
            String wanted = i < expected.size() ? expected.get(i) : "", actual = options.get(i);
            if (!wanted.equals(actual)) {
                if (i < expected.size() && actual.isEmpty()) waiting = true;
                else throw new IOException("POLL_OPTIONS_CHANGED");
            }
        }
        return !waiting && !checked;
    }
}
