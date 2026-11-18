package org.agentos.assistant;

import java.util.ArrayList;
import java.util.List;

/** Pure presentation of native turns. No synthetic messages, steps, clocks or completion. */
final class NativeTurnPresentation {
    static final class Block {
        final String key, status;
        final AgentConversation.Entry user;
        final List<AgentConversation.Entry> activities = new ArrayList<>();
        final List<AgentConversation.Entry> answers = new ArrayList<>();
        boolean active;

        Block(String key, String status, AgentConversation.Entry user) {
            this.key = key;
            this.status = status;
            this.user = user;
        }

        boolean terminal() {
            return status.equals("completed") || status.equals("failed") || status.equals("interrupted")
                    || status.equals("cancelled") || status.equals("canceled");
        }

        int failures() {
            int count = 0;
            for (AgentConversation.Entry entry : activities)
                if (entry.kind == AgentConversation.Kind.TOOL
                        && NativeToolPresentation.status(entry.item).equals("Failed")) count++;
            return count;
        }

        String stateLabel() {
            if (status.equals("failed")) return "This turn failed";
            if (status.equals("interrupted") || status.equals("cancelled") || status.equals("canceled"))
                return "This turn stopped";
            if (active) {
                for (int i = activities.size() - 1; i >= 0; i--) {
                    AgentConversation.Entry entry = activities.get(i);
                    if (entry.kind == AgentConversation.Kind.TOOL
                            && NativeToolPresentation.status(entry.item).equals("In progress"))
                        return "In progress" + NativeToolPresentation.title(entry.item);
                }
                return answers.isEmpty() ? "Waiting for reply…" : "Replying…";
            }
            return status.equals("completed") ? "Execution ended" : "Execution log";
        }

        String answerText() {
            StringBuilder text = new StringBuilder();
            for (AgentConversation.Entry entry : answers) {
                if (text.length() > 0) text.append("\n\n");
                text.append(visibleText(entry.text, active));
            }
            return text.toString();
        }
    }

    static List<Block> blocks(AgentConversation conversation) {
        List<Block> result = new ArrayList<>();
        for (AgentConversation.Turn turn : conversation.turns) {
            String anchor = "start";
            List<AgentConversation.Entry> segment = new ArrayList<>();
            for (AgentConversation.Entry entry : turn.entries) {
                if (entry.kind == AgentConversation.Kind.USER) {
                    add(result, turn, anchor, segment, false);
                    result.add(new Block("user/" + entry.key(), turn.status, entry));
                    segment.clear();
                    anchor = entry.id;
                } else segment.add(entry);
            }
            add(result, turn, anchor, segment, true);
        }
        return result;
    }

    private static void add(List<Block> result, AgentConversation.Turn turn, String anchor,
            List<AgentConversation.Entry> entries, boolean tail) {
        boolean active = tail && turn.status.equals("inProgress");
        boolean stoppedOrFailed = tail && (turn.status.equals("failed") || turn.status.equals("interrupted")
                || turn.status.equals("cancelled") || turn.status.equals("canceled"));
        // A stopped turn can end before any assistant item exists. Preserve that native state,
        // but never insert an empty status block before the first user/steer message.
        if (entries.isEmpty() && !active && !stoppedOrFailed) return;
        Block block = new Block("turn/" + turn.id + "/after/" + anchor, turn.status, null);
        block.active = active;
        int lastTool = -1;
        for (int i = 0; i < entries.size(); i++)
            if (entries.get(i).kind == AgentConversation.Kind.TOOL) lastTool = i;
        for (int i = 0; i < entries.size(); i++) {
            AgentConversation.Entry entry = entries.get(i);
            Object phaseValue = entry.item.opt("phase");
            String phase = phaseValue instanceof String ? (String) phaseValue : "";
            // New runtimes supply phase. Older records only allow structural fallback:
            // unphased text preceding a tool is commentary; trailing text stays visible.
            boolean commentary = entry.kind == AgentConversation.Kind.ASSISTANT
                    && (phase.equals("commentary") || phase.isEmpty() && i < lastTool);
            if (entry.kind == AgentConversation.Kind.TOOL || commentary) block.activities.add(entry);
            else block.answers.add(entry);
        }
        result.add(block);
    }

    /** Show even one Chinese character immediately. Hold only an unfinished UTF-16 pair. */
    static String visibleText(String text, boolean active) {
        if (active && !text.isEmpty() && Character.isHighSurrogate(text.charAt(text.length() - 1)))
            return text.substring(0, text.length() - 1);
        return text;
    }
}
