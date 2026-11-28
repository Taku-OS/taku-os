package org.agentos.lab;

import android.text.Html;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.agentos.ai.NotebookSocialSource;
import org.json.JSONException;
import org.json.JSONObject;

final class NotebookPublicPost {
    record Published(String url, List<String> authors, List<String> text) {}

    private static final int HTML_LIMIT = 2 * 1024 * 1024;
    private static final int JSON_LIMIT = 64 * 1024;
    private static final String MOBILE_USER_AGENT = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";
    private static final String INSTAGRAM = "https://www.instagram.com";
    private static final String YOUTUBE = "https://www.youtube.com";
    private static final String FACEBOOK = "https://www.facebook.com";
    private static final String REDNOTE = "https://www.rednote.com";
    private static final String ATTRIBUTES = "((?:[^>\"']++|\"[^\"]*+\"|'[^']*+')*+)";
    private static final Pattern SCRIPT = Pattern.compile("(?is)<script\\b" + ATTRIBUTES + ">(.*?)</script\\s*>");
    private static final Pattern COMMENT_OR_CODE = Pattern.compile(
            "(?is)<!--.*?-->|<(script|style)\\b" + ATTRIBUTES + ">.*?</\\1\\s*>");
    private static final Pattern TAG = Pattern.compile("(?i)<(/?)([a-z][a-z0-9-]*)\\b" + ATTRIBUTES + ">");
    private static final Pattern ATTRIBUTE = Pattern.compile(
            "([\\w:-]+)(?:\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+)))?");
    private static final Pattern INSTAGRAM_HANDLE = Pattern.compile("[A-Za-z0-9_.]{1,30}");
    private static final Pattern INSTAGRAM_TITLE = Pattern.compile("(?s)^(.{1,100}?) on Instagram: [\"“](.*)[\"”]$");
    private static final Pattern FACEBOOK_POST_ALIAS = Pattern.compile(
            "^https://www\\.facebook\\.com/([A-Za-z0-9._-]+/(?:posts|videos))/[A-Za-z0-9._-]+/([0-9]+)/?$");
    private static final Pattern SHARE_TOKEN = Pattern.compile("[A-Za-z0-9_+/\\-]{16,256}={0,2}");
    private static final Pattern SHARE_SOURCE = Pattern.compile("[A-Za-z0-9_-]{1,40}");
    private static final Set<String> PLACEHOLDERS = Set.of("facebook", "instagram", "tiktok", "youtube", "小红书",
            "log in to facebook", "log in • instagram", "page not found", "video unavailable",
            "this content isn't available right now", "sorry, this page isn't available", "当前笔记暂时无法浏览");

    private NotebookPublicPost() {}

    static Published read(NotebookSocialSource platform, String canonical, String discoveredUrl) throws Exception {
        if (canonical == null || !canonical.equals(platform.canonicalUrl(canonical))) return null;
        return switch (platform) {
            case REDNOTE -> rednote(canonical, discoveredUrl);
            case INSTAGRAM -> instagram(canonical);
            case YOUTUBE -> youtube(canonical);
            case TIKTOK -> tiktok(canonical);
            case FACEBOOK -> facebook(canonical);
            case X -> null;
        };
    }

    private static Published youtube(String canonical) throws Exception {
        Response response = request(YOUTUBE + "/oembed?format=json&url=" + encode(canonical), true, false);
        JSONObject embed = json(response.body());
        if (embed == null || !"video".equals(value(embed, "type"))) return null;
        Element player = uniqueElement(value(embed, "html"), "iframe", attrs -> attrs.containsKey("src"));
        if (player == null || !canonical.equals(NotebookSocialSource.YOUTUBE.canonicalUrl(player.get("src")))) {
            return null;
        }
        String profile = value(embed, "author_url");
        String handle = profile.startsWith(YOUTUBE + "/@") ? profile.substring(YOUTUBE.length() + 1) : "";
        return published(canonical, List.of(value(embed, "author_name"), handle), value(embed, "title"));
    }

