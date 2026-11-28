package org.agentos.lab;

import android.app.ActivityManager;
import android.app.TaskInfo;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Credentials;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.agentos.ai.NotebookSocialSource;
import org.json.JSONObject;

/** Short-lived display lease for finding a post inside its original app. */
final class NotebookPostScreen implements AutoCloseable {
    private static final String KEY = "notebook-post";
    private static final PhoneExecutionScreens.Host HOST = PhoneExecutionScreens.DEVICE_HOST;
    private static final String TASK_OWNER = "org.agentos.notebook.POST_SCREEN";
    private static final String JPEG = "data:image/jpeg;base64,";
    private static final int MAX_JPEG_BYTES = 220_000;
    private static final int MAX_TASKS = 1024;
    private static final AtomicBoolean IN_USE = new AtomicBoolean();

    private final PhoneExecutionScreens.Screen screen;
    private final NotebookSocialSource platform;
    private final Tasks tasks;
    private final Map<Integer, Task> otherTasks = new HashMap<>();
    private final Set<Integer> otherIds = new HashSet<>();
    private final Set<Integer> ownedIds = new HashSet<>();
    private Task primary;
    private long frameBeforeLaunch;
    private boolean closed;

    private NotebookPostScreen(PhoneExecutionScreens.Screen screen, NotebookSocialSource platform, Tasks tasks) {
        this.screen = screen;
        this.platform = platform;
        this.tasks = tasks;
    }

    static NotebookPostScreen open(String packageName) throws Exception {
        NotebookSocialSource platform = NotebookSocialSource.byPackage(packageName);
        if (platform == null) throw new IOException("NOTEBOOK_POST_PACKAGE_UNSUPPORTED");
        if (!IN_USE.compareAndSet(false, true)) throw new IOException("NOTEBOOK_POST_SCREEN_BUSY");
        String generation = null;
        try {
            int user = PhoneAppCatalog.currentUser();
            // Share clipboard attribution currently operates in Android's system user only.
            if (user != 0) throw new IOException("NOTEBOOK_POST_USER_UNSUPPORTED");
            requireBackgroundAccess(packageName);
            Tasks tasks = new Tasks();
            // This fixed slot is reserved for one Notebook operation at a time.
            try {
                HOST.status(KEY);
                throw new IOException("NOTEBOOK_POST_SCREEN_BUSY");
            } catch (IOException failure) {
                if (!failure.getMessage().contains("phone-display: No live host in this slot")) throw failure;
            }
            JSONObject started = HOST.start(KEY);
            PhoneExecutionScreens.Screen host = PhoneExecutionScreens.descriptor(KEY, started, false);
            generation = host.generation;
            PhoneExecutionScreens.Screen screen =
                    new PhoneExecutionScreens.Screen(KEY, host.generation, host.displayId, false, packageName, user);
            NotebookPostScreen lease = new NotebookPostScreen(screen, platform, tasks);
            try (Mirror mirror = lease.mirror()) {
                List<Task> running = lease.snapshot();
                if (running.stream().anyMatch(task -> task.display() == host.displayId)) {
                    throw new IOException("NOTEBOOK_POST_SCREEN_NOT_EMPTY");
                }
                lease.primary = focused(running, 0);
                if (platform == NotebookSocialSource.X && lease.primary != null
                        && lease.primary.belongsTo(packageName)) {
                    throw new IOException("NOTEBOOK_POST_FOREGROUND_SOURCE");
                }
            }
            return lease;
        } catch (Exception failure) {
            try {
                if (generation != null) HOST.stop(KEY, generation);
            } catch (Exception cleanup) {
                failure.addSuppressed(cleanup);
            } finally {
                IN_USE.set(false);
            }
            throw failure;
        }
    }

    int displayId() { return screen.displayId; }
    String packageName() { return screen.packageName; }
    String screenName() { return "Taku-Independent-Screen-" + KEY; }

    private static void requireBackgroundAccess(String packageName) throws Exception {
        JSONObject policy = WorkspacePolicy.snapshot();
        if (!policy.getBoolean("allowBackground") || WorkspacePolicy.blockedPackages(policy).contains(packageName)) {
            throw new IOException("NOTEBOOK_POST_BACKGROUND_DISABLED");
        }
    }

