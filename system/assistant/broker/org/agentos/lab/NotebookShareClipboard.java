package org.agentos.lab;

import android.app.ActivityManager;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.pm.ApplicationInfo;
import android.os.IBinder;
import android.system.Os;
import android.text.TextUtils;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.agentos.ai.NotebookSocialSource;

public final class NotebookShareClipboard {
    private static final String BROKER_JAR = "/system_ext/framework/agentos-task-broker.jar";
    private static final String RESULT = "NOTEBOOK_SHARE_CLIPBOARD:";
    private static final int USER_SYSTEM = 0;
    private static final int DEVICE_DEFAULT = 0;
    private static final int SHELL_UID = 2000;
    private static final int MAX_CANDIDATES = 4;
    private static final int MAX_REDIRECTS = 4;
    private static final int CLIP_TEXT_LIMIT = 32768;
    private static final int URL_LIMIT = 2048;
    private static final int OUTPUT_LIMIT = 4096;
    private static final Pattern URL = Pattern.compile("https?://[^\\p{Z}\\s<>\"'`\\[\\]()]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern URL_PUNCTUATION = Pattern.compile("[.,;:!?。，、；：！？）】》」』]+$");
    private static final Pattern SHORT_PATH = Pattern.compile("/[A-Za-z0-9_-]{1,128}/?");
    private static final Pattern REDNOTE_SHORT_PATH = Pattern.compile("/(?:[amo]/)?[A-Za-z0-9_-]{1,128}/?");
    private static final Pattern INSTAGRAM_SHARE_PATH = Pattern.compile("/share/(?:p/|reel/)?[A-Za-z0-9_-]{1,128}/?");
    private static final Pattern TIKTOK_SHARE_PATH = Pattern.compile("/t/[A-Za-z0-9_-]{1,128}/?");
    private static final Pattern FACEBOOK_SHARE_PATH = Pattern.compile("/share/(?:p/|v/|r/)?[A-Za-z0-9_-]{1,128}/?");

    private NotebookShareClipboard() {}

    static String resolveCandidates(NotebookSocialSource platform, String candidates) throws Exception {
        if (candidates.isEmpty()) return null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(7);
        String unique = null;
        for (String candidate : candidates.split("\t")) {
            String canonical = resolve(platform, candidate, deadline);
            if (canonical == null || (unique != null && !unique.equals(canonical))) return null;
            unique = canonical;
        }
        return unique;
    }

    static final class CopySession implements AutoCloseable {
        private final Process helper;
        private final InputStream output;

        CopySession(String packageName) throws IOException {
            ProcessBuilder builder = new ProcessBuilder("/system/bin/app_process", "/system/bin",
                    NotebookShareClipboard.class.getName(), packageName).redirectError(new File("/dev/null"));
            builder.environment().put("CLASSPATH", BROKER_JAR);
            helper = builder.start();
            output = helper.getInputStream();
        }

        boolean canCopy() throws IOException, InterruptedException {
            return "ready".equals(response());
        }

        String candidates() throws IOException, InterruptedException {
            var input = helper.getOutputStream();
            input.write('r');
            input.flush();
            return response();
        }

        private String response() throws IOException, InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            var line = new ByteArrayOutputStream();
            int bytes = 0;
            while (System.nanoTime() < deadline && bytes <= OUTPUT_LIMIT) {
                if (output.available() == 0) {
                    if (!helper.isAlive()) throw new IOException("NOTEBOOK_CLIPBOARD_UNAVAILABLE");
                    Thread.sleep(15);
                    continue;
                }
                int next = output.read();
                if (next == -1) throw new IOException("NOTEBOOK_CLIPBOARD_UNAVAILABLE");
                bytes++;
                if (next != '\n') {
                    line.write(next);
                    continue;
                }
                String reply = line.toString("UTF-8");
                if (reply.startsWith(RESULT)) return reply.substring(RESULT.length());
                line.reset();
            }
            throw new IOException("NOTEBOOK_CLIPBOARD_UNAVAILABLE");
        }

        @Override public void close() {
            boolean interrupted = Thread.interrupted();
            try {
                helper.getOutputStream().close();
                helper.waitFor(2, TimeUnit.SECONDS);
            } catch (InterruptedException cancelled) {
                interrupted = true;
            } catch (IOException unavailable) {
                // Closing the pipe lets the helper restore its saved clip on EOF.
            } finally {
                if (helper.isAlive()) helper.destroyForcibly();
                try { output.close(); } catch (IOException ignored) { }
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }

    public static void main(String[] args) {
        try {
            if (args.length != 1) throw new IOException();
            NotebookSocialSource platform = NotebookSocialSource.byPackage(args[0]);
            if (platform == null) throw new IOException();
            Os.setuid(SHELL_UID);
            requireSystemUser();
            Clipboard clipboard = new Clipboard();
            ClipDescription before = clipboard.description();
            ClipData saved = null;
            if (before != null) {
                if (!canPreserve(before)) {
                    printResult("protected");
                    return;
                }
                saved = clipboard.content();
                if (!plainText(saved) || !sameClip(before, saved.getDescription())
                        || !sameClip(before, clipboard.description())) {
                    printResult("protected");
                    return;
                }
            } else if (clipboard.description() != null) {
                printResult("protected");
                return;
            }

            long baseline = Math.max(before == null ? 0 : before.getTimestamp(), System.currentTimeMillis());
            SharedClip copied = null;
            try {
                printResult("ready");
                for (int read = 0; read < 3 && System.in.read() == 'r'; read++) {
                    if (copied == null) copied = freshClip(clipboard, platform, args[0], baseline);
                    printResult(copied == null ? "" : String.join("\t", copied.candidates()));
                }
            } finally {
                requireSystemUser();
                if (copied == null) copied = freshClip(clipboard, platform, args[0], baseline);
                if (copied != null && copied.source().equals(clipboard.source())
                        && sameClip(copied.description(), clipboard.description())) {
                    clipboard.restore(saved);
                }
            }
        } catch (Exception error) {
            System.exit(1);
        }
    }

    private static void printResult(String value) {
        System.out.println(RESULT + value);
        System.out.flush();
    }

    private static boolean canPreserve(ClipDescription description) {
        return description.getMimeTypeCount() == 1 && description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)
                && !sensitive(description);
    }

    private static boolean sensitive(ClipDescription description) {
        var extras = description.getExtras();
        return extras != null && extras.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE);
    }