    private static Published tiktok(String canonical) throws Exception {
        String[] path = URI.create(canonical).getPath().split("/");
        String page = request(canonical, false, true).body();
        JSONObject detail = child(script(page, attrs -> "api-data".equals(attrs.get("id")), ""), "videoDetail");
        if (detail == null) {
            JSONObject data = script(page, attrs -> "__UNIVERSAL_DATA_FOR_REHYDRATION__".equals(attrs.get("id")), "");
            detail = child(data, "__DEFAULT_SCOPE__", "webapp.video-detail");
        }
        JSONObject item = child(detail, "itemInfo", "itemStruct");
        JSONObject author = child(item, "author");
        String handle = value(author, "uniqueId");
        if (!path[3].equals(value(item, "id")) || !path[1].equalsIgnoreCase("@" + handle)) return null;
        return published(canonical, List.of(handle, value(author, "nickname")), value(item, "desc"));
    }

    private static Published rednote(String canonical, String discoveredUrl) throws Exception {
        String id = lastSegment(canonical);
        String publicUrl = REDNOTE + "/zh/discovery/item/" + id;
        String publicPage = request(publicUrl, false, true).body();
        JSONObject article = script(publicPage, attrs -> "application/ld+json".equals(attrs.get("type")), "");
        if ("Article".equals(value(article, "@type"))
                && canonical.equals(NotebookSocialSource.REDNOTE.canonicalUrl(value(article, "mainEntityOfPage")))
                && canonical.equals(NotebookSocialSource.REDNOTE.canonicalUrl(meta(publicPage, "og:url")))) {
            Published post = rednoteArticle(publicUrl, article);
            if (post != null) return post;
        }

        String query = rednoteShareQuery(canonical, discoveredUrl);
        Response response = request(canonical + query, false, true);
        if (response.location() != null) {
            URI redirect = resolve(canonical, response.location());
            String item = URI.create(canonical).getPath().replace("/explore/", "/discovery/item/");
            if (redirect == null || !item.equals(redirect.getRawPath())
                    || !canonical.equals(NotebookSocialSource.REDNOTE.canonicalUrl(redirect.toString()))
                    || !"www.xiaohongshu.com".equalsIgnoreCase(redirect.getRawAuthority())) return null;
            response = request("https://www.xiaohongshu.com" + item + query, false, true);
        }
        String page = response.body();
        JSONObject state = script(page, attrs -> true, "window.__SETUP_SERVER_STATE__=");
        JSONObject note = child(state, "LAUNCHER_SSR_STORE_PAGE_DATA", "noteData");
        if (id.equals(value(note, "noteId"))) {
            Published post = published(canonical, List.of(value(child(note, "user"), "nickName")),
                    value(note, "title"), value(note, "desc"));
            if (post != null) return post;
        }
        if (!canonical.equals(NotebookSocialSource.REDNOTE.canonicalUrl(meta(page, "og:url")))) return null;
        JSONObject structured = script(page, attrs -> "application/ld+json".equals(attrs.get("type")), "");
        return rednoteArticle(canonical, structured);
    }

    private static Published rednoteArticle(String url, JSONObject article) {
        return published(url, List.of(value(child(article, "author"), "name")),
                value(article, "headline").replaceFirst(" - (?:小红书|rednote)$", ""), value(article, "description"));
    }

    private static String rednoteShareQuery(String canonical, String discoveredUrl) {
        if (!canonical.equals(NotebookSocialSource.REDNOTE.canonicalUrl(discoveredUrl))) return "";
        URI source = URI.create(discoveredUrl);
        String host = source.getHost().toLowerCase(Locale.ROOT);
        if (!host.equals("xiaohongshu.com") && !host.endsWith(".xiaohongshu.com")) return "";
        String token = queryValue(source, "xsec_token");
        String origin = queryValue(source, "xsec_source");
        if (!SHARE_TOKEN.matcher(token).matches()) return "";
        return "?xsec_token=" + encode(token)
                + (SHARE_SOURCE.matcher(origin).matches() ? "&xsec_source=" + encode(origin) : "");
    }