    void launch(String permalink) throws Exception {
        launch(launchIntent(permalink), permalink);
    }

    void searchX(String query) throws Exception {
        if (platform != NotebookSocialSource.X || query.isBlank() || query.length() > 150) {
            throw new IOException("NOTEBOOK_POST_QUERY_UNSUPPORTED");
        }
        Uri uri = new Uri.Builder().scheme("twitter").authority("search")
                .appendQueryParameter("query", query).build();
        launch(new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
                .setPackage(packageName()), null);
    }

    private void launch(Intent intent, String permalink) throws Exception {
        requireBackgroundAccess(packageName());
        ActivityInfo target = tasks.resolve(intent, screen.userId);
        if (target == null && intent.getComponent() != null) {
            intent.setComponent(null);
            target = tasks.resolve(intent, screen.userId);
        }
        if (target == null && platform == NotebookSocialSource.REDNOTE && permalink != null) {
            intent.setData(Uri.parse(platform.canonicalUrl(permalink)));
            target = tasks.resolve(intent, screen.userId);
        }
        if (target == null || !target.exported || !packageName().equals(target.packageName)) {
            throw new IOException("NOTEBOOK_POST_APP_ENTRY_UNAVAILABLE");
        }

        try (Mirror mirror = mirror()) {
            List<Task> before = snapshot();
            Task local = checkScope(before, false);
            List<ActivityManager.RecentTaskInfo> recent = tasks.recent(screen.userId);
            for (ActivityManager.RecentTaskInfo task : recent) {
                if (!ownedIds.contains(task.taskId)) otherIds.add(task.taskId);
            }
            // Android may reuse the user's task for these modes despite the MULTIPLE_TASK flag.
            boolean singleTask = target.launchMode == ActivityInfo.LAUNCH_SINGLE_TASK;
            boolean singleton = singleTask || target.launchMode == ActivityInfo.LAUNCH_SINGLE_INSTANCE;
            boolean externalTask = singleton && (
                    recent.stream().anyMatch(task -> otherIds.contains(task.taskId) && belongsToApp(task))
                    || before.stream().anyMatch(task -> task.display() != displayId() && task.belongsTo(packageName())));
            int destination = -1;
            if (externalTask) {
                if (!singleTask || intent.getData() == null) throw new IOException("NOTEBOOK_POST_APP_WOULD_REUSE_TASK");
                if (local == null) {
                    launch(null);
                    local = checkScope(snapshot(), false);
                }
                if (local == null || !owned(local) || !local.belongsTo(packageName())) {
                    throw new IOException("NOTEBOOK_POST_APP_WOULD_REUSE_TASK");
                }
                destination = local.id();
            }

            Set<Integer> earlierTasks = new HashSet<>(ownedIds);
            Task beforePrimary = primary;
            ComponentName component = new ComponentName(target.packageName, target.name);
            List<String> command = new ArrayList<>(List.of("/system/bin/am", "start", "--user",
                    Integer.toString(screen.userId), "--display", Integer.toString(displayId()),
                    "-f", Integer.toString(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                            | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS),
                    "-a", intent.getAction(), "-n", component.flattenToString(),
                    "--es", TASK_OWNER, screen.generation));
            if (destination >= 0) command.addAll(List.of("--task", Integer.toString(destination)));
            if (intent.getDataString() != null) {
                command.addAll(List.of("-c", Intent.CATEGORY_BROWSABLE, "-d", intent.getDataString()));
            } else {
                command.addAll(List.of("-c", Intent.CATEGORY_LAUNCHER));
            }
            frameBeforeLaunch = mirror.status().getLong("frameSequence");
            String result;
            try {
                result = TaskBroker.command(command.toArray(String[]::new));
            } catch (IOException launchFailure) {
                checkScope(snapshot(), false);
                throw new IOException("NOTEBOOK_POST_APP_LAUNCH_FAILED");
            }
            if (result.contains("Warning: Activity not started") || result.contains("Error:")) {
                List<Task> after = snapshot();
                Task current = focused(after, 0);
                if (current != null && component.equals(current.top())
                        && (result.contains("been brought to the front") || result.contains("has been delivered"))) {
                    restoreFocus(after, beforePrimary, current);
                }
                throw new IOException("NOTEBOOK_POST_APP_LAUNCH_FAILED");
            }

            long deadline = SystemClock.elapsedRealtime() + 5_000;
            Task previous = null;
            while (SystemClock.elapsedRealtime() < deadline) {
                Task current = checkScope(snapshot(), false);
                if (current != null && (current.id() == destination || !earlierTasks.contains(current.id()))
                        && current.sameFocus(previous)
                        && mirror.status().getLong("frameSequence") > frameBeforeLaunch) {
                    return;
                }
                previous = current;
                Thread.sleep(250);
            }
            throw new IOException("NOTEBOOK_POST_APP_DID_NOT_OPEN_ON_SCREEN");
        }
    }

