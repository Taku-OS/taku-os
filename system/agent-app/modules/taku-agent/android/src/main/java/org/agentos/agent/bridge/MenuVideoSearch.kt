package org.agentos.agent.bridge

import android.content.Context
import android.net.Uri
import java.io.IOException
import java.net.URI
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

internal data class MenuVideo(
  val platform: String,
  val author: String,
  val url: String,
  val title: String? = null,
  val thumbnailUrl: String? = null,
  val playbackUrl: String? = null,
  val venue: String? = null,
  val mediaType: String = "video",
  val viewerUrl: String? = null,
) {
  fun json() = JSONObject().put("platform", platform).put("author", author).put("url", url)
    .put("title", title).put("thumbnailUrl", thumbnailUrl).put("playbackUrl", playbackUrl)
    .put("venue", venue).put("mediaType", mediaType).put("viewerUrl", viewerUrl).put("sameBrand", true)
}

internal object MenuVideoSearch {
  private val gateway = Uri.parse("content://${TakuAgentModule.GATEWAY_AUTHORITY}")
  private const val PAGE_LIMIT_BYTES = 2_097_152
  private const val VERIFICATIONS_PER_DISH = 2
  private const val MAX_SOURCE_PAGES = 4
  private const val VERIFICATION_TIMEOUT_SECONDS = 12L
  private const val EMBED_TIMEOUT_SECONDS = 7L
  private val platforms = listOf("tiktok", "instagram", "youtube", "x", "facebook")
  private val unavailableConfiguration = setOf("AI_DISABLED", "AI_CONFIGURATION_CHANGED", "UNSUPPORTED_PARAMETER")
  private val verificationPool = Executors.newFixedThreadPool(8)
  private val postLinks = Regex("""https://(?:www\.|m\.|mobile\.|mbasic\.|web\.)?(?:tiktok\.com|instagram\.com|youtube\.com|youtu\.be|x\.com|twitter\.com|facebook\.com)/[^\s<>"'\\]+""")

  internal fun canonicalUrl(url: String): String? = TikTokPostVerifier.canonicalUrl(url)
    ?: ExternalMenuVideoVerifier.canonicalUrl(url) ?: SocialMenuPostVerifier.canonicalUrl(url)

  private fun platform(url: String): String = URI(url).host.removePrefix("www.").substringBefore('.')

  data class RoundResult(val posts: List<MenuVideo>, val needsSupplement: Boolean, val unavailable: Boolean)

