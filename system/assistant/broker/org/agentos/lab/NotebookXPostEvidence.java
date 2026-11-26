package org.agentos.lab;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Checks search results against X's public post metadata and the saved screenshot evidence. */
final class NotebookXPostEvidence {
    private static final int MAX_CANDIDATES = 6;
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;
    private static final int VERIFY_LIMIT_MS = 8_000;
    private static final int IO_LIMIT_MS = 3_000;
    private static final Pattern HANDLE = Pattern.compile("[A-Za-z0-9_]{1,15}");
    private static final Pattern STATUS_PATH = Pattern.compile("/([A-Za-z0-9_]{1,15}|i/web)/status/([1-9][0-9]{0,19})/?");
    private static final Pattern MINUTE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}");
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Set<String> X_HOSTS = Set.of("x.com", "www.x.com", "m.x.com", "mobile.x.com",
            "twitter.com", "www.twitter.com", "m.twitter.com", "mobile.twitter.com");
    private static final Set<String> COMMON_WORDS = Set.of("a", "an", "and", "for", "from", "in", "is", "on", "of",
            "the", "this", "to", "with", "available", "component", "interactive", "new", "now", "http", "https", "www", "com", "dev");

    @FunctionalInterface
    interface Fetcher {
        JSONObject fetch(String id, long deadline) throws IOException;
    }

    private record Candidate(String id, JSONObject post) {}

    private NotebookXPostEvidence() {}

    static String verify(JSONObject evidence, JSONArray candidates, long capturedAt, String sourceZone) throws IOException {
        return verify(evidence, candidates, capturedAt, sourceZone, NotebookXPostEvidence::fetch);
    }

    static String verify(JSONObject evidence, JSONArray candidates, long capturedAt, String sourceZone, Fetcher fetcher)
            throws IOException {
        String author = handle(evidence.optString("handle"));
        if (author.isEmpty() || capturedAt <= 0) return "";

        ZoneId zone;
        LocalDateTime minute;
        try {
            zone = ZoneId.of(sourceZone);
            String shown = evidence.optString("postedMinute").strip();
            if (!shown.isEmpty() && !MINUTE.matcher(shown).matches()) return "";
            minute = shown.isEmpty() ? null : LocalDateTime.parse(shown);
        } catch (DateTimeException invalid) {
            return "";
        }

        Set<String> phrases = new LinkedHashSet<>();
        collectPhrases(phrases, evidence.optJSONArray("snippets"), 3, minute != null);
        if (!evidence.optString("translation").isBlank()) {
            collectPhrases(phrases, evidence.optJSONArray("anchors"), 4, minute != null);
        }
        if (phrases.isEmpty()) return "";

        if (candidates.length() > 24) return "";
        Set<String> ids = new LinkedHashSet<>();
        for (int index = 0; index < candidates.length(); index++) {
            String id = candidateId(candidates.optString(index), author);
            if (id != null) ids.add(id);
            if (ids.size() > MAX_CANDIDATES) return "";
        }
        if (ids.isEmpty()) return "";

        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(VERIFY_LIMIT_MS);
        Instant capture = Instant.ofEpochMilli(capturedAt);
        var executor = Executors.newFixedThreadPool(ids.size(), task -> {
            Thread worker = new Thread(task, "notebook-x-verify");
            worker.setDaemon(true);
            return worker;
        });
        try {
            var completed = new ExecutorCompletionService<Candidate>(executor);
            for (String id : ids) completed.submit(() -> new Candidate(id, fetcher.fetch(id, deadline)));
            String matched = "";
            for (int remaining = ids.size(); remaining > 0; remaining--) {
                var next = completed.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
                if (next == null) throw new VerificationFailure("NOTEBOOK_X_VERIFY_TIMEOUT");
                Candidate candidate = next.get();
                if (!matches(candidate.post(), candidate.id(), author, phrases, minute, zone, capture)) continue;
                if (!matched.isEmpty()) return "";
                matched = "https://x.com/" + author + "/status/" + candidate.id();
            }
            return matched;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new VerificationFailure("NOTEBOOK_X_VERIFY_INTERRUPTED");
        } catch (ExecutionException failure) {
            // An upstream error means we cannot establish uniqueness even if another candidate matched.
            if (failure.getCause() instanceof IOException safe) throw safe;
            throw new VerificationFailure("NOTEBOOK_X_VERIFY_UNAVAILABLE");
        } finally {
            executor.shutdownNow();
        }
    }

    private static String candidateId(String value, String author) {
        if (value.length() > 2048) return null;
        try {
            URI uri = URI.create(value.strip());
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getUserInfo() != null
                    || uri.getPort() != -1 && uri.getPort() != 443 || !X_HOSTS.contains(host.toLowerCase(Locale.ROOT))) {
                return null;
            }
            Matcher match = STATUS_PATH.matcher(uri.getRawPath());
            if (!match.matches()) return null;
            if (!"i/web".equals(match.group(1)) && !author.equals(handle(match.group(1)))) return null;
            return match.group(2);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static boolean matches(JSONObject post, String id, String author, Set<String> phrases, LocalDateTime minute,
            ZoneId zone, Instant capture) {
        if (post == null || !id.equals(post.optString("id_str"))) return false;
        JSONObject user = post.optJSONObject("user");
        if (user == null || !author.equals(handle(user.optString("screen_name")))) return false;
        try {
            Instant posted = Instant.parse(post.optString("created_at"));
            if (posted.isAfter(capture) || (minute != null
                    && !minute.equals(LocalDateTime.ofInstant(posted, zone).truncatedTo(ChronoUnit.MINUTES)))) return false;
        } catch (DateTimeException invalid) {
            return false;
        }

        String text = post.optString("text");
        var variants = new ArrayList<String>(3);
        variants.add(text);
        JSONObject entities = post.optJSONObject("entities");
        JSONArray urls = entities == null ? null : entities.optJSONArray("urls");
        if (urls != null) {
            variants.add(replaceLinks(text, urls, "display_url"));
            variants.add(replaceLinks(text, urls, "expanded_url"));
        }
        for (String variant : variants) {
            String words = " " + normalized(variant) + " ";
            for (String phrase : phrases) {
                if (words.contains(" " + phrase + " ") || (cjkCount(phrase) >= 8 && words.contains(phrase))) return true;
            }
        }
        return false;
    }

    private static String replaceLinks(String text, JSONArray urls, String replacementField) {
        for (int index = 0; index < Math.min(urls.length(), 16); index++) {
            JSONObject link = urls.optJSONObject(index);
            if (link == null) continue;
            String shortUrl = link.optString("url");
            String expanded = link.optString(replacementField);
            if (shortUrl.startsWith("https://t.co/") && !expanded.isBlank() && expanded.length() <= 2048) {
                text = text.replace(shortUrl, expanded);
            }
        }
        return text;
    }

    private static void collectPhrases(Set<String> output, JSONArray values, int count, boolean hasMinute) {
        if (values == null) return;
        for (int i = 0; i < Math.min(count, values.length()); i++) {
            String value = values.optString(i).stripTrailing();
            if (value.length() > 160 || value.contains("…") || value.endsWith("...")) continue;
            String phrase = normalized(value);
            String[] words = phrase.split(" ");
            long distinct = java.util.Arrays.stream(words).filter(w -> w.length() > 1 && !COMMON_WORDS.contains(w))
                    .distinct().count();
            int characters = phrase.replace(" ", "").length();
            if ((hasMinute && distinct >= 2 && characters >= 8)
                    || (distinct >= 2 && words.length >= 4 && characters >= 18)
                    || cjkCount(phrase) >= (hasMinute ? 8 : 12)) output.add(phrase);
        }
    }

    private static long cjkCount(String value) {
        return value.codePoints().filter(codePoint -> switch (Character.UnicodeScript.of(codePoint)) {
            case HAN, HIRAGANA, KATAKANA, HANGUL -> true;
            default -> false;
        }).count();
    }

    private static String normalized(String value) {
        value = value.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'");
        return NON_WORD.matcher(Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT))
                .replaceAll(" ").strip();
    }

    private static String handle(String value) {
        String handle = value.strip();
        if (handle.startsWith("@")) handle = handle.substring(1);
        return HANDLE.matcher(handle).matches() ? handle.toLowerCase(Locale.ROOT) : "";
    }

    private static JSONObject fetch(String id, long deadline) throws IOException {
        HttpURLConnection connection = null;
        try {
            int timeout = timeout(deadline);
            // This is the same public embed contract as the Notebook video resolver. No account credentials are used.
            URL endpoint = URI.create(NotebookVideoSource.publicEmbedUrl(id)).toURL();
            connection = openConnection(endpoint);
            connection.setConnectTimeout(timeout);
            connection.setReadTimeout(timeout);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "Taku-Notebook-Source/1");
            int status = connection.getResponseCode();
            if (status == 404 || status == 410) return null;
            if (status != 200) throw new VerificationFailure("NOTEBOOK_X_VERIFY_HTTP_" + status);
            if (connection.getContentLengthLong() > MAX_RESPONSE_BYTES) {
                throw new VerificationFailure("NOTEBOOK_X_VERIFY_RESPONSE_TOO_LARGE");
            }
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream body = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    timeout(deadline);
                    if (count > MAX_RESPONSE_BYTES - body.size()) {
                        throw new VerificationFailure("NOTEBOOK_X_VERIFY_RESPONSE_TOO_LARGE");
                    }
                    body.write(buffer, 0, count);
                }
                timeout(deadline);
                return new JSONObject(body.toString(StandardCharsets.UTF_8));
            }
        } catch (VerificationFailure safe) {
            throw safe;
        } catch (SocketTimeoutException timeout) {
            throw new VerificationFailure("NOTEBOOK_X_VERIFY_TIMEOUT");
        } catch (JSONException invalid) {
            throw new VerificationFailure("NOTEBOOK_X_VERIFY_INVALID_RESPONSE");
        } catch (IOException | IllegalArgumentException unavailable) {
            throw new VerificationFailure("NOTEBOOK_X_VERIFY_UNAVAILABLE");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static int timeout(long deadline) throws VerificationFailure {
        if (Thread.currentThread().isInterrupted()) throw new VerificationFailure("NOTEBOOK_X_VERIFY_INTERRUPTED");
        long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (remaining <= 0) throw new VerificationFailure("NOTEBOOK_X_VERIFY_TIMEOUT");
        return (int) Math.min(remaining, IO_LIMIT_MS);
    }

    private static HttpURLConnection openConnection(URL target) throws IOException {
        // The root broker does not participate in VpnService routing. Respect its existing proxy setting.
        Path setting = Path.of("/data/local/agentos/network-proxy");
        if (!Files.isRegularFile(setting)) return (HttpURLConnection) target.openConnection();
        try {
            URI proxy = URI.create(new String(Files.readAllBytes(setting), StandardCharsets.UTF_8).strip());
            if (!"http".equals(proxy.getScheme()) || proxy.getHost() == null || proxy.getPort() <= 0
                    || proxy.getPort() > 65535 || proxy.getUserInfo() != null) {
                throw new VerificationFailure("NOTEBOOK_X_VERIFY_PROXY_UNAVAILABLE");
            }
            return (HttpURLConnection) target.openConnection(new Proxy(Proxy.Type.HTTP,
                    InetSocketAddress.createUnresolved(proxy.getHost(), proxy.getPort())));
        } catch (IllegalArgumentException invalid) {
            throw new VerificationFailure("NOTEBOOK_X_VERIFY_PROXY_UNAVAILABLE");
        }
    }

    private static final class VerificationFailure extends IOException {
        private static final long serialVersionUID = 1L;

        VerificationFailure(String code) {
            super(code);
        }
    }
}
