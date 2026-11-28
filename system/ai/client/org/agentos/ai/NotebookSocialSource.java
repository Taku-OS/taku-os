package org.agentos.ai;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public enum NotebookSocialSource {
    X("X", "x.com", Set.of("x.com", "twitter.com"), "com.twitter.android"),
    REDNOTE("小红书", "www.xiaohongshu.com", Set.of("xiaohongshu.com", "rednote.com"), "com.xingin.xhs"),
    INSTAGRAM("Instagram", "www.instagram.com", Set.of("instagram.com"), "com.instagram.android"),
    YOUTUBE("YouTube", "www.youtube.com", Set.of("youtube.com", "youtu.be"), "com.google.android.youtube"),
    TIKTOK("TikTok", "www.tiktok.com", Set.of("tiktok.com"), "com.zhiliaoapp.musically", "com.ss.android.ugc.trill"),
    FACEBOOK("Facebook", "www.facebook.com", Set.of("facebook.com"), "com.facebook.katana", "com.facebook.lite");

    private static final Pattern X_POST = Pattern.compile("/([A-Za-z0-9_]{1,15}|i/web)/status/([1-9][0-9]{0,19})/?");
    private static final String FACEBOOK_FOLLOWERS = "[0-9][0-9.,万千\\s]*"
            + "(?:位粉丝|[kmb]?\\s+followers?)";
    private static final String FACEBOOK_SEARCH_RESULTS = "(?:个搜索结果|[,，]?\\s+search results?)";
    private static final Pattern FACEBOOK_REELS_FILTER = Pattern.compile(
            "^reels" + FACEBOOK_SEARCH_RESULTS + "(?=$|[,，\\s.!])", Pattern.CASE_INSENSITIVE);
    private static final Pattern FACEBOOK_FOLLOWER_LABEL = Pattern.compile(
            FACEBOOK_FOLLOWERS + "(?=$|[,，\\s·.!])", Pattern.CASE_INSENSITIVE);
    private static final Pattern FACEBOOK_READ_ROW = Pattern.compile(
            "^(?:(?:reels|照片|帖子|photos|posts)(?:" + FACEBOOK_SEARCH_RESULTS + ")?[,，]\\s*"
            + "(?:第[0-9]+项[,，]\\s*共[0-9]+项|item [0-9]+ of [0-9]+)"
            + "|reels 编号[0-9]+[,，].+次播放|.+的 reels[,，].+双击即可观看"
            + "|.+[,，]\\s*xfbsearchcontentphotoviewmodel[,，].+"
            + "|.+[,，].*" + FACEBOOK_FOLLOWERS + "(?:\\s*·\\s*.+)?)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern REDNOTE_POST = Pattern.compile(
            "/(?:[a-z]{2}(?:-[a-zA-Z]{2})?/)?(?:explore|discovery/item|user/profile/[0-9a-fA-F]{24})/"
            + "([0-9a-fA-F]{24})/?");
    private static final Pattern INSTAGRAM_POST = Pattern.compile(
            "/(?:[A-Za-z0-9_.]{1,30}/)?(p|reel|reels|tv)/([A-Za-z0-9_-]{5,24})/?");
    private static final Pattern YOUTUBE_PATH = Pattern.compile("/(?:shorts|live|embed)/([A-Za-z0-9_-]{11})/?");
    private static final Pattern YOUTUBE_SHORT_LINK = Pattern.compile("/([A-Za-z0-9_-]{11})/?");
    private static final Pattern YOUTUBE_ID = Pattern.compile("[A-Za-z0-9_-]{11}");
    private static final Pattern TIKTOK_POST = Pattern.compile("/@([A-Za-z0-9_.]{1,24})/(video|photo)/([1-9][0-9]{10,20})/?");
    private static final String FACEBOOK_OBJECT = "(?:[1-9][0-9]{0,24}|pfbid[A-Za-z0-9]{12,180})";
    private static final Pattern FACEBOOK_POST = Pattern.compile(
            "/(?:[A-Za-z0-9._-]{1,100}/(?:posts|videos)|groups/[A-Za-z0-9._-]{1,100}/(?:posts|permalink)|reel)/"
            + FACEBOOK_OBJECT + "/?");
    private static final Pattern FACEBOOK_SLUG = Pattern.compile(
            "(/[A-Za-z0-9._-]{1,100}/(?:posts|videos))/[A-Za-z0-9._%-]{1,200}/([1-9][0-9]{0,24})/?");
    private static final Pattern FACEBOOK_ID = Pattern.compile(FACEBOOK_OBJECT);
    private static final Pattern NUMERIC_ID = Pattern.compile("[1-9][0-9]{0,24}");
    private static final Set<String> HOST_PREFIXES = Set.of("", "www.", "m.", "mobile.", "web.", "mbasic.");

    private final String displayName;
    private final String canonicalHost;
    private final Set<String> domains;
    private final Set<String> packages;

    NotebookSocialSource(String displayName, String canonicalHost, Set<String> domains, String... packages) {
        this.displayName = displayName;
        this.canonicalHost = canonicalHost;
        this.domains = domains;
        this.packages = Set.of(packages);
    }

    public String displayName() { return displayName; }
    public Set<String> domains() { return domains; }
    public Set<String> packages() { return packages; }

    public String nativeViewActivity(String packageName) {
        return this == FACEBOOK && "com.facebook.katana".equals(packageName)
                ? "com.facebook.deeplinking.aliasactivity.CatchAllUriAliasActivity" : null;
    }

    public static NotebookSocialSource byPackage(String packageName) {
        if (packageName == null) return null;
        for (NotebookSocialSource source : values()) {
            if (source.packages.contains(packageName)) return source;
        }
        return null;
    }

    public static NotebookSocialSource byUrl(String value) {
        URI uri = parse(value);
        if (uri == null) return null;
        for (NotebookSocialSource source : values()) {
            if (source.canonicalUrl(uri) != null) return source;
        }
        return null;
    }

    public String canonicalUrl(String value) {
        URI uri = parse(value);
        return uri == null ? null : canonicalUrl(uri);
    }

    public static boolean isFacebookReadRow(String label) {
        return FACEBOOK_READ_ROW.matcher(label).matches();
    }

    public static boolean isFacebookReelsFilter(String label) {
        return FACEBOOK_REELS_FILTER.matcher(label).find();
    }

    public static int facebookFollowerCountStart(String label) {
        Matcher count = FACEBOOK_FOLLOWER_LABEL.matcher(label);
        return count.find() ? count.start() : -1;
    }

    public String handleFromPostUrl(String value) {
        URI uri = parse(value);
        if (uri == null || canonicalUrl(uri) == null) return "";
        String path = uri.getRawPath();
        return switch (this) {
            case X -> {
                Matcher match = X_POST.matcher(path);
                yield match.matches() && !Set.of("i", "i/web").contains(match.group(1).toLowerCase(Locale.ROOT))
                        ? match.group(1) : "";
            }
            case TIKTOK -> {
                Matcher match = TIKTOK_POST.matcher(path);
                yield match.matches() ? match.group(1) : "";
            }
            case FACEBOOK -> {
                String[] segments = path.split("/");
                yield segments.length >= 4 && ("posts".equals(segments[2]) || "videos".equals(segments[2]))
                        && !Set.of("groups", "me").contains(segments[1]) && !NUMERIC_ID.matcher(segments[1]).matches()
                        ? segments[1] : "";
            }
            default -> "";
        };
    }

    private String canonicalUrl(URI uri) {
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (!ownsHost(host)) return null;
        String path = uri.getRawPath();
        String permalink = switch (this) {
            case X -> {
                Matcher match = X_POST.matcher(path);
                yield match.matches() ? "/" + match.group(1).toLowerCase(Locale.ROOT) + "/status/" + match.group(2) : null;
            }
            case REDNOTE -> {
                Matcher match = REDNOTE_POST.matcher(path);
                yield match.matches() ? "/explore/" + match.group(1).toLowerCase(Locale.ROOT) : null;
            }
            case INSTAGRAM -> {
                Matcher match = INSTAGRAM_POST.matcher(path);
                if (!match.matches()) yield null;
                String type = match.group(1).equals("reels") ? "reel" : match.group(1);
                yield "/" + type + "/" + match.group(2) + "/";
            }
            case YOUTUBE -> youtubePath(host, path, uri.getRawQuery());
            case TIKTOK -> {
                Matcher match = TIKTOK_POST.matcher(path);
                yield match.matches() ? withoutTrailingSlash(path) : null;
            }
            case FACEBOOK -> facebookPath(path, uri.getRawQuery());
        };
        return permalink == null ? null : "https://" + canonicalHost + permalink;
    }

    private boolean ownsHost(String host) {
        for (String domain : domains) {
            if (host.endsWith(domain) && HOST_PREFIXES.contains(host.substring(0, host.length() - domain.length()))) {
                return true;
            }
        }
        return false;
    }

    private static String youtubePath(String host, String path, String query) {
        boolean shortHost = host.endsWith("youtu.be");
        if (path.equals("/watch") && !shortHost) {
            String id = parameter(query, "v", YOUTUBE_ID);
            return id == null ? null : "/watch?v=" + id;
        }
        Matcher match = (shortHost ? YOUTUBE_SHORT_LINK : YOUTUBE_PATH).matcher(path);
        return match.matches() ? "/watch?v=" + match.group(1) : null;
    }

    private static String facebookPath(String path, String query) {
        if (FACEBOOK_POST.matcher(path).matches()) return withoutTrailingSlash(path);
        Matcher slug = FACEBOOK_SLUG.matcher(path);
        if (slug.matches()) return slug.group(1) + "/" + slug.group(2);
        if (path.equals("/story.php") || path.equals("/permalink.php")) {
            String story = parameter(query, "story_fbid", FACEBOOK_ID);
            String owner = parameter(query, "id", NUMERIC_ID);
            return story == null || owner == null ? null : "/permalink.php?story_fbid=" + story + "&id=" + owner;
        }
        if (path.equals("/watch") || path.equals("/watch/") || path.equals("/video.php")) {
            String id = parameter(query, "v", NUMERIC_ID);
            return id == null ? null : "/watch/?v=" + id;
        }
        return null;
    }

    private static String withoutTrailingSlash(String path) {
        return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    private static String parameter(String query, String key, Pattern allowed) {
        if (query == null) return null;
        String value = null;
        for (String pair : query.split("&")) {
            if (!pair.startsWith(key + "=")) continue;
            String next = pair.substring(key.length() + 1);
            if (value != null || !allowed.matcher(next).matches()) return null;
            value = next;
        }
        return value;
    }

    private static URI parse(String value) {
        if (value == null || value.length() > 2048) return null;
        try {
            URI uri = new URI(value);
            String host = uri.getHost();
            if ("https".equalsIgnoreCase(uri.getScheme()) && host != null
                    && (host.equalsIgnoreCase(uri.getRawAuthority()) || (host + ":443").equalsIgnoreCase(uri.getRawAuthority()))) {
                return uri;
            }
        } catch (URISyntaxException ignored) { }
        return null;
    }
}
