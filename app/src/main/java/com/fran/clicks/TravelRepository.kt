package com.fran.clicks

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

// A boarding pass candidate: an email with a .pkpass/PDF pass or an obvious pass subject.
data class BoardingPassRef(
    val message: GmailMessage,
    val passAttachment: GmailAttachment?  // null → open the email itself
)

class TravelRepository(private val api: GmailApi) {

    // Emails that look like flight itineraries / confirmations, newest first.
    suspend fun findFlights(): List<GmailMessage> {
        val ids = api.search(
            "(flight OR itinerary OR \"e-ticket\" OR \"booking confirmation\" OR \"your trip\" OR PNR) newer_than:1y",
            maxResults = 12
        )
        return fetchAll(ids).sortedByDescending { it.date }
    }

    // Boarding passes: prefer real pass attachments, fall back to pass-titled emails.
    suspend fun findBoardingPasses(): List<BoardingPassRef> {
        val ids = api.search(
            "(filename:pkpass OR \"boarding pass\" OR \"mobile boarding pass\" OR \"gate\") newer_than:60d",
            maxResults = 12
        )
        return fetchAll(ids)
            .sortedByDescending { it.date }
            .map { msg ->
                val pass = msg.attachments.firstOrNull {
                    it.filename.endsWith(".pkpass", ignoreCase = true) ||
                        it.mimeType.contains("pkpass", ignoreCase = true) ||
                        (it.mimeType == "application/pdf" &&
                            (it.filename.contains("boarding", true) || it.filename.contains("pass", true)))
                }
                BoardingPassRef(msg, pass)
            }
    }

    private suspend fun fetchAll(ids: List<String>): List<GmailMessage> = coroutineScope {
        ids.map { id -> async { api.message(id) } }.awaitAll().filterNotNull()
    }
}
