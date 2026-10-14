package dev.narze.pood

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

val http: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .pingInterval(20, TimeUnit.SECONDS)
    .build()

data class Transcript(val id: Long, val createdAt: Long, val text: String, val device: String)

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

    private suspend fun request(path: String, method: String = "GET"): JSONObject = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) throw IOException("Server URL is not set")
        val req = Request.Builder().url(baseUrl + path).method(method, null).build()
        http.newCall(req).execute().use { res ->
            val body = res.body.string()
            if (!res.isSuccessful) throw IOException("HTTP ${res.code}: ${body.take(200)}")
            if (body.isEmpty()) JSONObject() else JSONObject(body)
        }
    }
}