    private static boolean plainText(ClipData clip) {
        if (clip == null || clip.getItemCount() != 1) return false;
        ClipData.Item item = clip.getItemAt(0);
        return item.getUri() == null && item.getIntent() == null && item.getHtmlText() == null
                && item.getText() instanceof String text && text.length() <= CLIP_TEXT_LIMIT;
    }

    private record SharedClip(ClipDescription description, String source, Set<String> candidates) {}

    private static void requireSystemUser() throws Exception {
        int current = (int) ActivityManager.class.getMethod("getCurrentUser").invoke(null);
        if (current != USER_SYSTEM) throw new IOException("NOTEBOOK_CLIPBOARD_USER_CHANGED");
    }

    private static SharedClip freshClip(Clipboard clipboard, NotebookSocialSource platform,
                                       String packageName, long baseline) throws Exception {
        ClipDescription description = clipboard.description();
        if (description == null || sensitive(description) || description.getTimestamp() <= baseline) return null;
        String source = clipboard.source();
        if (!packageName.equals(source) && !isSystemSharesheet(source)) return null;
        var empty = new SharedClip(description, source, Set.of());
        if (!linkContent(description)) return empty;
        ClipData clip = clipboard.content();
        if (clip == null || !sameClip(description, clip.getDescription()) || !source.equals(clipboard.source())
                || !sameClip(description, clipboard.description())) return null;

        Set<String> candidates = new LinkedHashSet<>();
        int remainingCharacters = CLIP_TEXT_LIMIT;
        for (int i = 0; i < clip.getItemCount(); i++) {
            ClipData.Item item = clip.getItemAt(i);
            if (item.getUri() != null) addCandidate(candidates, platform, item.getUri().toString());
            if (candidates.size() > MAX_CANDIDATES) return empty;
            CharSequence text = item.getText();
            if (text == null) continue;
            remainingCharacters -= text.length();
            if (remainingCharacters < 0) return empty;
            var urls = URL.matcher(text);
            while (urls.find()) {
                if (urls.end() - urls.start() > URL_LIMIT) continue;
                addCandidate(candidates, platform, URL_PUNCTUATION.matcher(urls.group()).replaceFirst(""));
                if (candidates.size() > MAX_CANDIDATES) return empty;
            }
        }
        return new SharedClip(description, source, candidates);
    }

