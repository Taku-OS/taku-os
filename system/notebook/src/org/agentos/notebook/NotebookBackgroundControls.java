package org.agentos.notebook;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.agentos.ai.NotebookSocialSource;
import org.json.JSONArray;
import org.json.JSONObject;

final class NotebookBackgroundControls {
    private static final String DISPLAY_PREFIX = "Taku-Independent-Screen-notebook-";
    private static final Set<String> SHARE_PACKAGES = Set.of(
            "android", "com.android.intentresolver", "com.android.systemui", "com.google.android.intentresolver");
    private static final Set<String> POST_SHARE_VIEWS = Set.of(
            "com.instagram.android:id/row_feed_button_share", "com.instagram.android:id/clips_ufi_share_button",
            "com.xingin.xhs:id/note_detail_share_layout", "com.xingin.xhs:id/note_detail_share_iv",
            "com.xingin.xhs:id/matrix_detail_feed_item_share_image_view", "com.xingin.xhs:id/moreoperateiv");
    private static final Set<String> SEARCH_EDITORS = Set.of("com.zhiliaoapp.musically:id/hy0");
    private static final String TIKTOK = "com.zhiliaoapp.musically";
    private static final String TIKTOK_SEARCH_SUBMIT = "com.zhiliaoapp.musically:id/tv_search_textview";
    private static final String TIKTOK_PROFILE_RESULT = "com.zhiliaoapp.musically:id/vjw";
    private static final String TIKTOK_VIDEO_RESULT = "com.zhiliaoapp.musically:id/vss";
    private static final String TIKTOK_PROFILE_VIDEO = "com.zhiliaoapp.musically:id/user_video_container_layout";
    private static final String TIKTOK_RESULT_CAPTION = "com.zhiliaoapp.musically:id/desc";
    private static final String REDNOTE_SEARCH_CARD = "com.xingin.xhs:id/searchnotecard";
    private static final String REDNOTE_SEARCH_TITLE = "com.xingin.xhs:id/notetitle";
    private static final String REDNOTE_SEARCH_AUTHOR = "com.xingin.xhs:id/authorname";
    private static final Pattern COPY = Pattern.compile(
            "^(?:copy link|copy video link|copy post link|复制链接|複製連結|複製鏈接|复制口令)(?:[.!。])?$");
    private static final Pattern SHARE = Pattern.compile(
            "^(?:share(?: this)?(?: (?:post|video|reel|note|tweet|short))?|分享(?:视频|帖子|笔记|影片|貼文)?)"
            + "(?:[.,，。]\\s*[\\d.,，\\s万千亿]+(?:shares?|次分享))?(?:[.!。])?$");
    private static final Pattern READ_NAVIGATION = Pattern.compile(
            "^(?:search(?: and explore| in .{1,45})?|you|videos?|posts|reels|account|profile|shorts|users|your activity|"
            + "(?:watch|browsing|viewing) history|history|settings and (?:activity|privacy)|see all|"
            + "搜索(?:笔记|视频|用户)?|搜尋|影片|视频|短视频|短片|用户|帖子|貼文|筆記|笔记|我的|你|个人资料|個人檔案|"
            + "观看记录|瀏覽記錄|浏览记录|活動紀錄|你的动态|你的動態|设置与隐私|設定和隱私)$");
    private static final Pattern MUTATING = Pattern.compile(
            "^(?:follow|unfollow|subscribe|like|unlike|repost|retweet|send|message|reply|comment|post|log in|"
            + "sign in|sign up|create|save|add to|delete|report|buy|book|block|allow|agree|accept|"
            + "关注|關注|订阅|訂閱|点赞|按讚|发送|傳送|发帖|私信|评论|留言|转发|转载|登录|登入|"
            + "保存|收藏|删除|全部删除|全部刪除|刪除|購買|购买|预订|允许|同意)(?:$|[ ,:：，].*|[一-龥].*)");
    private static final Pattern RESULT_VIEW = Pattern.compile(
            "(?:result|thumbnail|post_title|reel_title|note_title|video_title|media_preview|"
            + "user_row|user_result|author_name|channel_name|profile_name|grid_item|reel_item|"
            + "note_item|post_item|video_item|video_card)");
    private static final Pattern ACTION_VIEW = Pattern.compile(
            "(?:like|favorite|favourite|subscribe|follow|comment|send|direct_message|repost|"
            + "reaction|login|signup|edit|save|delete|trash|report|purchase|agree|allow)");
    private static final Pattern POST_TEXT = Pattern.compile(
            "(?:video_title|reel_caption|post_title|post_text|post_content|note_title|note_content|"
            + "tweet_text|author_name|channel_name|user_name)");
    private static final int MAX_NODES = 850;
    private static final int MAX_CONTROLS = 65;

