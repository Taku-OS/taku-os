package org.agentos.agent.bridge

import android.text.Html
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.net.URLEncoder
import java.util.Locale
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

internal object ExternalMenuVideoVerifier {
  private const val MAX_PAGE_BYTES = 2 * 1024 * 1024
  private const val MAX_OEMBED_BYTES = 16 * 1024
  private const val YOUTUBE_HOST = "www.youtube.com"
  private const val YOUTUBE_MOBILE_HOST = "m.youtube.com"
  private const val INSTAGRAM_HOST = "www.instagram.com"
  private val youtubeHosts = setOf(YOUTUBE_HOST, YOUTUBE_MOBILE_HOST, "youtube.com", "youtu.be")
  private val instagramHosts = setOf(INSTAGRAM_HOST, "instagram.com")
  private val youtubeId = Regex("[A-Za-z0-9_-]{11}")
  private val youtubeVideoPath = Regex("/(shorts|embed)/([A-Za-z0-9_-]{11})/?")
  private val instagramPostPath = Regex("/(?:[A-Za-z0-9_.]{1,30}/)?(reel|p|tv)/([A-Za-z0-9_-]{5,20})/?")
  private val instagramUsername = Regex("[A-Za-z0-9_.]{1,30}")
  private val playerResponse = Regex("""<script\b[^>]*>\s*var\s+ytInitialPlayerResponse\s*=\s*(?=\{)""")
  private val watchData = Regex("""<script\b[^>]*>\s*var\s+ytInitialData\s*=\s*(?=[{'\"])""")
  private val javascriptEscapes = Regex("""\\(?:x([0-9a-fA-F]{2})|.)""")
  private val youtubeBrowserChallenge = Regex("Sign in to confirm you[’']re not a bot")
  private val head = Regex("""<head\b[^>]*>(.*?)</head\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
  private val headTags = Regex(
    """<!--.*?-->|<(?:script|style)\b[^>]*>.*?</(?:script|style)\s*>|<(meta|link)\b(?:"[^"]*"|'[^']*'|[^'">]++)*>""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
  )
  private val attributes = Regex("""([\w:-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")
  private val instagramEmbedData = Regex("""\["PolarisEmbedSimple","init",\[\],\[\s*(?=\{)""")
  private val instagramEmbedTag = Regex("""<div\b[^>]*\bdata-permalink\s*=[^>]*>""")
  private val instagramEmbedCaption = Regex("""<div\s+class="Caption">(.*?)(?:<div\s+class="CaptionComments">|</div>)""", RegexOption.DOT_MATCHES_ALL)
  private val instagramCaptionLink = Regex("""<a\b([^>]*)>([^<]+)</a>""")
  private val instagramImageEmbed = Regex("""<a\b([^>]*\bclass="EmbeddedMedia"[^>]*)>\s*<img\b([^>]*)>""")
  private val instagramLocationPath = Regex("/explore/locations/[0-9]+(?:/[^/]+)?/?")

  private data class YouTubePost(val id: String, val isShort: Boolean) {
    val watchUrl = "https://$YOUTUBE_HOST/watch?v=$id"
    val url = if (isShort) "https://$YOUTUBE_HOST/shorts/$id" else watchUrl
  }

  private data class InstagramPost(val kind: String, val id: String) {
    val url = "https://$INSTAGRAM_HOST/$kind/$id/"
    val embedUrl = "${url}embed/captioned/"
  }

  fun canonicalUrl(candidate: String): String? {
    val uri = httpsUri(candidate) ?: return null
    return youtubePost(uri)?.watchUrl ?: instagramPost(uri)?.url
  }

  fun postKey(candidate: String): String? {
    val uri = httpsUri(candidate) ?: return null
    return youtubePost(uri)?.let { "youtube:${it.id}" } ?: instagramPost(uri)?.let { "instagram:${it.id}" }
  }

  fun verify(pages: MenuSourcePage, candidate: String, evidence: MenuPostEvidence, instagramHandle: String?): MenuVideo? {
    val uri = httpsUri(candidate) ?: return null
    return try {
      youtubePost(uri)?.let { return verifyYouTube(pages, it, evidence) }
      instagramPost(uri)?.let { verifyInstagram(pages, it, evidence, instagramHandle) }
    } catch (_: IOException) {
      null
    } catch (_: JSONException) {
      null
    }
  }

  private fun verifyYouTube(pages: MenuSourcePage, post: YouTubePost, evidence: MenuPostEvidence): MenuVideo? {
    val watch = readYouTubePage(pages, "/watch?v=${post.id}")
    val watchPlayer = watch?.let(::youTubePlayer)
    if (youtubeStatus(watchPlayer) == "OK") return verifyYouTubePlayer(watch, watchPlayer, post, evidence)
    if (watchPlayer != null && !hasYouTubeBrowserChallenge(watchPlayer)) return null

    val shorts = readYouTubePage(pages, "/shorts/${post.id}")
    val shortsPlayer = shorts?.let(::youTubePlayer)
    if (youtubeStatus(shortsPlayer) == "OK") {
      return verifyYouTubePlayer(shorts, shortsPlayer, post.copy(isShort = true), evidence)
    }
    if (shortsPlayer != null && !hasYouTubeBrowserChallenge(shortsPlayer)) return null

    val description = watch?.let { youtubeWatchDescription(it, post.id) }
    if (watchPlayer != null && description.isNullOrBlank()) return null
    return verifyYouTubeEmbed(pages, post, evidence, description.orEmpty())
  }

  private fun readYouTubePage(pages: MenuSourcePage, path: String): String? = try {
    pages.read("https://$YOUTUBE_MOBILE_HOST$path", MAX_PAGE_BYTES)
  } catch (_: IOException) {
    null
  }

  private fun youtubeStatus(player: JSONObject?) = player?.optJSONObject("playabilityStatus")?.string("status")

  private fun hasYouTubeBrowserChallenge(player: JSONObject): Boolean =
    youtubeStatus(player) == "LOGIN_REQUIRED" && player.optJSONObject("videoDetails") == null &&
      player.optJSONObject("playabilityStatus")?.string("reason")?.let(youtubeBrowserChallenge::matches) == true

  private fun verifyYouTubePlayer(html: String?, player: JSONObject?, post: YouTubePost, evidence: MenuPostEvidence): MenuVideo? {
    val video = player?.optJSONObject("videoDetails") ?: return null
    if (video.string("videoId") != post.id || video.optBoolean("isPrivate") ||
      (video.string("lengthSeconds")?.toLongOrNull() ?: 0L) <= 0L
    ) return null
    val title = video.string("title") ?: return null
    val author = video.string("author") ?: return null
    if (!evidence.matches(title, video.string("shortDescription").orEmpty(), author)) return null
    val original = html?.let(::youtubeCanonicalPost) ?: post
    if (original.id != post.id) return null
    return MenuVideo("youtube", author, original.url, title, youtubePlayerThumbnail(video, post.id))
  }

  private fun youtubeCanonicalPost(html: String): YouTubePost? =
    pageMetadata(html)["canonical"]?.let(::httpsUri)?.let(::youtubePost)

  private fun youtubeWatchDescription(html: String, id: String): String? {
    if (youtubeCanonicalPost(html)?.id != id) return null
    val start = watchData.find(html)?.range?.last?.plus(1) ?: return null
    val scriptEnd = html.indexOf("</script", start, ignoreCase = true).takeIf { it >= 0 } ?: return null
    val script = html.substring(start, scriptEnd)
    val data = when (val value = JSONTokener(javascriptEscapes.replace(script) { match ->
      match.groups[1]?.value?.let { "\\u00$it" } ?: match.value
    }).nextValue()) {
      is JSONObject -> value
      is String -> JSONObject(value)
      else -> return null
    }
    val currentId = data.optJSONObject("currentVideoEndpoint")?.optJSONObject("watchEndpoint")?.string("videoId")
    if (currentId != id) return null
    val panels = data.optJSONArray("engagementPanels") ?: return null
    for (index in 0 until panels.length()) {
      val panel = panels.optJSONObject(index)?.optJSONObject("engagementPanelSectionListRenderer") ?: continue
      if (panel.string("panelIdentifier") != "video-description-ep-identifier") continue
      val items = panel.optJSONObject("content")?.optJSONObject("structuredDescriptionContentRenderer")
        ?.optJSONArray("items") ?: return null
      for (item in 0 until items.length()) {
        val description = items.optJSONObject(item)?.optJSONObject("expandableVideoDescriptionBodyRenderer") ?: continue
        return description.optJSONObject("attributedDescriptionBodyText")?.string("content")
      }
    }
    return null
  }

  private fun youTubePlayer(html: String): JSONObject? {
    val match = playerResponse.find(html) ?: return null
    return JSONTokener(html.substring(match.range.last + 1)).nextValue() as? JSONObject
  }

  private fun youtubePlayerThumbnail(video: JSONObject, id: String): String? {
    val images = video.optJSONObject("thumbnail")?.optJSONArray("thumbnails") ?: return null
    for (index in images.length() - 1 downTo 0) {
      youtubeThumbnail(images.optJSONObject(index)?.string("url"), id)?.let { return it }
    }
    return null
  }

  private fun verifyYouTubeEmbed(
    pages: MenuSourcePage, post: YouTubePost, evidence: MenuPostEvidence, description: String,
  ): MenuVideo? {
    val encoded = URLEncoder.encode(post.watchUrl, Charsets.UTF_8.name())
    val response = pages.readJson("https://$YOUTUBE_HOST/oembed?url=$encoded&format=json", MAX_OEMBED_BYTES)
      ?: return null
    val data = JSONObject(response)
    if (data.string("type") != "video" || data.string("provider_name") != "YouTube") return null
    val iframe = data.string("html") ?: return null
    val embed = httpsUri(tagAttributes(iframe)["src"].orEmpty()) ?: return null
    if (embed.host != YOUTUBE_HOST || embed.rawPath != "/embed/${post.id}") return null
    val title = data.string("title") ?: return null
    val author = data.string("author_name") ?: return null
    if (!evidence.matches(title, description, author)) return null
    return MenuVideo("youtube", author, post.url, title, youtubeThumbnail(data.string("thumbnail_url"), post.id))
  }

  private fun verifyInstagram(pages: MenuSourcePage, post: InstagramPost, evidence: MenuPostEvidence, brandHandle: String?): MenuVideo? {
    // The public embed advertises a version that includes the caption without JavaScript.
    val html = pages.readPublicEmbed("${post.embedUrl}?_fb_noscript=1", MAX_PAGE_BYTES) ?: return null
    val embedTag = instagramEmbedTag.find(html)?.value?.let(::tagAttributes) ?: return null
    val original = httpsUri(decodeAttribute(embedTag["data-permalink"].orEmpty()))?.let(::instagramPost) ?: return null
    val mediaId = embedTag["data-media-id"] ?: return null
    val ownerId = embedTag["data-owner-id"] ?: return null
    if (original.id != post.id) return null
    val graphType = embedTag["data-media-type"]
    val mediaType = when (graphType) {
      "GraphVideo" -> "video"
      "GraphImage" -> "photo"
      "GraphSidecar" -> "carousel"
      else -> return null
    }
    val start = instagramEmbedData.find(html)?.range?.last?.plus(1) ?: return null
    val bootstrap = JSONTokener(html.substring(start)).nextValue() as? JSONObject ?: return null
    val contextJson = bootstrap.string("contextJSON")
    if (contextJson == null) {
      if (mediaType != "photo") return null
      return verifyInstagramPhotoEmbed(html, original, evidence, brandHandle)
    }
    val data = JSONObject(contextJson)
    val context = data.optJSONObject("context") ?: return null
    if (context.string("type") != graphType || context.string("shortcode") != post.id ||
      context.optBoolean("copyright_blocked")
    ) return null
    val media = data.optJSONObject("gql_data")?.optJSONObject("shortcode_media") ?: return null
    if (media.string("shortcode") != post.id || media.string("id") != mediaId) return null
    val playable = mediaType == "video"
    if (playable && (media.opt("is_video") != true || media.optDouble("video_duration", 0.0) <= 0.0)) return null
    val owner = media.optJSONObject("owner") ?: return null
    if (owner.optBoolean("is_private") || owner.string("id") != ownerId) return null
    val author = owner.string("username")?.takeIf(instagramUsername::matches) ?: return null
    val caption = media.optJSONObject("edge_media_to_caption")?.optJSONArray("edges")
      ?.optJSONObject(0)?.optJSONObject("node")?.string("text") ?: return null
    val location = media.optJSONObject("location")
    val address = location?.string("address_json")?.let { runCatching { JSONObject(it) }.getOrNull() }
    val place = MenuPostEvidence.Location(location?.string("name") ?: instagramLocation(html),
      address?.string("street_address"), address?.string("city_name"))
    if (!matchesInstagram(html, caption, author, brandHandle, evidence, place)) return null
    return MenuVideo("instagram", "@$author", original.url, caption.lineSequence().first().take(180),
      instagramMediaUrl(media.string("display_url")), post.embedUrl.takeIf { playable }, mediaType = mediaType,
      viewerUrl = "${post.embedUrl}?_fb_noscript=1".takeUnless { playable })
  }

  private fun verifyInstagramPhotoEmbed(
    html: String, post: InstagramPost, evidence: MenuPostEvidence, brandHandle: String?,
  ): MenuVideo? {
    val image = instagramImageEmbed.find(html) ?: return null
    val imagePost = httpsUri(decodeAttribute(tagAttributes(image.groupValues[1])["href"].orEmpty()))?.let(::instagramPost)
    if (imagePost != post) return null
    val thumbnail = instagramMediaUrl(decodeAttribute(tagAttributes(image.groupValues[2])["src"].orEmpty())) ?: return null
    val captionHtml = instagramEmbedCaption.find(html)?.groupValues?.get(1) ?: return null
    val authorLink = instagramCaptionLink.findAll(captionHtml).firstOrNull { link ->
      tagAttributes(link.groupValues[1])["class"] == "CaptionUsername"
    } ?: return null
    val author = authorLink.groupValues[2].takeIf(instagramUsername::matches) ?: return null
    val profile = httpsUri(decodeAttribute(tagAttributes(authorLink.groupValues[1])["href"].orEmpty())) ?: return null
    if (profile.host !in instagramHosts || !profile.rawPath.trim('/').equals(author, ignoreCase = true)) return null
    val caption = Html.fromHtml(captionHtml.substring(authorLink.range.last + 1), Html.FROM_HTML_MODE_LEGACY)
      .toString().trim()
    val place = MenuPostEvidence.Location(name = instagramLocation(html))
    if (!matchesInstagram(html, caption, author, brandHandle, evidence, place)) return null
    return MenuVideo("instagram", "@$author", post.url, caption.lineSequence().first().take(180), thumbnail,
      mediaType = "photo", viewerUrl = "${post.embedUrl}?_fb_noscript=1")
  }

  private fun instagramLocation(html: String): String? {
    val header = html.substringBefore("""<div class="Caption">""", "")
    return instagramCaptionLink.findAll(header).mapNotNull { link ->
      val attrs = tagAttributes(link.groupValues[1])
      val href = httpsUri(decodeAttribute(attrs["href"].orEmpty())) ?: return@mapNotNull null
      decodeAttribute(link.groupValues[2]).takeIf {
        attrs["data-log-event"] == "locationClick" && href.host in instagramHosts &&
          instagramLocationPath.matches(href.rawPath) && it.isNotBlank()
      }
    }.firstOrNull()
  }

  private fun matchesInstagram(
    html: String, caption: String, author: String, handle: String?, evidence: MenuPostEvidence,
    location: MenuPostEvidence.Location,
  ): Boolean {
    val verifiedHandle = handle?.takeIf(instagramUsername::matches)
    val linkedHandle = verifiedHandle?.takeIf { hasInstagramCaptionLink(html, it) }
    val mention = linkedHandle?.let { Regex("(?<![\\w.])@${Regex.escape(it)}(?!\\w|\\.[\\w.])", RegexOption.IGNORE_CASE) }
    return evidence.matches(caption = caption, author = author, location = location,
      verifiedAuthor = author.equals(verifiedHandle, ignoreCase = true), linkedRestaurant = mention)
  }

  private fun hasInstagramCaptionLink(html: String, handle: String): Boolean {
    val caption = instagramEmbedCaption.find(html)?.groupValues?.get(1) ?: return false
    return instagramCaptionLink.findAll(caption).any { link ->
      val href = decodeAttribute(tagAttributes(link.groupValues[1])["href"].orEmpty())
      val url = if (href.startsWith('/') && !href.startsWith("//")) "https://$INSTAGRAM_HOST$href" else href
      val profile = httpsUri(url)
      profile?.host in instagramHosts && profile?.rawPath?.trim('/')?.equals(handle, ignoreCase = true) == true &&
        decodeAttribute(link.groupValues[2]).equals("@$handle", ignoreCase = true)
    }
  }

  private fun pageMetadata(html: String): Map<String, String> = buildMap {
    val source = head.find(html)?.groupValues?.get(1) ?: return@buildMap
    for (tag in headTags.findAll(source)) {
      val kind = tag.groups[1]?.value ?: continue
      val fields = tagAttributes(tag.value)
      val key = if (kind.equals("meta", ignoreCase = true)) fields["property"] else fields["rel"]
      val value = fields["content"] ?: fields["href"]
      if (key != null && !value.isNullOrBlank() && (key.startsWith("og:") || key == "canonical")) {
        putIfAbsent(key, decodeAttribute(value))
      }
    }
  }

  private fun tagAttributes(tag: String): Map<String, String> = attributes.findAll(tag).associate { field ->
    field.groupValues[1].lowercase(Locale.ROOT) to (field.groups[2]?.value ?: field.groupValues[3])
  }

  private fun decodeAttribute(value: String): String = value.lineSequence().joinToString("\n") { line ->
    if ('&' in line) Html.fromHtml(line, Html.FROM_HTML_MODE_LEGACY).toString() else line
  }.trim()

  private fun youtubePost(uri: URI): YouTubePost? {
    val host = uri.host.lowercase(Locale.ROOT)
    val path = uri.rawPath ?: return null
    if (host !in youtubeHosts) return null
    if (host == "youtu.be") {
      val id = path.removePrefix("/").removeSuffix("/").takeIf(youtubeId::matches) ?: return null
      return YouTubePost(id, false)
    }
    if (path == "/watch") {
      val id = uri.rawQuery.orEmpty().split('&').filter { it.startsWith("v=") }.singleOrNull()
        ?.removePrefix("v=")?.takeIf(youtubeId::matches) ?: return null
      return YouTubePost(id, false)
    }
    val (kind, id) = youtubeVideoPath.matchEntire(path)?.destructured ?: return null
    return YouTubePost(id, kind == "shorts")
  }

  private fun instagramPost(uri: URI): InstagramPost? {
    if (uri.host.lowercase(Locale.ROOT) !in instagramHosts) return null
    val (kind, id) = instagramPostPath.matchEntire(uri.rawPath ?: return null)?.destructured ?: return null
    return InstagramPost(kind, id)
  }

  private fun youtubeThumbnail(value: String?, id: String): String? {
    val uri = httpsUri(value.orEmpty()) ?: return null
    return value.takeIf { uri.host == "i.ytimg.com" && uri.rawPath.startsWith("/vi/$id/") }
  }

  private fun instagramMediaUrl(value: String?): String? {
    val host = httpsUri(value.orEmpty())?.host?.lowercase(Locale.ROOT) ?: return null
    return value.takeIf { host.endsWith(".cdninstagram.com") || host.endsWith(".fbcdn.net") }
  }

  private fun httpsUri(value: String): URI? {
    val uri = try {
      URI(value.trim())
    } catch (_: URISyntaxException) {
      return null
    }
    return uri.takeIf { it.scheme.equals("https", ignoreCase = true) && it.rawUserInfo == null && it.port == -1 && it.host != null }
  }

  private fun JSONObject.string(key: String): String? = (opt(key) as? String)?.trim()?.takeIf(String::isNotEmpty)
}