    private static Published instagram(String canonical) throws Exception {
        Published post = instagramCaption(canonical, markup(request(canonical + "embed/captioned/", false, false).body()));
        if (post != null) return post;
        String page = request(canonical, false, false).body();
        String permalink = NotebookSocialSource.INSTAGRAM.canonicalUrl(linkCanonical(page));
        if (!sameInstagramPost(canonical, permalink)) return null;
        URI ogUrl = resolve(canonical, meta(page, "og:url"));
        if (ogUrl == null || !"www.instagram.com".equalsIgnoreCase(ogUrl.getRawAuthority())
                || !"https".equals(ogUrl.getScheme())) return null;
        String[] path = ogUrl.getPath().split("/", 3);
        if (path.length != 3 || !INSTAGRAM_HANDLE.matcher(path[1]).matches()
                || !sameInstagramPost(canonical, NotebookSocialSource.INSTAGRAM.canonicalUrl(INSTAGRAM + "/" + path[2]))) {
            return null;
        }
        Matcher title = INSTAGRAM_TITLE.matcher(meta(page, "og:title"));
        return title.matches() ? published(permalink, List.of(path[1], title.group(1)), title.group(2)) : null;
    }

    private static Published instagramCaption(String canonical, String embed) {
        Element post = uniqueElement(embed, "div", attrs -> hasClass(attrs, "Embed"));
        if (post == null) return null;
        String permalink = NotebookSocialSource.INSTAGRAM.canonicalUrl(post.get("data-permalink"));
        if (!sameInstagramPost(canonical, permalink)) return null;
        String inside = post.inner(embed);
        Element caption = uniqueElement(inside, "div", attrs -> hasClass(attrs, "Caption"));
        if (caption == null) return null;
        String content = caption.inner(inside);
        Element author = uniqueElement(content, "a", attrs -> hasClass(attrs, "CaptionUsername"));
        if (author == null) return null;
        String username = plain(author.inner(content));
        if (!INSTAGRAM_HANDLE.matcher(username).matches()
                || !(INSTAGRAM + "/" + username + "/").equals(profileUrl(author.get("href")))) return null;
        Element comments = uniqueElement(content, "div", attrs -> hasClass(attrs, "CaptionComments"));
        int start = author.outerEnd(content);
        int end = comments == null ? content.length() : comments.start();
        return start <= end ? published(permalink, List.of(username), plain(content.substring(start, end))) : null;
    }

    private static boolean sameInstagramPost(String canonical, String link) {
        return link != null && lastSegment(link).equals(lastSegment(canonical));
    }

    private static Published facebook(String canonical) throws Exception {
        String url = FACEBOOK + "/plugins/post.php?show_text=true&href=" + encode(canonical);
        String embed = markup(request(url, false, false).body());
        Element container = uniqueElement(embed, "div", attrs -> hasClass(attrs, "pluginSkinLight"));
        if (container == null) return null;
        String content = container.inner(embed);
        Element message = uniqueElement(content, "div", attrs -> "post_message".equals(attrs.get("data-testid")));
        if (message == null) return null;
        String header = content.substring(0, message.start());
        String expected = facebookId(canonical);
        String permalink = facebookHeaderPermalink(header, expected);
        if (permalink == null) {
            if (!expected.startsWith("pfbid")) return null;
            // The official post page can translate an opaque permalink into its numeric post ID.
            String page = request(canonical, false, false).body();
            String ogId = facebookId(meta(page, "og:url"));
            if (ogId.isEmpty() || !ogId.equals(facebookId(linkCanonical(page)))) return null;
            permalink = facebookHeaderPermalink(header, ogId);
            if (permalink == null) return null;
        }
        Element author = uniqueElement(header, null, attrs -> hasClass(attrs, "_2_79") || hasClass(attrs, "_90h_"));
        if (author == null) return null;
        Element videoTitle = uniqueElement(header, "div", attrs -> hasClass(attrs, "_8z50"));
        return published(permalink, List.of(plain(author.inner(header))), plain(message.inner(content)),
                videoTitle == null ? "" : plain(videoTitle.inner(header)));
    }

