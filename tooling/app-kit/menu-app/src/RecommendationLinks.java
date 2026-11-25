package org.agentos.generated.menu;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

final class RecommendationLinks {
    private enum Platform {
        TIKTOK("com.zhiliaoapp.musically", "tiktok\\.com|www\\.tiktok\\.com",
                "/@[A-Za-z0-9._-]{1,64}/(?:video|photo)/[0-9]{8,30}/?"),
        REDNOTE("com.xingin.xhs", "xiaohongshu\\.com|www\\.xiaohongshu\\.com",
                "/(?:explore|discovery/item)/[A-Fa-f0-9]{24}/?"),
        INSTAGRAM("com.instagram.android", "instagram\\.com|www\\.instagram\\.com",
                "/(?:p|reel|tv)/[A-Za-z0-9_-]{5,40}/?"),
        X("com.twitter.android", "(?:www\\.)?(?:x|twitter)\\.com",
                "/(?:[A-Za-z0-9_]{1,15}|i/web|i)/status/[0-9]{1,25}/?"),
        YOUTUBE("com.google.android.youtube", "(?:www\\.|m\\.)?youtube\\.com",
                "/(?:watch|shorts/[A-Za-z0-9_-]{11})/?");

        final String packageName;
        final Pattern host;
        final Pattern path;

        Platform(String packageName, String host, String path) {
            this.packageName = packageName;
            this.host = Pattern.compile(host);
            this.path = Pattern.compile(path);
        }

        boolean acceptsQuery(URI uri) {
            String query = uri.getRawQuery();
            if (this == YOUTUBE && uri.getRawPath().replace("/", "").equals("watch")) {
                return query != null && query.matches("v=[A-Za-z0-9_-]{11}");
            }
            if (this == REDNOTE && query != null) {
                String token = "xsec_token=[A-Za-z0-9_%=-]{1,400}";
                String source = "xsec_source=[A-Za-z0-9_-]{1,40}";
                return query.matches(token + "|" + source + "|" + token + "&" + source
                        + "|" + source + "&" + token);
            }
            return query == null;
        }
    }

    static String nativePackage(String platformName, String url) {
        String name = platformName.toUpperCase(Locale.ROOT);
        if (name.equals("XIAOHONGSHU")) name = "REDNOTE";
        if (name.equals("TWITTER")) name = "X";
        try {
            Platform platform = Platform.valueOf(name);
            URI uri = URI.create(url);
            String host = uri.getHost();
            if ("https".equals(uri.getScheme()) && host != null && host.equals(uri.getRawAuthority())
                    && uri.getRawFragment() == null && platform.host.matcher(host).matches()
                    && platform.path.matcher(uri.getRawPath()).matches() && platform.acceptsQuery(uri)) {
                return platform.packageName;
            }
        } catch (IllegalArgumentException ignored) {
            return null;
        }
        return null;
    }

    private RecommendationLinks() {}
}