    private NotebookBackgroundControls() {}

    static JSONObject call(AccessibilityService service, JSONObject input) throws Exception {
        if (service == null) throw new IOException("NOTEBOOK_SCREEN_SERVICE_UNAVAILABLE");
        int displayId = input.getInt("displayId");
        String screenName = input.getString("screenName");
        var display = service.getSystemService(DisplayManager.class).getDisplay(displayId);
        if (displayId <= 0 || !screenName.startsWith(DISPLAY_PREFIX) || display == null
                || !screenName.equals(display.getName())) throw new IOException("INVALID_NOTEBOOK_BACKGROUND_SCREEN");
        String packageName = input.getString("packageName");
        var platform = NotebookSocialSource.byPackage(packageName);
        if (platform == null) throw new IOException("INVALID_SOURCE_PACKAGE");
        if (platform == NotebookSocialSource.X) service.clearCache();

        Snapshot snapshot = inspect(service.getWindowsOnAllDisplays().get(displayId), displayId, packageName);
        return switch (input.getString("action")) {
            case "observe" -> snapshot.toJson();
            case "click" -> click(service, displayId, packageName, snapshot, input);
            case "copyLink" -> copyLink(service, displayId, packageName, snapshot);
            case "query" -> query(snapshot, input);
            default -> throw new IllegalArgumentException("INVALID_NOTEBOOK_BACKGROUND_ACTION");
        };
    }

    private static JSONObject click(AccessibilityService service, int displayId, String packageName,
            Snapshot snapshot, JSONObject input) throws Exception {
        int index = input.getInt("id");
        if (!snapshot.token.equals(input.getString("token")) || index < 0 || index >= snapshot.controls.size()) {
            return new JSONObject().put("performed", false);
        }
        Control control = snapshot.controls.get(index);
        boolean performed = control.role.equals(input.getString("role"))
                && perform(service, displayId, packageName, snapshot, control);
        return new JSONObject().put("performed", performed);
    }

    private static JSONObject copyLink(AccessibilityService service, int displayId, String packageName,
            Snapshot snapshot) throws Exception {
        var controls = snapshot.controls.stream().filter(control -> "copy".equals(control.role)).toList();
        return new JSONObject().put("performed", controls.size() == 1
                && perform(service, displayId, packageName, snapshot, controls.get(0)));
    }

