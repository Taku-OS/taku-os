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
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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

    static long baseline() throws Exception {
        return Long.parseLong(runHelper("baseline"));
    }

    static String copiedLink(String packageName, long baseline) throws Exception {
        NotebookSocialSource platform = NotebookSocialSource.byPackage(packageName);
        if (platform == null || baseline <= 0) return null;
        String result = runHelper("copied", packageName, Long.toString(baseline));
        if (result.isEmpty()) return null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(7);
        String unique = null;
        for (String candidate : result.split("\t")) {
            String canonical = resolve(platform, candidate, deadline);
            if (canonical == null || (unique != null && !unique.equals(canonical))) return null;
            unique = canonical;
        }
        return unique;
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
                NotebookSocialSource platform = NotebookSocialSource.byPackage(args[1]);
                if (platform == null) throw new IOException();
                value = String.join("\t", freshCandidates(clipboard, platform, args[1], Long.parseLong(args[2])));
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

    private static Set<String> freshCandidates(Clipboard clipboard, NotebookSocialSource platform,
                                                String packageName, long baseline) throws Exception {
        ClipDescription description = clipboard.description();
        if (baseline <= 0 || !linkContent(description) || description.getTimestamp() <= baseline) return Set.of();
        String source = clipboard.source();
        if (!packageName.equals(source) && !isSystemSharesheet(source)) return Set.of();
        ClipData clip = clipboard.content();
        if (clip == null || !sameClip(description, clip.getDescription()) || !source.equals(clipboard.source())
                || !sameClip(description, clipboard.description())) return Set.of();

        Set<String> candidates = new LinkedHashSet<>();
        int remainingCharacters = CLIP_TEXT_LIMIT;
        for (int i = 0; i < clip.getItemCount(); i++) {
            ClipData.Item item = clip.getItemAt(i);
            if (item.getUri() != null) addCandidate(candidates, platform, item.getUri().toString());
            if (candidates.size() > MAX_CANDIDATES) return Set.of();
            CharSequence text = item.getText();
            if (text == null) continue;
            remainingCharacters -= text.length();
            if (remainingCharacters < 0) return Set.of();
            var urls = URL.matcher(text);
            while (urls.find()) {
                if (urls.end() - urls.start() > URL_LIMIT) continue;
                addCandidate(candidates, platform, URL_PUNCTUATION.matcher(urls.group()).replaceFirst(""));
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
