package dev.narze.pood

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

val http: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .pingInterval(20, TimeUnit.SECONDS)
    .build()

data class Transcript(val id: Long, val createdAt: Long, val text: String, val device: String)

private val JSON = "application/json".toMediaType()

fun JSONArray.strings(): List<String> = List(length()) { getString(it) }

data class Me(val user: String, val model: String)

/** HTTP side of the gateway (see PROTOCOL.md). */
class PoodApi(private val baseUrl: String) {
    suspend fun me(): Me = request("/v1/me").let { Me(it.getString("user"), it.getString("model")) }

    suspend fun history(before: Long? = null, limit: Int = 50): List<Transcript> {
        val query = "?limit=$limit" + (before?.let { "&before=$it" } ?: "")
        val items = request("/v1/history$query").getJSONArray("items")
        return (0 until items.length()).map { i ->
            val t = items.getJSONObject(i)
            Transcript(t.getLong("id"), t.getLong("createdAt"), t.getString("text"), t.getString("device"))
        }
    }

    suspend fun delete(id: Long) {
        request("/v1/history/$id", method = "DELETE")
    }

    /** Names of this user's desktops that are online right now. */
    suspend fun desktops(): List<String> = request("/v1/desktops").getJSONArray("desktops").strings()

    /** Sends a history item to the named desktops; returns the ones that got it. */
    suspend fun beam(id: Long, targets: Collection<String>): List<String> {
        val body = JSONObject().put("id", id).put("targets", JSONArray(targets))
        return request("/v1/beam", method = "POST", body = body).getJSONArray("delivered").strings()
    }

    private suspend fun request(path: String, method: String = "GET", body: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) throw IOException("Server URL is not set")
        val req = Request.Builder().url(baseUrl + path).method(method, body?.toString()?.toRequestBody(JSON)).build()
        http.newCall(req).execute().use { res ->
            val body = res.body.string()
            if (!res.isSuccessful) throw IOException("HTTP ${res.code}: ${body.take(200)}")
            if (body.isEmpty()) JSONObject() else JSONObject(body)
        }
    }
}