    private Intent launchIntent(String permalink) throws IOException {
        if (permalink == null) {
            return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(packageName());
        }
        String canonical = platform.canonicalUrl(permalink);
        if (canonical == null) throw new IOException("NOTEBOOK_POST_URL_UNSUPPORTED");
        String url = platform == NotebookSocialSource.REDNOTE
                ? "xhsdiscover://item/" + Uri.parse(canonical).getLastPathSegment() : canonical;
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addCategory(Intent.CATEGORY_BROWSABLE).setPackage(packageName());
        String activity = platform.nativeViewActivity(packageName());
        return activity == null ? intent : intent.setClassName(packageName(), activity);
    }

    void verifyActive(boolean allowSharingSurface) throws Exception {
        try (Mirror mirror = mirror()) {
            requireActive(allowSharingSurface);
            mirror.status();
        }
    }

    void pressBack() throws Exception {
        try (Mirror mirror = mirror()) {
            requireActive(true);
            pressBack(mirror);
        }
    }

    private void pressBack(Mirror mirror) throws Exception {
        sendInput(mirror, "key 4");
    }

    void enter() throws Exception {
        input("key 66");
    }

    void swipe(int startY, int endY) throws Exception {
        input("swipe 370 " + startY + " 370 " + endY + " 370");
    }

    private void input(String command) throws Exception {
        try (Mirror mirror = mirror()) {
            requireActive(false);
            sendInput(mirror, command);
        }
    }

    private void sendInput(Mirror mirror, String command) throws Exception {
        mirror.status();
        JSONObject reply = mirror.json(command);
        if (!reply.optBoolean("ok") || reply.getInt("displayId") != displayId()) {
            throw new IOException("NOTEBOOK_POST_INPUT_FAILED");
        }
        mirror.status();
    }

    String captureImage() throws Exception {
        try (Mirror mirror = mirror()) {
            requireActive(false);
            if (mirror.status().getLong("frameSequence") <= frameBeforeLaunch) {
                throw new IOException("NOTEBOOK_POST_FRAME_UNAVAILABLE");
            }
            byte[] bytes = mirror.call("frame", 1_000_000);
            requireActive(false);
            mirror.status();
            return JPEG + Base64.encodeToString(boundedJpeg(bytes), Base64.NO_WRAP);
        }
    }

    private static byte[] boundedJpeg(byte[] bytes) throws IOException {
        if (bytes.length < 3 || (bytes[0] & 255) != 0xff || (bytes[1] & 255) != 0xd8
                || (bytes[2] & 255) != 0xff) throw new IOException("NOTEBOOK_POST_FRAME_UNAVAILABLE");
        if (bytes.length <= MAX_JPEG_BYTES) return bytes;
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        if (bitmap == null) throw new IOException("NOTEBOOK_POST_FRAME_UNAVAILABLE");
        try {
            ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            for (int quality : new int[] {62, 46, 30}) {
                encoded.reset();
                if (bitmap.compress(Bitmap.CompressFormat.JPEG, quality, encoded)
                        && encoded.size() <= MAX_JPEG_BYTES) return encoded.toByteArray();
            }
            throw new IOException("NOTEBOOK_POST_FRAME_TOO_LARGE");
        } finally {
            bitmap.recycle();
        }
    }