  class DishSearch(
    private val context: Context,
    dish: String,
    venue: String,
    private val address: String,
    dishAliases: List<String>,
    private val sourcePages: List<String>,
    private val sourcePosts: List<String>,
    private val instagramHandle: String?,
    private val onVerified: (List<MenuVideo>) -> Unit,
  ) {
    private val names = (listOf(dish) + dishAliases).distinctBy(MenuPostEvidence::normalize)
    private val firstSearchName = dishAliases.firstOrNull(String::isNotBlank) ?: dish
    private val secondSearchName = names.firstOrNull {
      MenuPostEvidence.normalize(it) != MenuPostEvidence.normalize(firstSearchName)
    }
    private val evidence = MenuPostEvidence(venue, address, names)
    private val pagesReader = MenuSourcePage(context)
    private val checked = mutableSetOf<String>()
    private val visitedPages = mutableSetOf<String>()
    private val verifiedPosts = linkedMapOf<String, MenuVideo>()
    private var verificationTimedOut = false
    private var discoverySucceeded = false
    private var discoveryFailure: IOException? = null

    fun firstRound(): RoundResult {
      verify(sourcePosts)
      verify(pageLinks(sourcePages))
      discoverPosts(firstSearchName, focused = false)
      val maySearchAgain = discoveryFailure?.message !in unavailableConfiguration
      return result(needsSupplement = maySearchAgain &&
        (secondSearchName != null || discoveryFailure != null || verificationTimedOut || pagesReader.hadNetworkFailure))
    }

    fun supplement(retry: Boolean = false): RoundResult {
      verificationTimedOut = false
      if (retry) {
        verify(sourcePosts)
        verify(pageLinks(sourcePages))
      }
      discoverPosts(secondSearchName ?: firstSearchName, focused = true)
      return result()
    }

    private fun discoverPosts(name: String, focused: Boolean) {
      val discovery = try {
        discover(context, name, evidence.brand, address, platforms.takeIf { focused })
      } catch (failure: IOException) {
        discoveryFailure = failure
        return
      }
      discoverySucceeded = true
      discoveryFailure = null
      verify(discovery.first)
      verify(pageLinks(discovery.second))
    }

    private fun result(needsSupplement: Boolean = false) = RoundResult(verifiedPosts.values.toList(), needsSupplement,
      verifiedPosts.isEmpty() && (!discoverySucceeded && discoveryFailure != null || verificationTimedOut || pagesReader.hadNetworkFailure))

    private fun verify(candidates: Iterable<String>) {
      val canonical = candidates.mapNotNull(::canonicalUrl).distinct().filter { it !in checked }.groupBy(::platform)
      val remaining = (0 until (canonical.values.maxOfOrNull(List<String>::size) ?: 0)).asSequence().flatMap { index ->
        platforms.asSequence().mapNotNull { canonical[it]?.getOrNull(index) }
      }.iterator()
      val completion = ExecutorCompletionService<MenuVideo?>(verificationPool)
      val jobs = mutableMapOf<Future<MenuVideo?>, String>()
      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(VERIFICATION_TIMEOUT_SECONDS)
      try {
        while (remaining.hasNext() || jobs.isNotEmpty()) {
          while (remaining.hasNext() && jobs.size < VERIFICATIONS_PER_DISH) {
            val candidate = remaining.next()
            val job = completion.submit {
              when (platform(candidate)) {
                "tiktok" -> TikTokPostVerifier.verify(pagesReader, candidate, evidence)
                "instagram", "youtube" -> ExternalMenuVideoVerifier.verify(pagesReader, candidate, evidence, instagramHandle)
                else -> SocialMenuPostVerifier.verify(pagesReader, candidate, evidence)
              }
            }
            jobs[job] = candidate
          }
          val job = completion.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS)
          if (job == null) {
            verificationTimedOut = true
            return
          }
          val candidate = jobs.remove(job) ?: continue
          val post = try {
            job.get().also { checked.add(candidate) }
          } catch (failure: ExecutionException) {
            if (failure.cause !is Exception) throw failure
            verificationTimedOut = true
            continue
          } ?: continue
          val key = verifiedPostKey(post.url) ?: continue
          if (verifiedPosts.putIfAbsent(key, post) == null) onVerified(verifiedPosts.values.toList())
        }
      } finally {
        jobs.keys.forEach { it.cancel(true) }
      }
    }
    private fun pageLinks(pages: List<String>): List<String> {
      val completion = ExecutorCompletionService<Set<String>>(verificationPool)
      val jobs = pages.distinct().filter { it !in visitedPages && isSupportedEmbedPage(it) }.take(MAX_SOURCE_PAGES).map { page ->
        visitedPages.add(page)
        completion.submit { publicEmbedLinks(pagesReader, page, names) }
      }
      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(EMBED_TIMEOUT_SECONDS)
      return try {
        buildList {
          repeat(jobs.size) {
            val job = completion.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS)
            if (job == null) {
              verificationTimedOut = true
              return@buildList
            }
            try {
              addAll(job.get())
            } catch (failure: ExecutionException) {
              if (failure.cause !is Exception) throw failure
              verificationTimedOut = true
            }
          }
        }
      } finally { jobs.forEach { it.cancel(true) } }
    }
  }

  internal fun verifiedPostKey(url: String): String? = TikTokPostVerifier.postKey(url)
    ?: ExternalMenuVideoVerifier.postKey(url) ?: SocialMenuPostVerifier.postKey(url)

  private fun discover(
    context: Context, dish: String, brand: String, address: String, platforms: List<String>?,
  ): Pair<List<String>, List<String>> {
    if (Thread.currentThread().isInterrupted) throw InterruptedException()
    val input = JSONObject().put("dish", dish).put("venue", brand).put("address", address)
    if (platforms != null) input.put("platforms", JSONArray(platforms))
    val response = context.contentResolver.call(gateway, "searchMenuVideos", input.toString(), null)?.getString("result")
    if (Thread.currentThread().isInterrupted) throw InterruptedException()
    if (response == null) throw IOException("Menu search gateway unavailable")
    val envelope = JSONObject(response)
    if (!envelope.optBoolean("ok")) throw IOException(envelope.optString("code", "MENU_SEARCH_UNAVAILABLE"))
    val result = envelope.getJSONObject("result")
    fun links(key: String) = result.getJSONArray(key).let { array -> (0 until array.length()).map(array::getString) }
    return links("posts") to links("pages")
  }

  private fun isSupportedEmbedPage(link: String): Boolean {
    val uri = runCatching { URI(link) }.getOrNull() ?: return false
    if (uri.scheme != "https" || uri.userInfo != null || uri.port != -1) return false
    return when (uri.host) {
      "airial.travel", "www.airial.travel", "crispylist.com", "www.crispylist.com" -> uri.path.startsWith("/restaurants/")
      "hypedabout.com", "www.hypedabout.com" -> uri.path.startsWith("/venues/")
      "www.marketing-interactive.com" -> uri.path.startsWith("/") && uri.path.length > 1
      else -> false
    }
  }

  private fun publicEmbedLinks(pages: MenuSourcePage, link: String, names: List<String>): Set<String> {
    val html = try {
      decodeSlashes(pages.read(link, PAGE_LIMIT_BYTES) ?: return emptySet())
    } catch (_: IOException) {
      return emptySet()
    }
    return postLinks.findAll(html)
      .sortedByDescending { match ->
        val start = (match.range.first - 360).coerceAtLeast(0)
        val end = (match.range.last + 360).coerceAtMost(html.length)
        names.any { html.substring(start, end).contains(it, ignoreCase = true) }
      }
      .map { it.value.substringBefore("&quot;").replace("&amp;", "&").trimEnd('.', ',', ';') }
      .filter { canonicalUrl(it) != null }
      .take(36).toCollection(linkedSetOf())
  }

  private fun decodeSlashes(value: String): String = value.replace("\\/", "/").replace("\\u002F", "/", ignoreCase = true)

}
