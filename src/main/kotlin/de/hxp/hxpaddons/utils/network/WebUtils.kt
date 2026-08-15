package de.hxp.hxpaddons.utils.network

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import de.hxp.hxpaddons.HxPMod.logger
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeoutException
import kotlin.coroutines.resume

object WebUtils {
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    val gson: Gson = GsonBuilder().create()

    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    /** Backstop above the per-request [HttpRequest.timeout] (10s) below - confirmed live that a stalled
     * connection can make the JDK HttpClient never complete its future at all (no success, no failure, no
     * timeout), e.g. `raw.githubusercontent.com/.../HEAT_CORE.json` and `.../CHEST.json` both hung forever
     * during a CraftFlipScanner run and froze the whole scan, since those fetches are memoized/shared
     * (many callers await the same in-flight request). This enforces cancellation at the coroutine level
     * regardless of whether the HttpRequest's own timeout actually fires. */
    private const val REQUEST_BACKSTOP_MS = 15_000L

    suspend inline fun <reified T> fetchJson(url: String, json: Gson = gson): Result<T> = runCatching {
        json.fromJson<T>(fetchString(url).getOrElse { return Result.failure(it) }, T::class.java)
    }

    suspend fun fetchString(url: String): Result<String> =
        executeRequest(createGetRequest(url))
            .mapCatching { response -> response.body() }
            .onFailure { logger.warn("Failed to fetch from $url: ${it.message}") }

    suspend fun getInputStream(url: String): Result<InputStream> =
        executeRequest(createGetRequest(url))
            .map { response -> response.body().byteInputStream() }
            .onFailure { logger.warn("Failed to get input stream from $url: ${it.message}") }

    suspend fun postData(url: String, body: String): Result<String> =
        executeRequest(
            HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(10))
                .build()
        )
            .mapCatching { response -> response.body() }
            .onFailure { logger.warn("Failed to post data to $url: ${it.message}") }

    private fun createGetRequest(url: String): HttpRequest =
        HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .GET()
            .timeout(Duration.ofSeconds(10))
            .build()

    private suspend fun executeRequest(request: HttpRequest): Result<HttpResponse<String>> =
        withTimeoutOrNull(REQUEST_BACKSTOP_MS) {
            suspendCancellableCoroutine { cont ->
                logger.info("Making request to ${request.uri()}")

                val future = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())

                cont.invokeOnCancellation {
                    logger.info("Cancelling request to ${request.uri()}")
                    future.cancel(true)
                }

                future.whenComplete { response, error ->
                    if (error != null) {
                        if (cont.isActive) {
                            logger.warn("Request failed for ${request.uri()}: ${error.message}")
                            cont.resume(Result.failure(error))
                        }
                    } else {
                        if (!cont.isActive) return@whenComplete

                        if (response.statusCode() in 200..299) cont.resume(Result.success(response))
                        else cont.resume(Result.failure(InputStreamException(response.statusCode(), request.uri().toString())))
                    }
                }
            }
        } ?: run {
            logger.warn("Request to ${request.uri()} did not complete within ${REQUEST_BACKSTOP_MS}ms (client-side backstop - the request's own timeout did not fire)")
            Result.failure(TimeoutException("Request to ${request.uri()} timed out (backstop)"))
        }

    suspend fun hasBonusPaulScore(): Boolean {
        val response = fetchJson<JsonObject>("https://api.hypixel.net/resources/skyblock/election").getOrNull() ?: return false
        val mayor = response.getAsJsonObject("mayor") ?: return false
        return mayor.get("name")?.asString == "Paul" && mayor.getAsJsonArray("perks")?.any { it.asJsonObject.get("name")?.asString == "EZPZ" } == true
    }

    class InputStreamException(val code: Int, url: String) : Exception("Failed to get input stream from $url: HTTP $code")
}