    private void requireActive(boolean allowSharingSurface) throws Exception {
        requireBackgroundAccess(packageName());
        if (checkScope(snapshot(), allowSharingSurface) == null) {
            throw new IOException("NOTEBOOK_POST_APP_NOT_ACTIVE");
        }
    }

    private List<Task> snapshot() throws Exception {
        if (PhoneAppCatalog.currentUser() != screen.userId) throw new IOException("NOTEBOOK_POST_USER_CHANGED");
        List<Task> running = tasks.running(screen.generation);
        for (Task task : running) {
            if (task.display() == displayId() || task.marked() || ownedIds.contains(task.id())) continue;
            otherIds.add(task.id());
            otherTasks.put(task.id(), task);
        }
        return running;
    }

    private Task checkScope(List<Task> running, boolean allowSharingSurface) throws Exception {
        return checkScope(running, allowSharingSurface, true);
    }

    private Task checkScope(List<Task> running, boolean allowSharingSurface, boolean canDismissPrompt) throws Exception {
        Task currentPrimary = focused(running, 0);
        for (Task task : running) {
            if (task.display() != displayId() && (task.marked() || ownedIds.contains(task.id()))) {
                if (task.sameFocus(currentPrimary)) restoreFocus(running, primary, currentPrimary);
                throw new IOException("NOTEBOOK_POST_APP_LEFT_SCREEN");
            }
        }
        if (currentPrimary != null && !currentPrimary.sameFocus(primary)
                && (currentPrimary.belongsTo(packageName()) || allowSharingSurface && sharing(currentPrimary.top()))) {
            throw new IOException("NOTEBOOK_POST_MAIN_SCREEN_CHANGED");
        }
        primary = currentPrimary;

        List<Task> local = running.stream().filter(task -> task.display() == displayId()).toList();
        for (Task task : local) {
            if (!owned(task)) throw new IOException("NOTEBOOK_POST_UNEXPECTED_TASK");
            ownedIds.add(task.id());
        }
        // The virtual display has its own visible windows even while input focus stays on the phone.
        Task top = local.stream().filter(task -> task.visible() && task.top() != null).findFirst().orElse(null);
        if (top == null || packageName().equals(top.topPackage())) return top;
        // IME and app-owned dialogs are windows and leave the app's Activity task focused.
        if (allowSharingSurface && sharing(top.top())) return top;
        if (canDismissPrompt && packageName().equals(top.basePackage()) && permissionPrompt(top.top())) {
            try (Mirror mirror = mirror()) {
                pressBack(mirror);
            }
            Thread.sleep(600);
            return checkScope(snapshot(), allowSharingSurface, false);
        }
        throw new IOException("NOTEBOOK_POST_UNEXPECTED_ACTIVITY");
    }

    private boolean owned(Task task) {
        return task.user() == screen.userId && !otherIds.contains(task.id())
                && (task.marked() || ownedIds.contains(task.id()) || packageName().equals(task.basePackage())
                        || sharing(task.base()));
    }

    private boolean belongsToApp(TaskInfo task) {
        return task.baseActivity != null && packageName().equals(task.baseActivity.getPackageName())
                || task.topActivity != null && packageName().equals(task.topActivity.getPackageName());
    }

    private static boolean sharing(ComponentName component) {
        if (component == null) return false;
        return switch (component.flattenToString()) {
            case "android/com.android.internal.app.ChooserActivity",
                 "com.android.intentresolver/com.android.intentresolver.ChooserActivity",
                 "com.google.android.intentresolver/com.android.intentresolver.ChooserActivity",
                 "com.google.android.intentresolver/com.google.android.intentresolver.ChooserActivity" -> true;
            default -> false;
        };
    }

    private static boolean permissionPrompt(ComponentName component) {
        return "com.android.permissioncontroller/com.android.permissioncontroller.permission.ui.GrantPermissionsActivity"
                .equals(component.flattenToString());
    }

    private static Task focused(List<Task> tasks, int display) {
        return tasks.stream().filter(task -> task.display() == display && task.focused() && task.top() != null)
                .findFirst().orElse(null);
    }

