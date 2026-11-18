/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.agentos.inputmethod.probe;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import org.json.JSONArray;
import org.json.JSONObject;

/** Read-only synthetic-emulator probe. Include the IME window, not just the active app. */
public final class UiProbe extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            UiAutomation automation = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            AccessibilityServiceInfo info = automation.getServiceInfo();
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
            automation.setServiceInfo(info);
            Thread.sleep(150);
            JSONArray nodes = new JSONArray(), windows = new JSONArray();
            for (AccessibilityWindowInfo window : automation.getWindows()) {
                AccessibilityNodeInfo root = window.getRoot();
                Rect bounds = new Rect(); window.getBoundsInScreen(bounds);
                windows.put(new JSONObject().put("id",window.getId()).put("type",window.getType())
                    .put("package",root==null?"":string(root.getPackageName()))
                    .put("bounds",bounds.flattenToString()));
                dump(root, nodes);
            }
            result.putString("nodes", nodes.toString());
            result.putString("windows", windows.toString());
            finish(Activity.RESULT_OK, result);
        } catch (Exception error) {
            result.putString("error", error.toString());
            finish(Activity.RESULT_CANCELED, result);
        }
    }
    private static String string(CharSequence value) { return value == null ? "" : value.toString(); }
    private static void dump(AccessibilityNodeInfo node, JSONArray nodes) throws Exception {
        if (node == null || nodes.length() > 5000) return;
        if (node.isVisibleToUser()) {
            Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
            nodes.put(new JSONObject().put("package", string(node.getPackageName()))
                .put("class", string(node.getClassName())).put("text", string(node.getText()))
                .put("content-desc", string(node.getContentDescription()))
                .put("hint", string(node.getHintText())).put("password", node.isPassword())
                .put("editable", node.isEditable())
                .put("resource-id", string(node.getViewIdResourceName()))
                .put("bounds", "[" + bounds.left + "," + bounds.top + "][" + bounds.right + "," + bounds.bottom + "]"));
        }
        for (int index = 0; index < node.getChildCount(); index++) dump(node.getChild(index), nodes);
    }
}
