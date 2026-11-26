package org.agentos.lab;

import android.app.ActivityManager;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.pm.ApplicationInfo;
import android.os.IBinder;
import android.system.Os;
import android.text.TextUtils;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.agentos.ai.NotebookSocialSource;

public final class NotebookShareClipboard {
    private static final String BROKER_JAR = "/system_ext/framework/agentos-task-broker.jar";
    private static final String X_PACKAGE = "com.twitter.android";
    private static final String RESULT = "NOTEBOOK_SHARE_CLIPBOARD:";
    private static final int USER_SYSTEM = 0;
    private static final int DEVICE_DEFAULT = 0;
    private static final int SHELL_UID = 2000;
    private static final int MAX_CANDIDATES = 4;
    private static final int CLIP_TEXT_LIMIT = 32768;
    private static final int URL_LIMIT = 2048;
    private static final int OUTPUT_LIMIT = 4096;
    private static final Pattern URL = Pattern.compile("https?://[^\\p{Z}\\s<>\"'`\\[\\]()]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern URL_PUNCTUATION = Pattern.compile("[.,;:!?。，、；：！？）】》」』]+$");

    private NotebookShareClipboard() {}

    static long baseline() throws Exception {
        return Long.parseLong(runHelper("baseline"));
    }

    static String copiedLink(String packageName, long baseline) throws Exception {
        if (!X_PACKAGE.equals(packageName) || baseline <= 0) return null;
        String result = runHelper("copied", packageName, Long.toString(baseline));
        // The helper returns exactly one canonical URL, or an empty line.
        return NotebookSocialSource.X.canonicalUrl(result);
    }

    private static String runHelper(String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of(
                "/system/bin/app_process", "/system/bin", NotebookShareClipboard.class.getName()));
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).redirectError(new File("/dev/null"));
        builder.environment().put("CLASSPATH", BROKER_JAR);
        Process helper = builder.start();
        try (InputStream output = helper.getInputStream()) {
            if (!helper.waitFor(3, TimeUnit.SECONDS)) throw new IOException("NOTEBOOK_CLIPBOARD_TIMEOUT");
            if (helper.exitValue() != 0) throw new IOException("NOTEBOOK_CLIPBOARD_UNAVAILABLE");
            byte[] bytes = output.readNBytes(OUTPUT_LIMIT + 1);
            if (bytes.length > OUTPUT_LIMIT) throw new IOException("NOTEBOOK_CLIPBOARD_INVALID_RESULT");
            String result = null;
            for (String line : new String(bytes, StandardCharsets.UTF_8).split("\n")) {
                if (!line.startsWith(RESULT)) continue;
                if (result != null) throw new IOException("NOTEBOOK_CLIPBOARD_INVALID_RESULT");
                result = line.substring(RESULT.length());
            }
            if (result == null) throw new IOException("NOTEBOOK_CLIPBOARD_INVALID_RESULT");
            return result;
        } finally {
            if (helper.isAlive()) helper.destroyForcibly();
        }
    }

    public static void main(String[] args) {
        try {
            // ClipboardService grants background reads and source attribution to Android Shell.
            Os.setuid(SHELL_UID);
            requireSystemUser();
            Clipboard clipboard = new Clipboard();
            String value;
            if (args.length == 1 && "baseline".equals(args[0])) {
                ClipDescription description = clipboard.description();
                long timestamp = description == null ? 0 : description.getTimestamp();
                value = Long.toString(Math.max(timestamp, System.currentTimeMillis()));
            } else if (args.length == 3 && "copied".equals(args[0])) {
                if (!X_PACKAGE.equals(args[1])) throw new IOException();
                Set<String> candidates = freshCandidates(clipboard, Long.parseLong(args[2]));
                value = candidates.size() == 1 ? candidates.iterator().next() : "";
            } else {
                throw new IOException();
            }
            requireSystemUser();
            System.out.println(RESULT + value);
            System.exit(0);
        } catch (Exception error) {
            System.exit(1);
        }
    }

    private static void requireSystemUser() throws Exception {
        int current = (int) ActivityManager.class.getMethod("getCurrentUser").invoke(null);
        if (current != USER_SYSTEM) throw new IOException("NOTEBOOK_CLIPBOARD_USER_CHANGED");
    }

    private static Set<String> freshCandidates(Clipboard clipboard, long baseline) throws Exception {
        ClipDescription description = clipboard.description();
        if (baseline <= 0 || !linkContent(description) || description.getTimestamp() <= baseline) return Set.of();
        String source = clipboard.source();
        if (!X_PACKAGE.equals(source) && !isSystemSharesheet(source)) return Set.of();
        ClipData clip = clipboard.content();
        if (clip == null || !sameClip(description, clip.getDescription()) || !source.equals(clipboard.source())
                || !sameClip(description, clipboard.description())) return Set.of();

        Set<String> candidates = new LinkedHashSet<>();
        int remainingCharacters = CLIP_TEXT_LIMIT;
        for (int i = 0; i < clip.getItemCount(); i++) {
            ClipData.Item item = clip.getItemAt(i);
            if (item.getUri() != null) addCandidate(candidates, item.getUri().toString());
            if (candidates.size() > MAX_CANDIDATES) return Set.of();
            CharSequence text = item.getText();
            if (text == null) continue;
            remainingCharacters -= text.length();
            if (remainingCharacters < 0) return Set.of();
            var urls = URL.matcher(text);
            while (urls.find()) {
                if (urls.end() - urls.start() > URL_LIMIT) continue;
                addCandidate(candidates, URL_PUNCTUATION.matcher(urls.group()).replaceFirst(""));
                if (candidates.size() > MAX_CANDIDATES) return Set.of();
            }
        }
        return candidates;
    }

    private static boolean linkContent(ClipDescription description) {
        return description != null && (description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)
                || description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)
                || description.hasMimeType(ClipDescription.MIMETYPE_TEXT_URILIST));
    }

    private static boolean sameClip(ClipDescription before, ClipDescription after) {
        if (!linkContent(after) || before.getTimestamp() != after.getTimestamp()
                || before.getMimeTypeCount() != after.getMimeTypeCount()
                || !TextUtils.equals(before.getLabel(), after.getLabel())) return false;
        for (int i = 0; i < before.getMimeTypeCount(); i++) {
            if (!before.getMimeType(i).equals(after.getMimeType(i))) return false;
        }
        return true;
    }

    private static boolean isSystemSharesheet(String source) throws Exception {
        if (!"com.android.intentresolver".equals(source) && !"com.google.android.intentresolver".equals(source)) return false;
        Class<?> contract = Class.forName("android.content.pm.IPackageManager");
        Object manager = service("package", contract.getName());
        ApplicationInfo app = (ApplicationInfo) contract.getMethod("getApplicationInfo", String.class, long.class, int.class)
                .invoke(manager, source, 0L, USER_SYSTEM);
        return app != null && (app.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
    }

    private static void addCandidate(Set<String> candidates, String value) {
        String canonical = NotebookSocialSource.X.canonicalUrl(value);
        if (canonical != null) candidates.add(canonical);
    }

    private static Object service(String name, String contract) throws Exception {
        IBinder binder = (IBinder) Class.forName("android.os.ServiceManager").getMethod("getService", String.class)
                .invoke(null, name);
        return Class.forName(contract + "$Stub").getMethod("asInterface", IBinder.class).invoke(null, binder);
    }

    private static final class Clipboard {
        private static final Class<?>[] SIGNATURE = {String.class, String.class, int.class, int.class};
        private static final Object[] IDENTITY = {"com.android.shell", null, USER_SYSTEM, DEVICE_DEFAULT};

        private final Object remote;
        private final Method description;
        private final Method source;
        private final Method content;

        Clipboard() throws Exception {
            Class<?> contract = Class.forName("android.content.IClipboard");
            remote = service("clipboard", contract.getName());
            description = contract.getMethod("getPrimaryClipDescription", SIGNATURE);
            source = contract.getMethod("getPrimaryClipSource", SIGNATURE);
            content = contract.getMethod("getPrimaryClip", SIGNATURE);
        }

        ClipDescription description() throws Exception { return (ClipDescription) description.invoke(remote, IDENTITY); }
        String source() throws Exception { return (String) source.invoke(remote, IDENTITY); }
        ClipData content() throws Exception { return (ClipData) content.invoke(remote, IDENTITY); }
    }
}