    private static boolean linkContent(ClipDescription description) {
        return description != null && (description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)
                || description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)
                || description.hasMimeType(ClipDescription.MIMETYPE_TEXT_URILIST));
    }

    private static boolean sameClip(ClipDescription before, ClipDescription after) {
        if (after == null || before.getTimestamp() != after.getTimestamp()
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

    private static void addCandidate(Set<String> candidates, NotebookSocialSource platform, String value) {
        if (platform == NotebookSocialSource.REDNOTE && value.regionMatches(true, 0, "http://", 0, 7)) {
            URI upgraded = shareRedirect(platform, "https://" + value.substring(7));
            if (upgraded != null) candidates.add(upgraded.toString());
            return;
        }
        String direct = platform.canonicalUrl(value);
        if (direct != null) {
            candidates.add(direct);
            return;
        }
        URI shortUrl = shareRedirect(platform, value);
        if (shortUrl != null) candidates.add(shortUrl.toString());
    }

    private static URI shareRedirect(NotebookSocialSource platform, String value) {
        if (value.length() > URL_LIMIT) return null;
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) return null;
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (!host.equalsIgnoreCase(uri.getRawAuthority()) && !(host + ":443").equalsIgnoreCase(uri.getRawAuthority())) return null;
        String path = uri.getRawPath();
        boolean allowed = switch (platform) {
            case REDNOTE -> (host.equals("xhslink.cn") || host.equals("www.xhslink.cn")
                    || host.equals("xhslink.com") || host.equals("www.xhslink.com"))
                    && REDNOTE_SHORT_PATH.matcher(path).matches();
            case INSTAGRAM -> (host.equals("instagram.com") || host.equals("www.instagram.com"))
                    && INSTAGRAM_SHARE_PATH.matcher(path).matches();
            case TIKTOK -> ((host.equals("vm.tiktok.com") || host.equals("vt.tiktok.com")) && SHORT_PATH.matcher(path).matches())
                    || ((host.equals("www.tiktok.com") || host.equals("tiktok.com")) && TIKTOK_SHARE_PATH.matcher(path).matches());
            case FACEBOOK -> (host.equals("fb.watch") && SHORT_PATH.matcher(path).matches())
                    || ((host.equals("facebook.com") || host.equals("www.facebook.com") || host.equals("m.facebook.com"))
                            && FACEBOOK_SHARE_PATH.matcher(path).matches());
            default -> false;
        };
        return allowed ? URI.create("https://" + host + path) : null;
    }

    private static String resolve(NotebookSocialSource platform, String candidate, long deadline) throws Exception {
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            String canonical = platform.canonicalUrl(candidate);
            if (canonical != null) return canonical;
            URI next = shareRedirect(platform, candidate);
            if (next == null || hop == MAX_REDIRECTS) return null;
            long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remainingMs <= 0) return null;
            HttpURLConnection connection = PhoneNetwork.openConnection(next.toURL());
            try {
                int timeout = (int) Math.min(1500, remainingMs);
                connection.setConnectTimeout(timeout);
                connection.setReadTimeout(timeout);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("User-Agent", "Mozilla/5.0");
                int status = connection.getResponseCode();
                if (status != 301 && status != 302 && status != 303 && status != 307 && status != 308) return null;
                String location = connection.getHeaderField("Location");
                if (location == null || location.length() > URL_LIMIT) return null;
                candidate = next.resolve(location).toString();
            } catch (IllegalArgumentException | IOException unresolved) {
                return null;
            } finally {
                connection.disconnect();
            }
        }
        return null;
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
        private final Method set;
        private final Method clear;

        Clipboard() throws Exception {
            Class<?> contract = Class.forName("android.content.IClipboard");
            remote = service("clipboard", contract.getName());
            description = contract.getMethod("getPrimaryClipDescription", SIGNATURE);
            source = contract.getMethod("getPrimaryClipSource", SIGNATURE);
            content = contract.getMethod("getPrimaryClip", SIGNATURE);
            set = contract.getMethod("setPrimaryClip", ClipData.class, String.class, String.class, int.class, int.class);
            clear = contract.getMethod("clearPrimaryClip", SIGNATURE);
        }

        ClipDescription description() throws Exception { return (ClipDescription) description.invoke(remote, IDENTITY); }
        String source() throws Exception { return (String) source.invoke(remote, IDENTITY); }
        ClipData content() throws Exception { return (ClipData) content.invoke(remote, IDENTITY); }
        void restore(ClipData clip) throws Exception {
            if (clip == null) clear.invoke(remote, IDENTITY);
            else set.invoke(remote, clip, IDENTITY[0], IDENTITY[1], IDENTITY[2], IDENTITY[3]);
        }
    }
}
