package org.agentos.agent.bridge

import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale
import org.json.JSONException
import org.json.JSONObject

internal object TikTokPostVerifier {
  private const val HOST = "www.tiktok.com"
  private const val MAX_PAGE_BYTES = 2 * 1024 * 1024
  private val postPath = Regex("/(?:@|%40)([a-zA-Z0-9_.]{1,64})/(video|photo)/([0-9]{1,20})/?")
  private val pageData = Regex(
    """<script\b[^>]*\s+id\s*=\s*["'](__UNIVERSAL_DATA_FOR_REHYDRATION__|api-data)["'][^>]*>(.*?)</script\s*>""",
    setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
  )
  private data class Post(val handle: String, val kind: String, val id: String) {
    val url = "https://$HOST/@$handle/$kind/$id"
  }

  fun canonicalUrl(candidate: String): String? = parsePost(candidate)?.url
  fun postKey(candidate: String): String? = parsePost(candidate)?.let { "tiktok:${it.id}" }

  private fun parsePost(candidate: String): Post? {
    val uri = try {
      URI(candidate.trim())
    } catch (_: URISyntaxException) {
      return null
    }
    if (!uri.scheme.equals("https", ignoreCase = true) || uri.rawUserInfo != null || uri.port != -1) return null
    if (!uri.host.equals(HOST, ignoreCase = true) && !uri.host.equals("tiktok.com", ignoreCase = true)) return null
    val (handle, kind, id) = postPath.matchEntire(uri.rawPath ?: return null)?.destructured ?: return null
    return Post(handle.lowercase(Locale.ROOT), kind, id)
  }

  fun verify(pages: MenuSourcePage, candidate: String, evidence: MenuPostEvidence): MenuVideo? {
    val post = parsePost(candidate) ?: return null
    val item = try {
      val html = pages.read(post.url, MAX_PAGE_BYTES) ?: return null
      val detail = videoDetail(html) ?: return null
      if (detail.optInt("statusCode", -1) != 0) return null
      detail.optJSONObject("itemInfo")?.optJSONObject("itemStruct") ?: return null
    } catch (_: IOException) {
      return null
    } catch (_: JSONException) {
      return null
    }
    if (item.opt("id")?.toString() != post.id || item.optBoolean("privateItem") || item.optBoolean("secret")) return null
    val author = item.optJSONObject("author") ?: return null
    val authorHandle = author.opt("uniqueId") as? String ?: return null
    if (!authorHandle.equals(post.handle, ignoreCase = true) || author.optBoolean("privateAccount")) return null
    val video = item.optJSONObject("video")
    val images = item.optJSONObject("imagePost")?.optJSONArray("images")
    val playable = post.kind == "video" && images == null
    if (playable) {
      if (video == null || video.opt("id")?.toString() != post.id || video.optInt("duration") <= 0 ||
        video.optInt("width") <= 0 || video.optInt("height") <= 0) return null
    } else if (images == null || images.length() == 0) return null
    val description = item.opt("desc") as? String ?: return null
    val poi = item.optJSONObject("poi")
    val venueName = (poi?.opt("name") as? String)?.trim()?.takeIf(String::isNotBlank)
    val address = item.optJSONObject("contentLocation")?.optJSONObject("address")
    val location = MenuPostEvidence.Location(venueName,
      (poi?.opt("address") as? String) ?: (address?.opt("streetAddress") as? String),
      (poi?.opt("city") as? String) ?: (address?.opt("addressLocality") as? String))
    if (!evidence.matches(caption = description, author = authorHandle, location = location)) return null
    val imagePostCover = images?.optJSONObject(0)?.optJSONObject("imageURL")?.optJSONArray("urlList")?.optString(0)
    val cover = (imagePostCover ?: video?.opt("cover") as? String)?.takeIf { url ->
      val uri = runCatching { URI(url) }.getOrNull()
      uri?.scheme == "https" && uri.rawUserInfo == null && uri.port == -1 &&
        uri.host?.let { it.endsWith(".tiktokcdn.com") || it.endsWith(".tiktokcdn-us.com") || it.endsWith(".tiktokcdn-eu.com") } == true
    }
    val playback = if (playable) "https://www.tiktok.com/player/v1/${post.id}?autoplay=1&muted=1&description=1" else null
    val mediaType = if (playable) "video" else if (images?.length() == 1) "photo" else "carousel"
    return MenuVideo("tiktok", "@$authorHandle", post.url, description.trim().take(200), cover, playback,
      venueName, mediaType)
  }

  private fun videoDetail(html: String): JSONObject? {
    for (data in pageData.findAll(html)) {
      val (id, payload) = data.destructured
      val json = JSONObject(payload)
      // TikTok's mobile public page renders the same video data in api-data.
      val detail = if (id.equals("api-data", ignoreCase = true)) {
        json.optJSONObject("videoDetail")
      } else {
        json.optJSONObject("__DEFAULT_SCOPE__")?.optJSONObject("webapp.video-detail")
      }
      if (detail != null) return detail
    }
    return null
  }

}
