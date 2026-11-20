package org.agentos.agent.bridge

import android.text.Html
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.net.URLEncoder
import java.util.Locale
import org.json.JSONException
import org.json.JSONObject

internal object SocialMenuPostVerifier {
  private const val X_HOST = "x.com"
  private const val FACEBOOK_HOST = "www.facebook.com"
  private const val MAX_X_BYTES = 16 * 1024
  private const val MAX_FACEBOOK_BYTES = 512 * 1024
  private const val ID = "[1-9][0-9]{0,19}"
  private const val FACEBOOK_ID = "(?:$ID|pfbid[A-Za-z0-9]{10,150})"
  private const val FACEBOOK_OWNER = "[A-Za-z0-9.]{1,100}"
  private const val FACEBOOK_SLUG = "[A-Za-z0-9_-]{1,200}"
  private val xHosts = setOf(X_HOST, "www.x.com", "mobile.x.com", "twitter.com", "www.twitter.com", "mobile.twitter.com", "m.twitter.com")
  private val facebookHosts = setOf(FACEBOOK_HOST, "facebook.com", "m.facebook.com", "mbasic.facebook.com", "web.facebook.com")
  private val xHandle = Regex("[A-Za-z0-9_]{1,15}")
  private val xPath = Regex("/(${xHandle.pattern})/status/($ID)(?:/(?:photo|video)/[1-9][0-9]?)?/?")
  private val numericId = Regex(ID)
  private val facebookId = Regex(FACEBOOK_ID)
  private val facebookOwner = Regex(FACEBOOK_OWNER)
  private val facebookPostPath = Regex("/(?:($FACEBOOK_OWNER)/)?posts/(?:$FACEBOOK_SLUG/)?($FACEBOOK_ID)/?")
  private val facebookVideoPath = Regex("/(?:($FACEBOOK_OWNER)/)?videos/(?:$FACEBOOK_SLUG/)?($ID)/?")
  private val facebookReelPath = Regex("/reel/($ID)/?")
  private val facebookPhotoPath = Regex("/(?:$FACEBOOK_OWNER/)?photos/(?:(?:a|pcb)\\.$ID/)?($ID)/?")
  private val facebookWatchProfile = Regex("/watch/($FACEBOOK_OWNER)/?")
  private val xBlock = Regex("""^<blockquote\b([^>]*)>(.*?)</blockquote\s*>$""", RegexOption.DOT_MATCHES_ALL)
  private val paragraph = Regex("""<p\b[^>]*>(.*?)</p\s*>""", RegexOption.DOT_MATCHES_ALL)
  private val anchors = Regex("""<a\b([^>]*)>(.*?)</a\s*>""", RegexOption.DOT_MATCHES_ALL)
  private val imageTag = Regex("""<img\b[^>]*>""")
  private val videoTag = Regex("""<video\b[^>]*>""")
  private val ignoredMarkup = Regex("""<!--.*?-->|<(script|style|noscript)\b[^>]*>.*?</\1\s*>""", RegexOption.DOT_MATCHES_ALL)
  private val attributes = Regex("""([\w:-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")
  private val postMessage = Regex("""<div\b[^>]*\bdata-testid=["']post_message["'][^>]*>(.*?)</div\s*>""", RegexOption.DOT_MATCHES_ALL)
  private val displayName = Regex("""<(?:img|div)\b[^>]*\baria-label\s*=[^>]*>""")
  private val whitespace = Regex("\\s+")

  private data class XPost(val handle: String, val id: String) {
    val url = "https://$X_HOST/$handle/status/$id"
  }

  private data class FacebookPost(val kind: String, val id: String, val path: String) {
    val url = "https://$FACEBOOK_HOST$path"
  }

  fun canonicalUrl(candidate: String): String? {
    val uri = httpsUri(candidate) ?: return null
    return xPost(uri)?.url ?: facebookPost(uri)?.url
  }

  fun postKey(candidate: String): String? {
    val uri = httpsUri(candidate) ?: return null
    return xPost(uri)?.let { "x:${it.id}" }
      ?: facebookPost(uri)?.let { "facebook:${it.kind}:${it.id}" }
  }

  fun verify(pages: MenuSourcePage, candidate: String, evidence: MenuPostEvidence): MenuVideo? {
    val uri = httpsUri(candidate) ?: return null
    return try {
      xPost(uri)?.let { return verifyX(pages, it, evidence) }
      facebookPost(uri)?.let { verifyFacebook(pages, it, evidence) }
    } catch (_: IOException) {
      null
    } catch (_: JSONException) {
      null
    }
  }

  private fun verifyX(pages: MenuSourcePage, post: XPost, evidence: MenuPostEvidence): MenuVideo? {
    val embedUrl = "https://publish.x.com/oembed?url=${encode(post.url)}&omit_script=true&hide_thread=true"
    val data = JSONObject(pages.readJson(embedUrl, MAX_X_BYTES) ?: return null)
    if (data.string("type") != "rich" || data.string("provider_name") !in setOf("X", "Twitter")) return null
    val original = data.string("url")?.let(::httpsUri)?.let(::xPost) ?: return null
    val profile = data.string("author_url")?.let(::httpsUri) ?: return null
    if (original != post || profile.host.lowercase(Locale.ROOT) !in xHosts ||
      !profile.rawPath.trimEnd('/').equals("/${post.handle}", ignoreCase = true)) return null
    val block = xBlock.matchEntire(data.string("html") ?: return null) ?: return null
    if (tagAttributes(block.groupValues[1])["class"] != "twitter-tweet") return null
    val body = block.groupValues[2]
    val content = paragraph.find(body) ?: return null
    val permalink = anchors.findAll(body.substring(content.range.last + 1)).lastOrNull()?.let(::href)
    if (permalink?.let(::xPost) != post) return null
    val caption = plainText(anchors.replace(content.groupValues[1]) { link ->
      val label = link.groupValues[2]
      if (label.startsWith("pic.twitter.com/") || label.startsWith("pic.x.com/")) "" else label
    })
    val author = data.string("author_name") ?: return null
    if (!evidence.matches(caption = caption, author = author)) return null
    // X's oEmbed caption includes media links without identifying the media type.
    return MenuVideo("x", "@${post.handle}", post.url, caption.take(200), mediaType = "text")
  }

  private fun verifyFacebook(pages: MenuSourcePage, post: FacebookPost, evidence: MenuPostEvidence): MenuVideo? {
    val embedUrl = "https://$FACEBOOK_HOST/plugins/post.php?href=${encode(post.url)}&show_text=true"
    val html = pages.readPublicEmbed(embedUrl, MAX_FACEBOOK_BYTES) ?: return null
    val visible = ignoredMarkup.replace(html.substringAfter("<body", ""), "")
    val message = postMessage.find(visible) ?: return null
    val header = visible.substring(0, message.range.first)
    val original = anchors.findAll(visible).mapNotNull(::facebookEmbedLink).mapNotNull(::facebookPost)
      .firstOrNull { it.kind == post.kind && it.id == post.id } ?: return null
    val authorLink = anchors.findAll(header).firstOrNull { link ->
      val profile = facebookEmbedLink(link) ?: return@firstOrNull false
      val path = profile.rawPath.removePrefix("/").removeSuffix("/")
      facebookOwner.matches(path) || facebookWatchProfile.matches(profile.rawPath) ||
        path == "profile.php" && queryValue(profile, "id")?.let(numericId::matches) == true
    } ?: return null
    val author = tagAttributes(authorLink.groupValues[1])["title"]
      ?: displayName.find(authorLink.groupValues[2])?.let { tagAttributes(it.value)["aria-label"] }
      ?: plainText(authorLink.groupValues[2]).replace("\uFFFC", "").trim().takeIf(String::isNotEmpty)
      ?: return null
    val caption = plainText(message.groupValues[1])
    if (!evidence.matches(caption = caption, author = author)) return null
    val photo = anchors.findAll(header).mapNotNull { link ->
      val image = facebookEmbedLink(link)?.let(::facebookPost)?.takeIf { it.kind == "photo" }
        ?: return@mapNotNull null
      if (post.kind == "photo" && image.id != post.id) return@mapNotNull null
      imageTag.find(link.groupValues[2])?.value?.let(::tagAttributes)?.get("src")?.let(::facebookImage)
    }.distinct().take(2).toList()
    val mediaType = when {
      videoTag.containsMatchIn(header) -> "video"
      photo.size > 1 -> "carousel"
      photo.isNotEmpty() -> "photo"
      else -> "text"
    }
    val playbackUrl = if (mediaType == "video") {
      "https://$FACEBOOK_HOST/plugins/video.php?href=${encode(original.url)}&show_text=true"
    } else null
    return MenuVideo("facebook", author, original.url, caption.take(200), thumbnailUrl = photo.firstOrNull(),
      playbackUrl = playbackUrl, mediaType = mediaType)
  }

  private fun xPost(uri: URI): XPost? {
    if (uri.host.lowercase(Locale.ROOT) !in xHosts) return null
    val (handle, id) = xPath.matchEntire(uri.rawPath)?.destructured ?: return null
    return XPost(handle.lowercase(Locale.ROOT), id)
  }

  private fun facebookPost(uri: URI): FacebookPost? {
    if (uri.host.lowercase(Locale.ROOT) !in facebookHosts) return null
    val path = uri.rawPath
    facebookPostPath.matchEntire(path)?.destructured?.let { (owner, id) ->
      return FacebookPost("post", id, ownedPath(owner, "posts", id))
    }
    facebookReelPath.matchEntire(path)?.destructured?.let { (id) ->
      return FacebookPost("video", id, "/reel/$id")
    }
    facebookVideoPath.matchEntire(path)?.destructured?.let { (owner, id) ->
      return FacebookPost("video", id, ownedPath(owner, "videos", id))
    }
    facebookPhotoPath.matchEntire(path)?.destructured?.let { (id) ->
      return FacebookPost("photo", id, path.trimEnd('/'))
    }
    return when (path.trimEnd('/')) {
      "/watch" -> queryValue(uri, "v")?.takeIf(numericId::matches)?.let { FacebookPost("video", it, "/watch/?v=$it") }
      "/photo", "/photo.php" -> queryValue(uri, "fbid")?.takeIf(numericId::matches)
        ?.let { FacebookPost("photo", it, "/photo/?fbid=$it") }
      "/story.php", "/permalink.php" -> {
        val id = queryValue(uri, "story_fbid")?.takeIf(facebookId::matches) ?: return null
        val owner = queryValue(uri, "id")?.takeIf(numericId::matches) ?: return null
        FacebookPost("post", id, "/permalink.php?story_fbid=$id&id=$owner")
      }
      else -> null
    }
  }

  private fun ownedPath(owner: String, kind: String, id: String) =
    if (owner.isEmpty()) "/$kind/$id" else "/$owner/$kind/$id"

  private fun facebookEmbedLink(anchor: MatchResult): URI? {
    val link = href(anchor, "https://$FACEBOOK_HOST") ?: return null
    return link.takeIf { it.host.lowercase(Locale.ROOT) in facebookHosts &&
      queryValue(it, "ref") in setOf("embed_post", "embed_video") }
  }

  private fun facebookImage(url: String): String? {
    val host = httpsUri(url)?.host?.lowercase(Locale.ROOT) ?: return null
    return url.takeIf { host.endsWith(".fbcdn.net") }
  }

  private fun href(anchor: MatchResult, origin: String = ""): URI? {
    val url = tagAttributes(anchor.groupValues[1])["href"] ?: return null
    return httpsUri(if (url.startsWith('/') && !url.startsWith("//")) "$origin$url" else url)
  }

  private fun queryValue(uri: URI, name: String): String? = uri.rawQuery.orEmpty().split('&')
    .filter { it.substringBefore('=') == name }.singleOrNull()?.substringAfter('=', "")

  private fun httpsUri(value: String): URI? {
    val uri = try {
      URI(value.trim())
    } catch (_: URISyntaxException) {
      return null
    }
    return uri.takeIf { it.scheme.equals("https", ignoreCase = true) && it.host != null &&
      it.rawUserInfo == null && it.port == -1 }
  }

  private fun tagAttributes(tag: String): Map<String, String> = attributes.findAll(tag).associate { field ->
    field.groupValues[1].lowercase(Locale.ROOT) to decodeAttribute(field.groups[2]?.value ?: field.groupValues[3])
  }

  private fun plainText(value: String) = Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString()
    .replace(whitespace, " ").trim()

  private fun decodeAttribute(value: String) = if ('&' in value) plainText(value) else value

  private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name())

  private fun JSONObject.string(key: String): String? = (opt(key) as? String)?.trim()?.takeIf(String::isNotEmpty)
}
