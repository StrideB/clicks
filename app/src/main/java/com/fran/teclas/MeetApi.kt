package com.fran.teclas

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.UUID

/**
 * Google Meet skill: create a Meet link and drop it into any chat, no app switch. Reuses the shared
 * Google OAuth (GmailAuth) — needs the calendar.events scope (added there) and the Calendar API
 * enabled in the Cloud project. A Meet link is created by inserting an instant Calendar event and
 * reading its hangoutLink. Blocking; call off the main thread.
 */
object MeetApi {

    fun createMeeting(context: Context): String? {
        val token = runBlocking { GmailAuth(context).getValidToken() } ?: return null
        val now = Instant.now()
        val body = JSONObject().apply {
            put("summary", "Quick sync (Teclas)")
            put("start", JSONObject().put("dateTime", now.toString()))
            put("end", JSONObject().put("dateTime", now.plusSeconds(1800).toString()))
            put(
                "conferenceData",
                JSONObject().put(
                    "createRequest",
                    JSONObject()
                        .put("requestId", UUID.randomUUID().toString())
                        .put("conferenceSolutionKey", JSONObject().put("type", "hangoutsMeet"))
                )
            )
        }
        val resp = postJson(
            "https://www.googleapis.com/calendar/v3/calendars/primary/events?conferenceDataVersion=1",
            token, body.toString()
        ) ?: return null
        val json = runCatching { JSONObject(resp) }.getOrNull() ?: return null
        json.optString("hangoutLink").takeIf { it.isNotBlank() }?.let { return it }
        // Fallback: read the video entry point from conferenceData.
        return json.optJSONObject("conferenceData")?.optJSONArray("entryPoints")
            ?.let { arr -> (0 until arr.length()).map { arr.optJSONObject(it) } }
            ?.firstOrNull { it?.optString("entryPointType") == "video" }
            ?.optString("uri")?.takeIf { it.isNotBlank() }
    }

    private fun postJson(url: String, token: String, body: String): String? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true
            connectTimeout = 8_000; readTimeout = 15_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            if (conn.responseCode !in 200..299) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } finally { conn.disconnect() }
    } catch (_: Exception) { null }
}