    private static String facebookHeaderPermalink(String header, String id) {
        if (id.isEmpty()) return null;
        Matcher tags = TAG.matcher(header);
        while (tags.find()) {
            if (!isOpeningTag(tags, "a")) continue;
            String href = attributes(tags.group(3)).get("href");
            URI link = href == null ? null : resolve(FACEBOOK, plain(href));
            if (link != null && Set.of("embed_post", "embed_video").contains(queryValue(link, "ref"))
                    && id.equals(facebookId(link.toString()))) {
                return NotebookSocialSource.FACEBOOK.canonicalUrl(link.toString());
            }
        }
        return null;
    }

    private static String facebookId(String url) {
        if (url == null) return "";
        Matcher alias = FACEBOOK_POST_ALIAS.matcher(url);
        if (alias.matches()) url = FACEBOOK + "/" + alias.group(1) + "/" + alias.group(2);
        String normalized = NotebookSocialSource.FACEBOOK.canonicalUrl(url);
        if (normalized == null) return "";
        URI uri = URI.create(normalized);
        return switch (uri.getPath()) {
            case "/watch/" -> queryValue(uri, "v");
            case "/permalink.php" -> queryValue(uri, "story_fbid");
            default -> lastSegment(normalized);
        };
    }

    private record Response(String body, String location) {}