    private static boolean perform(AccessibilityService service, int displayId, String packageName,
            Snapshot snapshot, Control control) throws Exception {
        if (control.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        if (!control.tapFallback) return false;
        Snapshot current = inspect(service.getWindowsOnAllDisplays().get(displayId), displayId, packageName);
        return snapshot.token.equals(current.token) && tapButton(service, displayId, control.bounds);
    }

    private static boolean tapButton(AccessibilityService service, int displayId, Rect bounds) {
        var display = service.getSystemService(DisplayManager.class).getDisplay(displayId);
        if (display == null) return false;
        var size = new Point();
        display.getRealSize(size);
        if (bounds.centerX() < 0 || bounds.centerX() >= size.x || bounds.centerY() < 0 || bounds.centerY() >= size.y) return false;
        var touch = new Path();
        touch.moveTo(bounds.exactCenterX(), bounds.exactCenterY());
        var tap = new GestureDescription.Builder().setDisplayId(displayId)
                .addStroke(new GestureDescription.StrokeDescription(touch, 0, 60)).build();
        return service.dispatchGesture(tap, null, null);
    }

    private static JSONObject query(Snapshot snapshot, JSONObject input) throws Exception {
        String text = input.getString("text").strip();
        if (text.isEmpty() || text.length() > 180 || text.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("INVALID_NOTEBOOK_SEARCH_TEXT");
        }
        AccessibilityNodeInfo editor = snapshot.editor;
        if (editor == null || !snapshot.token.equals(input.getString("token"))) {
            return new JSONObject().put("performed", false).put("submitted", false);
        }
        Bundle arguments = new Bundle();
        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        if (!editor.isFocused()) editor.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        boolean performed = editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments);
        boolean submitted = performed && editor.performAction(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
        return new JSONObject().put("performed", performed).put("submitted", submitted);
    }

    private static Snapshot inspect(List<AccessibilityWindowInfo> windows, int displayId, String packageName)
            throws Exception {
        if (windows == null) return Snapshot.empty();
        for (var window : windows.stream().sorted(Comparator.comparingInt(AccessibilityWindowInfo::getLayer).reversed()).toList()) {
            if (window.getDisplayId() != displayId || window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue;
            var root = window.getRoot();
            if (root == null || root.getPackageName() == null) continue;
            String owner = root.getPackageName().toString();
            if (packageName.equals(owner) || SHARE_PACKAGES.contains(owner)) {
                var snapshot = inspectWindow(root, window.getId(), owner, packageName);
                if (packageName.equals(owner) || !snapshot.controls.isEmpty()) return snapshot;
                continue;
            }
            if (window.getType() == AccessibilityWindowInfo.TYPE_APPLICATION) return Snapshot.empty();
        }
        return Snapshot.empty();
    }

    private static Snapshot inspectWindow(AccessibilityNodeInfo root, int windowId, String owner, String packageName)
            throws Exception {
        var remaining = new ArrayDeque<Node>();
        var seen = new HashSet<AccessibilityNodeInfo>();
        var controls = new LinkedHashMap<AccessibilityNodeInfo, Control>();
        var editors = new ArrayList<AccessibilityNodeInfo>();
        var postText = new ArrayList<String>();
        remaining.add(new Node(root, null));
        boolean source = packageName.equals(owner);
        while (!remaining.isEmpty() && seen.size() < MAX_NODES) {
            var item = remaining.removeFirst();
            var node = item.value;
            if (!seen.add(node) || !node.isVisibleToUser() || node.isPassword()) continue;
            if (node.getPackageName() == null || !owner.contentEquals(node.getPackageName())) continue;
            var clickable = node.isClickable() ? node : item.clickableAncestor;
            String label = label(node);
            String id = resource(node);
            boolean tiktokVideo = TIKTOK.equals(owner) && node.isClickable() && label.isEmpty()
                    && (TIKTOK_VIDEO_RESULT.equals(id) || TIKTOK_PROFILE_VIDEO.equals(id));
            boolean rednoteCard = REDNOTE_SEARCH_CARD.equals(id) && node.isClickable();
            String xCaption = source && "com.twitter.android".equals(owner) && node.isClickable()
                    ? xResultCaption(node) : "";
            boolean resultCard = tiktokVideo || rednoteCard || !xCaption.isEmpty();
            if (!xCaption.isEmpty()) label = xCaption;
            if (tiktokVideo && TIKTOK_VIDEO_RESULT.equals(id)) label = resultText(node, owner, TIKTOK_RESULT_CAPTION);
            if (rednoteCard && label.isEmpty()) label = rednoteResultLabel(node, owner);
            String normalized = label.toLowerCase(Locale.ROOT);
            if (source && !label.isEmpty() && postText.size() < 16 && POST_TEXT.matcher(id).find()) postText.add(label);
            if (source && node.isEditable() && node.isEnabled()
                    && (id.contains("search") || SEARCH_EDITORS.contains(id)
                        || searchLabel(value(node.getHintText())) || searchLabel(label))) editors.add(node);
            boolean tapFallback = COPY.matcher(normalized).matches()
                    && ("android.widget.Button".equals(String.valueOf(node.getClassName()))
                        || "com.instagram.android:id/button".equals(id))
                    || TIKTOK_SEARCH_SUBMIT.equals(id) && ("search".equals(normalized) || "搜索".equals(normalized))
                    || TIKTOK.equals(owner) && id.isEmpty() && node.isClickable()
                        && ("视频".equals(normalized) || "用户".equals(normalized))
                    || rednoteCard
                    || "com.facebook.katana".equals(owner) && node.isClickable()
                        && NotebookSocialSource.isFacebookReadRow(normalized);
            var target = clickable == null || tapFallback ? node : clickable;
            if (target.isEnabled() && (clickable != null || tapFallback)) {
                String role = role(normalized, id, resource(target), source, "com.facebook.katana".equals(owner),
                        resultCard);
                if (role != null) {
                    var bounds = new Rect();
                    target.getBoundsInScreen(bounds);
                    if (!bounds.isEmpty()) addControl(controls, new Control(target, role, label, id, bounds, tapFallback));
                }
            }
            for (int i = 0; i < Math.min(node.getChildCount(), MAX_NODES); i++) {
                var child = node.getChild(i);
                if (child != null && remaining.size() < MAX_NODES) remaining.addLast(new Node(child, clickable));
            }
        }
        boolean sharePanel = controls.values().stream().anyMatch(control -> "copy".equals(control.role));
        var projected = controls.values().stream().filter(control -> !sharePanel || "copy".equals(control.role))
                .limit(MAX_CONTROLS).toList();
        AccessibilityNodeInfo editor = !sharePanel && editors.size() == 1 ? editors.get(0) : null;
        return Snapshot.create(windowId, projected, editor, sharePanel ? List.of() : postText);
    }

    private static void addControl(LinkedHashMap<AccessibilityNodeInfo, Control> controls, Control candidate) {
        controls.merge(candidate.node, candidate, (previous, next) ->
                priority(next.role) > priority(previous.role)
                        || next.role.equals(previous.role) && next.label.length() > previous.label.length()
                        ? next : previous);
    }

    private static int priority(String role) {
        return switch (role) { case "copy" -> 3; case "share" -> 2; default -> 1; };
    }

    private static String role(String label, String id, String targetId, boolean source, boolean facebook,
            boolean resultCard) {
        if (COPY.matcher(label).matches() || !source && "copy".equals(label)
                && (id.endsWith("/copy") || id.endsWith("/chooser_copy_button") || targetId.contains("copy_button"))) return "copy";
        if (!source) return null;
        if (POST_SHARE_VIEWS.contains(id) || POST_SHARE_VIEWS.contains(targetId) || SHARE.matcher(label).matches()
                || label.isBlank() && id.matches(".+/(?:share_button|button_share|share_icon|share_action)")) return "share";
        if (TIKTOK_PROFILE_RESULT.equals(id) && label.isBlank()) return "navigate";
        if (MUTATING.matcher(label).find()
                || ACTION_VIEW.matcher(id).find() || ACTION_VIEW.matcher(targetId).find()) return null;
        if (resultCard) return "navigate";
        if (label.isBlank()) return null;
        if (READ_NAVIGATION.matcher(label).matches() || facebook && NotebookSocialSource.isFacebookReadRow(label)
                || TIKTOK_PROFILE_RESULT.equals(targetId)) return "navigate";
        if (!id.contains("toolbar") && !targetId.contains("toolbar")
                && (RESULT_VIEW.matcher(id).find() || RESULT_VIEW.matcher(targetId).find())) return "navigate";
        return null;
    }

    private static String rednoteResultLabel(AccessibilityNodeInfo row, String owner) {
        String title = resultText(row, owner, REDNOTE_SEARCH_TITLE);
        String author = resultText(row, owner, REDNOTE_SEARCH_AUTHOR);
        if (author.isEmpty()) return title;
        return title.isEmpty() ? author : author + " · " + title;
    }

    private static String resultText(AccessibilityNodeInfo row, String owner, String viewId) {
        var remaining = new ArrayDeque<AccessibilityNodeInfo>();
        remaining.add(row);
        int visited = 0;
        while (!remaining.isEmpty() && visited++ < MAX_NODES) {
            var node = remaining.removeFirst();
            if (node.isPassword() || node.getPackageName() == null || !owner.contentEquals(node.getPackageName())) continue;
            if (viewId.equals(resource(node))) return label(node);
            for (int i = 0; i < node.getChildCount() && remaining.size() < MAX_NODES; i++) {
                var child = node.getChild(i);
                if (child != null) remaining.addLast(child);
            }
        }
        return "";
    }

    private static String label(AccessibilityNodeInfo node) {
        String text = value(node.getText());
        String description = value(node.getContentDescription());
        return text.isEmpty() ? description : text;
    }

    private static String xResultCaption(AccessibilityNodeInfo card) {
        if (!"android.view.View".equals(value(card.getClassName())) || card.getChildCount() > 24) return "";
        boolean postOptions = false;
        String caption = "";
        for (int i = 0; i < card.getChildCount(); i++) {
            var child = card.getChild(i);
            if (child == null || !child.isVisibleToUser() || child.isEditable() || child.isPassword()
                    || !value(card.getPackageName()).equals(value(child.getPackageName()))) continue;
            String type = value(child.getClassName());
            if ("android.widget.Button".equals(type)) {
                switch (label(child).toLowerCase(Locale.ROOT)) {
                    case "发帖选项", "貼文選項", "post options", "tweet options" -> postOptions = true;
                    default -> { }
                }
            }
            if ("android.widget.TextView".equals(type) && !child.isClickable()
                    && child.getText() != null && !label(child).isBlank()) {
                if (!caption.isEmpty()) return "";
                caption = label(child);
            }
        }
        return postOptions ? caption : "";
    }

    private static boolean searchLabel(String text) {
        String value = text.toLowerCase(Locale.ROOT);
        return value.startsWith("search") || value.startsWith("搜索") || value.startsWith("搜尋");
    }

    private static String value(CharSequence sequence) {
        if (sequence == null) return "";
        String safe = sequence.toString().replaceAll("[\\s\\p{Z}]+", " ").strip();
        return safe.substring(0, Math.min(safe.length(), 240));
    }

    private static String resource(AccessibilityNodeInfo node) {
        return node.getViewIdResourceName() == null ? "" : node.getViewIdResourceName().toLowerCase(Locale.ROOT);
    }

    private record Node(AccessibilityNodeInfo value, AccessibilityNodeInfo clickableAncestor) {}
    private record Control(AccessibilityNodeInfo node, String role, String label, String viewId, Rect bounds,
                           boolean tapFallback) {
        JSONObject toJson(int id) throws Exception {
            return new JSONObject().put("id", id).put("role", role).put("label", label).put("viewId",viewId)
                    .put("tapFallback", tapFallback)
                    .put("left", bounds.left).put("top", bounds.top).put("right", bounds.right).put("bottom", bounds.bottom);
        }
    }

    private record Snapshot(String token, List<Control> controls, AccessibilityNodeInfo editor, JSONArray json) {
        static Snapshot empty() { return new Snapshot("", List.of(), null, new JSONArray()); }

        static Snapshot create(int windowId, List<Control> controls, AccessibilityNodeInfo editor,
                List<String> postText) throws Exception {
            var json = new JSONArray();
            for (int i = 0; i < controls.size(); i++) json.put(controls.get(i).toJson(i));
            var encoded = (windowId + "\n" + json + "\n" + (editor != null) + "\n" + postText)
                    .getBytes(StandardCharsets.UTF_8);
            var token = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded));
            return new Snapshot(token, controls, editor, json);
        }

        JSONObject toJson() throws Exception {
            return new JSONObject().put("token", token).put("controls", json).put("editable", editor != null);
        }
    }
}
