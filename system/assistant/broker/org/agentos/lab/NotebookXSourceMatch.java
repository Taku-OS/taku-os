package org.agentos.lab;

import java.net.URI;
import java.net.URISyntaxException;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class NotebookXSourceMatch {
    private static final long X_EPOCH_MILLIS = 1288834974657L;
    private static final Set<String> X_HOSTS = Set.of(
            "x.com", "www.x.com", "mobile.x.com", "twitter.com", "www.twitter.com", "mobile.twitter.com");
    private static final String HANDLE = "[A-Za-z0-9_]{1,15}";
    private static final Pattern SCREENSHOT_HANDLE = Pattern.compile("@?(" + HANDLE + ")");
    private static final Pattern PROFILE_PATH = Pattern.compile("/(" + HANDLE + ")/?");
    private static final Pattern POST_PATH = Pattern.compile("/(" + HANDLE + ")/status/([0-9]{1,19})/?");
    private static final Pattern MINUTE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}");
    private static final Pattern URL = Pattern.compile("(?iu)\\b(?:https?://|www\\.|pic\\.twitter\\.com/)\\S+");
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern UNSEGMENTED_SCRIPT = Pattern.compile("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}]");
    private static final Pattern WORD_SEPARATOR = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Set<String> GENERIC_ANCHORS = Set.of(
            "screenshot", "translation", "translated", "original", "twitter", "thread", "today", "yesterday");

    record Evidence(String handle, String postedMinute, String translation, List<String> snippets, List<String> anchors) {}
    record Candidate(String url, String authorUrl, String body) {}

    private record Post(String handle, long id) {
        String url() { return "https://x.com/" + handle + "/status/" + id; }
        long postedAt() { return X_EPOCH_MILLIS + (id >>> 22); }
    }

    private record AuthorAndTime(String handle, LocalDateTime minute, ZoneId zone, long capturedAt) {
        boolean matches(Post post) {
            if (post == null || !handle.equals(post.handle()) || post.postedAt() > capturedAt) return false;
            return minute == null || minute.equals(LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(post.postedAt()), zone).truncatedTo(ChronoUnit.MINUTES));
        }
    }

    private NotebookXSourceMatch() {}

    static String canonicalPostUrl(String raw) {
        Post post = parsePost(raw);
        return post == null ? null : post.url();
    }

    static boolean canVerifyPublicPost(Evidence evidence, ZoneId zone, long capturedAt) {
        if (authorAndTime(evidence, zone, capturedAt) == null) return false;
        if (isTranslated(evidence)) return normalizeEvidence(evidence.anchors(), NotebookXSourceMatch::isAnchor).size() >= 2;
        return !distinctSnippets(evidence).isEmpty();
    }

    private static Set<String> distinctSnippets(Evidence evidence) {
        return normalizeEvidence(evidence.snippets(), phrase -> isDistinctive(phrase, 22, 5, 14));
    }

    static String verifiedMatch(Evidence evidence, Iterable<Candidate> candidates, ZoneId zone, long capturedAt) {
        AuthorAndTime expected = authorAndTime(evidence, zone, capturedAt);
        if (expected == null || candidates == null) return null;
        boolean translated = isTranslated(evidence);
        Set<String> snippets = distinctSnippets(evidence);
        Set<String> anchors = normalizeEvidence(evidence.anchors(), NotebookXSourceMatch::isAnchor);
        String match = null;
        for (Candidate candidate : candidates) {
            if (candidate == null) continue;
            Post post = parsePost(candidate.url());
            if (!expected.matches(post) || !expected.handle().equals(authorHandle(candidate.authorUrl()))) {
                continue;
            }
            if (!matchesBody(normalize(candidate.body()), snippets, anchors, translated, expected.minute() != null)) {
                continue;
            }
            String url = post.url();
            if (match != null && !match.equals(url)) return null;
            match = url;
        }
        return match;
    }

    static boolean matchesAuthorAndMinute(Evidence evidence, String canonicalUrl, ZoneId zone, long capturedAt) {
        AuthorAndTime expected = authorAndTime(evidence, zone, capturedAt);
        return expected != null && expected.matches(parsePost(canonicalUrl));
    }

    private static AuthorAndTime authorAndTime(Evidence evidence, ZoneId zone, long capturedAt) {
        if (evidence == null || zone == null || capturedAt <= 0) return null;
        String handle = extractHandle(SCREENSHOT_HANDLE, evidence.handle());
        if (handle == null) return null;
        String minuteText = evidence.postedMinute();
        LocalDateTime minute = null;
        if (minuteText != null && !minuteText.isBlank()) {
            if (!MINUTE.matcher(minuteText).matches()) return null;
            try {
                minute = LocalDateTime.parse(minuteText, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            } catch (DateTimeParseException malformed) {
                return null;
            }
        }
        if (minute == null && isTranslated(evidence)) return null;
        return new AuthorAndTime(handle, minute, zone, capturedAt);
    }

    private static boolean isTranslated(Evidence evidence) {
        return evidence.translation() != null && !evidence.translation().isBlank();
    }

    private static boolean matchesBody(String primaryBody, Set<String> snippets, Set<String> anchors,
            boolean translated, boolean hasMinute) {
        String body = atWordBoundaries(primaryBody);
        if (translated) return hasIndependentMatches(body, anchors);
        if (hasMinute) return snippets.stream().anyMatch(snippet -> body.contains(atWordBoundaries(snippet)));
        if (hasIndependentMatches(body, snippets)) return true;

        for (String snippet : snippets) {
            if (!isDistinctive(snippet, 44, 8, 26)) continue;
            String phrase = atWordBoundaries(snippet);
            for (int start = body.indexOf(phrase); start >= 0; start = body.indexOf(phrase, start + 1)) {
                // Anchors inside the excerpt are already accounted for by the excerpt match.
                String remaining = body.substring(0, start + 1) + '\0' + body.substring(start + phrase.length() - 1);
                if (hasIndependentMatches(remaining, anchors)) return true;
            }
        }
        return false;
    }

    private static boolean hasIndependentMatches(String body, Set<String> evidence) {
        for (String first : evidence) {
            String phrase = atWordBoundaries(first);
            int start = body.indexOf(phrase);
            if (start < 0) continue;
            for (String second : evidence) {
                String other = atWordBoundaries(second);
                if (phrase.contains(other) || other.contains(phrase)) continue;
                if (body.indexOf(other, start + phrase.length() - 1) >= 0) return true;
            }
        }
        return false;
    }

    private static Set<String> normalizeEvidence(List<String> values, Predicate<String> valid) {
        Set<String> phrases = new HashSet<>();
        if (values != null) {
            for (String value : values) {
                String phrase = normalize(value);
                if (valid.test(phrase)) phrases.add(phrase);
            }
        }
        return phrases;
    }

    private static boolean isDistinctive(String phrase, int letters, int words, int unsegmentedLetters) {
        if (UNSEGMENTED_SCRIPT.matcher(phrase).results().limit(unsegmentedLetters).count() == unsegmentedLetters) {
            return true;
        }
        return phrase.codePoints().filter(Character::isLetter).limit(letters).count() == letters
                && WORD_SEPARATOR.splitAsStream(phrase).distinct().limit(words).count() == words;
    }

    private static boolean isAnchor(String phrase) {
        return !GENERIC_ANCHORS.contains(phrase)
                && phrase.codePoints().filter(Character::isLetter).limit(6).count() == 6;
    }

    private static String normalize(String text) {
        if (text == null) return "";
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKD).toLowerCase(Locale.ROOT);
        normalized = URL.matcher(normalized).replaceAll(" ");
        normalized = MARKS.matcher(normalized).replaceAll("");
        normalized = UNSEGMENTED_SCRIPT.matcher(normalized).replaceAll(" $0 ");
        return WORD_SEPARATOR.matcher(normalized).replaceAll(" ").strip();
    }

    private static String atWordBoundaries(String phrase) {
        return " " + phrase + " ";
    }

    private static Post parsePost(String raw) {
        URI uri = officialUri(raw);
        if (uri == null) return null;
        Matcher path = POST_PATH.matcher(uri.getRawPath());
        if (!path.matches() || "i".equalsIgnoreCase(path.group(1))) return null;
        try {
            long id = Long.parseLong(path.group(2));
            return id > 0 ? new Post(path.group(1).toLowerCase(Locale.ROOT), id) : null;
        } catch (NumberFormatException tooLarge) {
            return null;
        }
    }

    private static String authorHandle(String authorUrl) {
        URI uri = officialUri(authorUrl);
        return uri == null ? null : extractHandle(PROFILE_PATH, uri.getRawPath());
    }

    private static String extractHandle(Pattern pattern, String value) {
        if (value == null) return null;
        Matcher handle = pattern.matcher(value.strip());
        return handle.matches() ? handle.group(1).toLowerCase(Locale.ROOT) : null;
    }

    private static URI officialUri(String raw) {
        if (raw == null || raw.length() > 2048) return null;
        try {
            URI uri = new URI(raw);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) return null;
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            String authority = uri.getRawAuthority();
            if (X_HOSTS.contains(host) && (host.equalsIgnoreCase(authority) || (host + ":443").equalsIgnoreCase(authority))) {
                return uri;
            }
        } catch (URISyntaxException malformed) {
            return null;
        }
        return null;
    }
}