    private static Response request(String address, boolean json, boolean mobile) throws Exception {
        HttpURLConnection connection = PhoneNetwork.openConnection(URI.create(address).toURL());
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(5000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", json ? "application/json" : "text/html");
        connection.setRequestProperty("User-Agent", mobile ? MOBILE_USER_AGENT : "Mozilla/5.0");
        connection.setRequestProperty("Accept-Language", mobile ? "zh-CN,zh;q=0.9" : "en-US,en;q=0.9");
        connection.setRequestProperty("Cookie", "");
        try {
            int status = connection.getResponseCode();
            if (status == 408 || status == 429 || status >= 500) {
                throw new IOException("Public post service returned HTTP " + status);
            }
            if (status >= 300 && status < 400) return new Response("", connection.getHeaderField("Location"));
            if (status != 200) return new Response("", null);
            int limit = json ? JSON_LIMIT : HTML_LIMIT;
            if (connection.getContentLengthLong() > limit) return new Response("", null);
            try (InputStream input = connection.getInputStream()) {
                return new Response(NativeBridge.read(input, limit), null);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static URI resolve(String base, String value) {
        if (value == null || value.isBlank() || value.length() > 2048) return null;
        try {
            return URI.create(base).resolve(value);
        } catch (IllegalArgumentException invalidUri) {
            return null;
        }
    }

    private static String profileUrl(String url) {
        URI uri = resolve(INSTAGRAM, url);
        return uri == null ? "" : uri.getScheme() + "://" + uri.getRawAuthority() + uri.getRawPath();
    }

    private static String lastSegment(String url) {
        String path = URI.create(url).getPath().replaceFirst("/$", "");
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static String queryValue(URI uri, String key) {
        String query = uri.getRawQuery();
        if (query == null) return "";
        String found = null;
        for (String pair : query.split("&")) {
            if (!pair.startsWith(key + "=")) continue;
            if (found != null) return "";
            try {
                found = URLDecoder.decode(pair.substring(key.length() + 1), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException invalidEncoding) {
                return "";
            }
        }
        return found == null ? "" : found;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static JSONObject json(String data) {
        try {
            return data.isBlank() ? null : new JSONObject(data);
        } catch (JSONException invalidJson) {
            return null;
        }
    }

    private static JSONObject child(JSONObject parent, String... names) {
        for (String name : names) {
            if (parent == null) return null;
            parent = parent.optJSONObject(name);
        }
        return parent;
    }

    private static String value(JSONObject object, String field) {
        return object != null && object.opt(field) instanceof String value ? value : "";
    }

    private static JSONObject script(String page, Predicate<Map<String, String>> matches, String prefix) {
        Matcher scripts = SCRIPT.matcher(page);
        while (scripts.find()) {
            String body = scripts.group(2).strip();
            if (body.startsWith(prefix) && matches.test(attributes(scripts.group(1)))) {
                return json(body.substring(prefix.length()));
            }
        }
        return null;
    }

    private static String meta(String page, String key) {
        Element element = uniqueElement(markup(page), "meta",
                attrs -> key.equalsIgnoreCase(attrs.getOrDefault("property", attrs.get("name"))));
        return element == null ? "" : Objects.toString(element.get("content"), "");
    }

    private static String linkCanonical(String page) {
        Element link = uniqueElement(markup(page), "link", attrs -> "canonical".equalsIgnoreCase(attrs.get("rel")));
        return link == null ? "" : Objects.toString(link.get("href"), "");
    }

    private static String markup(String page) {
        return COMMENT_OR_CODE.matcher(page).replaceAll("");
    }

    private static String plain(String markup) {
        return Html.fromHtml(markup, Html.FROM_HTML_MODE_LEGACY).toString().strip();
    }

    private static Map<String, String> attributes(String markup) {
        Map<String, String> result = new LinkedHashMap<>();
        Matcher attrs = ATTRIBUTE.matcher(markup);
        while (attrs.find()) {
            String value = attrs.group(2) != null ? attrs.group(2)
                    : attrs.group(3) != null ? attrs.group(3) : attrs.group(4);
            result.putIfAbsent(attrs.group(1).toLowerCase(Locale.ROOT), value == null ? "" : value);
        }
        return result;
    }

    private static boolean hasClass(Map<String, String> attrs, String target) {
        for (String name : attrs.getOrDefault("class", "").split("\\s+")) {
            if (target.equals(name)) return true;
        }
        return false;
    }

    private static boolean isOpeningTag(Matcher tag, String name) {
        return tag.group(1).isEmpty() && (name == null || name.equalsIgnoreCase(tag.group(2)));
    }

    private static Element uniqueElement(String page, String name, Predicate<Map<String, String>> matches) {
        Element found = null;
        Matcher tags = TAG.matcher(page);
        while (tags.find()) {
            if (!isOpeningTag(tags, name)) continue;
            Map<String, String> attrs = attributes(tags.group(3));
            if (!matches.test(attrs)) continue;
            if (found != null) return null;
            found = new Element(tags.group(2), attrs, tags.start(), tags.end());
        }
        return found;
    }

    private record Element(String tag, Map<String, String> attrs, int start, int openEnd) {
        String get(String key) {
            String value = attrs.get(key);
            return value == null ? null : plain(value);
        }

        String inner(String source) {
            Matcher end = close(source);
            return end == null ? "" : source.substring(openEnd, end.start());
        }

        int outerEnd(String source) {
            Matcher end = close(source);
            return end == null ? source.length() : end.end();
        }

        private Matcher close(String source) {
            int depth = 1;
            Matcher tags = TAG.matcher(source).region(openEnd, source.length());
            while (tags.find()) {
                if (!tag.equalsIgnoreCase(tags.group(2))) continue;
                depth += tags.group(1).isEmpty() ? 1 : -1;
                if (depth == 0) return tags;
            }
            return null;
        }
    }

    private static Published published(String url, List<String> authors, String... content) {
        List<String> names = authors.stream().map(String::strip).filter(NotebookPublicPost::usable).distinct().toList();
        List<String> text = List.of(content).stream().map(String::strip).filter(NotebookPublicPost::usable)
                .map(value -> value.substring(0, value.offsetByCodePoints(0, Math.min(5000, value.codePointCount(0, value.length())))))
                .distinct().toList();
        return names.isEmpty() || text.isEmpty() ? null : new Published(url, names, text);
    }

    private static boolean usable(String text) {
        return !text.isBlank() && !PLACEHOLDERS.contains(text.toLowerCase(Locale.ROOT).replaceFirst("[.!]+$", ""));
    }
}