    private void restoreFocus(List<Task> running, Task original, Task displaced) throws Exception {
        if (original == null || displaced == null || displaced.sameFocus(original)) return;
        if (running.stream().noneMatch(original::sameFocus)) return;
        // Recheck the displaced focus before overriding it. The user may have changed it.
        List<Task> fresh = tasks.running(screen.generation);
        if (!displaced.sameFocus(focused(fresh, original.display()))
                || fresh.stream().noneMatch(original::sameFocus)) return;
        tasks.focus(original);
    }

    private void returnDisplacedTask(Task task, List<Task> running) throws Exception {
        Task original = otherTasks.get(task.id());
        if (original == null || original.user() != screen.userId || !original.belongsTo(packageName())) {
            throw new IOException("NOTEBOOK_POST_CANNOT_RELEASE_FOREIGN_TASK");
        }
        Task visibleBefore = focused(running, original.display());
        tasks.returnRoot(task, original.display());
        List<Task> after = tasks.running(screen.generation);
        if (after.stream().noneMatch(next -> next.id() == task.id() && next.display() == original.display())) {
            throw new IOException("NOTEBOOK_POST_TASK_RESTORE_FAILED");
        }
        Task focusedAfter = focused(after, original.display());
        if (focusedAfter != null && focusedAfter.id() == task.id()) restoreFocus(after, visibleBefore, focusedAfter);
    }

    public void close() throws Exception {
        if (closed) return;
        try (Mirror mirror = mirror()) {
            List<Task> running = snapshot();
            for (Task task : running) {
                if (task.display() == displayId() && !owned(task)) returnDisplacedTask(task, running);
            }
            running = snapshot();
            for (Task task : running) {
                if (task.display() == displayId() && !owned(task)) {
                    throw new IOException("NOTEBOOK_POST_CANNOT_RELEASE_FOREIGN_TASK");
                }
            }
            Task current = focused(running, 0);
            if (current != null && (current.marked() || ownedIds.contains(current.id()))) {
                restoreFocus(running, primary, current);
            }
            mirror.status();
            HOST.stop(KEY, screen.generation);
        } finally {
            closed = true;
            IN_USE.set(false);
        }
    }

    private Mirror mirror() throws Exception {
        if (closed) throw new IOException("NOTEBOOK_POST_SCREEN_CLOSED");
        return new Mirror(screen);
    }

    private static final class Mirror implements AutoCloseable {
        private final PhoneExecutionScreens.Screen screen;
        private final LocalSocket socket = new LocalSocket();
        private final DataInputStream input;
        private final OutputStream output;

