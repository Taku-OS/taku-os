package org.agentos.agent.bridge

import android.content.Context
import android.net.ConnectivityManager
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection

internal class MenuSourcePage(context: Context) {
  companion object {
    private const val TIMEOUT_MS = 5_000
    private const val HTML_ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 " +
      "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
  }
  private val connectivity = context.getSystemService(ConnectivityManager::class.java)
  private val responses = AtomicInteger()
  private val failures = AtomicInteger()
  val hadNetworkFailure: Boolean get() = failures.get() > 0 && responses.get() == 0

  fun read(url: String, maxBytes: Int): String? = read(url, maxBytes, "text/html", HTML_ACCEPT, USER_AGENT)

  fun readJson(url: String, maxBytes: Int): String? = read(url, maxBytes, "application/json", "application/json", USER_AGENT)

  fun readPublicEmbed(url: String, maxBytes: Int): String? = read(url, maxBytes, "text/html", "text/html", "TakuOS")

  private fun read(url: String, maxBytes: Int, mediaType: String, accept: String, userAgent: String): String? {
    val deadline = System.nanoTime() + 2L * TIMEOUT_MS * 1_000_000L
    val target = URL(url)
    val configured = connectivity?.defaultProxy
    val proxy = configured?.takeIf { it.host?.isNotBlank() == true && it.port > 0 && it.pacFileUrl == android.net.Uri.EMPTY }
      ?.let { Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(it.host, it.port)) }
    val connection = (if (proxy == null) target.openConnection() else target.openConnection(proxy)) as HttpsURLConnection
    return try {
      connection.instanceFollowRedirects = false
      connection.connectTimeout = TIMEOUT_MS
      connection.readTimeout = TIMEOUT_MS
      connection.setRequestProperty("User-Agent", userAgent)
      connection.setRequestProperty("Accept", accept)
      connection.setRequestProperty("Accept-Language", "en-CA,en;q=0.9")
      val status = connection.responseCode
      if (status == 429 || status >= 500) failures.incrementAndGet() else responses.incrementAndGet()
      if (status != HttpsURLConnection.HTTP_OK ||
        connection.contentType?.substringBefore(';')?.trim()?.equals(mediaType, ignoreCase = true) != true
      ) return null
      connection.inputStream.use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (!Thread.currentThread().isInterrupted && System.nanoTime() < deadline) {
          val count = input.read(buffer)
          if (count == -1) return output.toString(Charsets.UTF_8.name())
          if (output.size() + count > maxBytes) return null
          output.write(buffer, 0, count)
        }
        null
      }
    } catch (failure: IOException) {
      failures.incrementAndGet()
      throw failure
    } finally {
      connection.disconnect()
    }
  }
}
