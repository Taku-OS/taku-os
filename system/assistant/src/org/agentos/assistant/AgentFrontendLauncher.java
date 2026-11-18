package org.agentos.assistant;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;

/** Opens the Taku Agent chat (org.agentos.agent) for a thread, or reports that it cannot. */
final class AgentFrontendLauncher {
    static final String RETURN_AGENT_FRONTEND = "returnAgentFrontend";
    static final String RETURN_CONVERSATION_ID = "returnConversationId";

    private AgentFrontendLauncher() {}

    static void openHome(Context context) {
        if (context.getPackageManager().checkSignatures(context.getPackageName(), AgentFrontendRoute.PACKAGE)
                != PackageManager.SIGNATURE_MATCH) throw new IllegalStateException("Taku frontend unavailable");
        context.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setClassName(AgentFrontendRoute.PACKAGE, "org.agentos.agent.home.HomeActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }

    static boolean matchesWorkspaceReturn(boolean requested, String leaseThread, String returnThread) {
        return requested && returnThread != null
                && returnThread.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
                && returnThread.equals(leaseThread);
    }

    /** Returns from a gateway-opened workspace without reinterpreting a fresh assistant invocation. */
    static boolean returnFromWorkspace(Context context, boolean requested, String leaseThread, String returnThread) {
        if (!matchesWorkspaceReturn(requested, leaseThread, returnThread)) return false;
        return start(context, returnThread);
    }

    /**
     * Every ordinary assistant entry (launcher icon, ASSIST intent, assist key) opens the chat.
     * Explicit gesture-handle entries use openGesture instead. Calls that need native context ([AgentFrontendRoute#ordinary])
     * return false so the caller shows the native session instead.
     */
    static boolean openConversation(Context context, Bundle arguments) {
        try {
            Bundle args = arguments == null ? Bundle.EMPTY : arguments;
            SharedPreferences preferences = context.getSharedPreferences("agent-settings", 0);
            String requested = args.getString("conversationId", "");
            String thread = requested.isEmpty() ? preferences.getString("conversation", "") : requested;
            if (!AgentFrontendRoute.ordinary(args.keySet(), preferences.getAll(), thread)) return false;
            return start(context, thread);
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    /** Dedicated system-handle entry. Native assist context/drafts remain untouched. */
    static boolean openGesture(Context context, Bundle arguments, java.util.function.Consumer<Intent> launch) {
        if (arguments == null) return false;
        String trigger = arguments.getString(AgentFrontendRoute.EXTRA_TRIGGER, "");
        boolean voice = "nav_handle_long_press".equals(trigger);
        if (!voice && !"nav_handle_double_tap".equals(trigger)) return false;
        try {
            if (context.getPackageManager().checkSignatures(context.getPackageName(), AgentFrontendRoute.PACKAGE)
                    != PackageManager.SIGNATURE_MATCH) throw new IllegalStateException("Taku frontend unavailable");
            Intent intent = new Intent().setClassName(AgentFrontendRoute.PACKAGE,
                    "org.agentos.agent.home.assistant.AssistantOverlayActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra("mode", voice ? "voice" : "text");
            if (voice) {
                String id = arguments.getString("org.agentos.assistant.extra.GESTURE_ID", "");
                if (!id.matches("[A-Za-z0-9_-]{8,128}")) throw new IllegalArgumentException("Missing gesture id");
                intent.putExtra("gestureId", id);
            }
            launch.accept(intent);
        } catch (RuntimeException failure) {
            android.util.Log.w("TakuAssistant", "Gesture frontend unavailable", failure);
            android.widget.Toast.makeText(context, "Taku is unavailable. Please try again.", android.widget.Toast.LENGTH_SHORT).show();
        }
        return true; // An explicit gesture never falls back to a different UI or Google.
    }

    private static boolean start(Context context, String thread) {
        try {
            PackageManager packages = context.getPackageManager();
            if (packages.checkSignatures(context.getPackageName(), AgentFrontendRoute.PACKAGE)
                    != PackageManager.SIGNATURE_MATCH) return false;
            Intent launch = packages.getLaunchIntentForPackage(AgentFrontendRoute.PACKAGE);
            if (launch == null || launch.getComponent() == null
                    || !AgentFrontendRoute.PACKAGE.equals(launch.getComponent().getPackageName())) return false;
            if (thread != null && !thread.isEmpty()) launch.putExtra("threadId", thread);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            context.startActivity(launch);
            return true;
        } catch (RuntimeException unavailable) {
            // Installation, signature, or context changes never strand the system assistant entry.
            return false;
        }
    }
}