        Mirror(PhoneExecutionScreens.Screen screen) throws Exception {
            this.screen = screen;
            try {
                socket.connect(new LocalSocketAddress("taku-mirror-" + KEY, LocalSocketAddress.Namespace.ABSTRACT));
                socket.setSoTimeout(5_000);
                input = new DataInputStream(socket.getInputStream());
                output = socket.getOutputStream();
                status();
            } catch (Exception failure) {
                try { socket.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }

        byte[] call(String command, int limit) throws IOException {
            output.write((command + "\n").getBytes(StandardCharsets.UTF_8));
            output.flush();
            int size = input.readInt();
            if (size < 1 || size > limit) throw new IOException("NOTEBOOK_POST_SCREEN_REPLY_INVALID");
            byte[] bytes = new byte[size];
            input.readFully(bytes);
            return bytes;
        }

        JSONObject json(String command) throws Exception {
            return new JSONObject(new String(call(command, 65_536), StandardCharsets.UTF_8));
        }

        JSONObject status() throws Exception {
            JSONObject result = json("status");
            Credentials peer = socket.getPeerCredentials();
            screen.validateLive(result, peer.getPid(), peer.getUid());
            return result;
        }

        public void close() throws IOException { socket.close(); }
    }

    private record Task(int id, int user, int display, ComponentName base, ComponentName top,
                        boolean focused, boolean visible, boolean marked) {
        String basePackage() { return base == null ? "" : base.getPackageName(); }
        String topPackage() { return top == null ? "" : top.getPackageName(); }
        boolean belongsTo(String packageName) { return packageName.equals(basePackage()) || packageName.equals(topPackage()); }
        boolean sameFocus(Task other) {
            return other != null && id == other.id && user == other.user && display == other.display
                    && Objects.equals(top, other.top);
        }
    }

    private static final class Tasks {
        private final Class<?> api = Class.forName("android.app.IActivityTaskManager");
        private final Object service = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
        private final Field user = TaskInfo.class.getField("userId");
        private final Field display = TaskInfo.class.getField("displayId");
        private final Field focused = TaskInfo.class.getField("isFocused");
        private final Field visible = TaskInfo.class.getField("isVisible");
        private final Method getRunning = api.getMethod("getTasks", int.class, boolean.class, boolean.class, int.class);
        private final Method getRecent = api.getMethod("getRecentTasks", int.class, int.class, int.class);

        Tasks() throws Exception { }

        List<Task> running(String generation) throws Exception {
            List<?> result = (List<?>) getRunning.invoke(service, MAX_TASKS, false, true, -1);
            if (result.size() >= MAX_TASKS) throw new IOException("NOTEBOOK_POST_TASK_LIST_INCOMPLETE");
            List<Task> running = new ArrayList<>(result.size());
            for (Object entry : result) {
                ActivityManager.RunningTaskInfo info = (ActivityManager.RunningTaskInfo) entry;
                boolean marked = info.baseIntent != null && generation.equals(info.baseIntent.getStringExtra(TASK_OWNER));
                running.add(new Task(info.taskId, user.getInt(info), display.getInt(info), info.baseActivity,
                        info.topActivity, focused.getBoolean(info), visible.getBoolean(info), marked));
            }
            return running;
        }

        List<ActivityManager.RecentTaskInfo> recent(int userId) throws Exception {
            Object slice = getRecent.invoke(service, MAX_TASKS, ActivityManager.RECENT_WITH_EXCLUDED, userId);
            List<?> result = (List<?>) slice.getClass().getMethod("getList").invoke(slice);
            if (result.size() >= MAX_TASKS) throw new IOException("NOTEBOOK_POST_TASK_LIST_INCOMPLETE");
            return result.stream().map(ActivityManager.RecentTaskInfo.class::cast).toList();
        }

        ActivityInfo resolve(Intent intent, int userId) throws Exception {
            Object pm = Class.forName("android.app.ActivityThread").getMethod("getPackageManager").invoke(null);
            Method resolve = Class.forName("android.content.pm.IPackageManager")
                    .getMethod("resolveIntent", Intent.class, String.class, long.class, int.class);
            long flags = Intent.ACTION_VIEW.equals(intent.getAction()) ? PackageManager.MATCH_DEFAULT_ONLY : 0;
            ResolveInfo match = (ResolveInfo) resolve.invoke(pm, intent, null, flags, userId);
            return match == null ? null : match.activityInfo;
        }

        void focus(Task task) throws Exception {
            int rootId = rootId(task, false);
            api.getMethod("setFocusedRootTask", int.class).invoke(service, rootId);
            // Organizer roots can contain several leaf tasks. Restore the original leaf as well.
            if (rootId != task.id()) api.getMethod("setFocusedTask", int.class).invoke(service, task.id());
        }

        void returnRoot(Task task, int originalDisplay) throws Exception {
            int rootId = rootId(task, true);
            api.getMethod("moveRootTaskToDisplay", int.class, int.class).invoke(service, rootId, originalDisplay);
        }

        private int rootId(Task task, boolean standalone) throws Exception {
            List<?> roots = (List<?>) api.getMethod("getAllRootTaskInfosOnDisplay", int.class).invoke(service, task.display());
            for (Object root : roots) {
                int[] children = (int[]) root.getClass().getField("childTaskIds").get(root);
                int rootId = ((TaskInfo) root).taskId;
                for (int child : children) {
                    if (child != task.id()) continue;
                    if (standalone && (children.length != 1 || rootId != task.id())) {
                        throw new IOException("NOTEBOOK_POST_CANNOT_RESTORE_SHARED_ROOT_TASK");
                    }
                    return rootId;
                }
            }
            throw new IOException("NOTEBOOK_POST_ROOT_TASK_UNAVAILABLE");
        }
    }
}
