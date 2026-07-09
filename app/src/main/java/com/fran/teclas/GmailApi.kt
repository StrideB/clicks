package com.fran.teclas

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// A single Gmail message reduced to what the travel features need.
data class GmailMessage(
    val id: String,
    val threadId: String,
    val from: String,
    val subject: String,
    val date: Long,          // epoch millis (internalDate)
    val snippet: String,
    val bodyText: String,
    val attachments: List<GmailAttachment>
)

data class GmailAttachment(
    val messageId: String,
    val attachmentId: String,
    val filename: String,
    val mimeType: String
)

class GmailApi(private val auth: GmailAuth) {

    private val base = "https://gmail.googleapis.com/gmail/v1/users/me"

    // Search message IDs matching a Gmail query (same syntax as the Gmail search box).
    suspend fun search(query: String, maxResults: Int = 15): List<String> = withContext(Dispatchers.IO) {
        val token = auth.getValidToken() ?: return@withContext emptyList()
        val q = URLEncoder.encode(query, "UTF-8")
        val json = get("$base/messages?q=$q&maxResults=$maxResults", token) ?: return@withContext emptyList()
        val arr = json.optJSONArray("messages") ?: return@withContext emptyList()
        buildList { for (i in 0 until arr.length()) add(arr.getJSONObject(i).getString("id")) }
    }

    // Fetch and flatten a single message.
    suspend fun message(id: String): GmailMessage? = withContext(Dispatchers.IO) {
        val token = auth.getValidToken() ?: return@withContext null
        val json = get("$base/messages/$id?format=full", token) ?: return@withContext null
        val payload = json.optJSONObject("payload") ?: return@withContext null
        val headers = payload.optJSONArray("headers")
        fun header(name: String): String {
            val h = headers ?: return ""
            for (i in 0 until h.length()) {
                val o = h.getJSONObject(i)
                if (o.getString("name").equals(name, ignoreCase = true)) return o.optString("value")
            }
            return ""
        }
        val body = StringBuilder()
        val attachments = mutableListOf<GmailAttachment>()
        walkParts(payload, id, body, attachments)
        GmailMessage(
            id = id,
            threadId = json.optString("threadId"),
            from = header("From"),
            subject = header("Subject"),
            date = json.optString("internalDate").toLongOrNull() ?: 0L,
            snippet = json.optString("snippet"),
            bodyText = body.toString().trim(),
            attachments = attachments
        )
    }

    // Download an attachment's raw bytes (e.g. a .pkpass or PDF boarding pass).
    suspend fun attachmentBytes(messageId: String, attachmentId: String): ByteArray? = withContext(Dispatchers.IO) {
        val token = auth.getValidToken() ?: return@withContext null
        val json = get("$base/messages/$messageId/attachments/$attachmentId", token) ?: return@withContext null
        val data = json.optString("data").ifBlank { return@withContext null }
        runCatching { Base64.decode(data, Base64.URL_SAFE) }.getOrNull()
    }

    // Recursively pull plain-text body and collect attachment refs from a MIME tree.
    private fun walkParts(part: JSONObject, messageId: String, body: StringBuilder, attachments: MutableList<GmailAttachment>) {
        val mimeType = part.optString("mimeType")
        val filename = part.optString("filename")
        val partBody = part.optJSONObject("body")
        val attachmentId = partBody?.optString("attachmentId").orEmpty()

        if (filename.isNotBlank() && attachmentId.isNotBlank()) {
            attachments.add(GmailAttachment(messageId, attachmentId, filename, mimeType))
        }
        if (mimeType == "text/plain") {
            partBody?.optString("data")?.takeIf { it.isNotBlank() }?.let {
                runCatching { body.append(String(Base64.decode(it, Base64.URL_SAFE))).append('\n') }
            }
        } else if (mimeType == "text/html" && body.isBlank()) {
            // Fall back to stripped HTML only if we have no plain-text yet.
            partBody?.optString("data")?.takeIf { it.isNotBlank() }?.let {
                runCatching {
                    val html = String(Base64.decode(it, Base64.URL_SAFE))
                    body.append(html.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ")).append('\n')
                }
            }
        }
        part.optJSONArray("parts")?.let { parts ->
            for (i in 0 until parts.length()) walkParts(parts.getJSONObject(i), messageId, body, attachments)
        }
    }

    private fun get(urlStr: String, token: String): JSONObject? {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        return runCatching {
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.connectTimeout = 10_000; conn.readTimeout = 15_000
            if (conn.responseCode !in 200..299) return null
            JSONObject(conn.inputStream.bufferedReader().readText())
        }.getOrNull().also { conn.disconnect() }
    }
}
