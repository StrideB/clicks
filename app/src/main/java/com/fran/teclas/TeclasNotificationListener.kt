package com.fran.teclas

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import com.fran.teclas.brief.NotificationRecord
import com.fran.teclas.brief.RawAction
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import kotlin.math.abs

class TeclasNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        instance = this
        activeNotifications.orEmpty().forEach { onNotificationPosted(it) }
    }

    /** Cancel a live notification by key (used by Today's long-press dismiss). Instance-scoped. */
    fun cancelByKey(key: String) {
        runCatching { cancelNotification(key) }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (instance === this) instance = null
        // Iterating a synchronizedMap needs the lock held for the whole sweep, else a concurrent
        // post could mutate it mid-iteration and throw ConcurrentModificationException.
        synchronized(notificationAvatars) {
            notificationAvatars.values.forEach { runCatching { it.recycle() } }
            notificationAvatars.clear()
        }
        notificationIntents.clear()
        replyActions.clear()
        synchronized(briefRecords) { briefRecords.clear() }
        synchronized(dockBadgeKeys) { dockBadgeKeys.clear() }
        synchronized(liveActivities) { liveActivities.clear() }
        ongoingCallPackage = null
        ongoingTimerPackage = null
        onDockStateChanged?.invoke()
        onBriefChanged?.invoke()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Brief capture runs for EVERY notification (not just hub candidates) so the "Today" brief
        // can surface any of them with all their inline actions. This is independent of the hub
        // widget-stack path below, which keeps its existing narrower filtering.
        captureBriefRecord(sbn)
        updateDockLiveState(sbn, posted = true)

        if (!sbn.isHubCandidate()) return

        val extras = sbn.notification.extras
        val sender = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val preview = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (sender.isBlank() && preview.isBlank()) return
        val kind = sbn.hubKind()
        if (kind == HUB_KIND_MESSAGE && !isConversationPerson(sender, preview, sbn.packageName)) return
        if (kind in DIRECT_OPEN_KINDS && sbn.notification.contentIntent == null) return

        sbn.notification.contentIntent?.let { notificationIntents[sbn.key] = it }
        captureReplyAction(sbn)
        notificationAvatar(sbn.notification)?.let { newBitmap ->
            // Atomic size-check + FIFO evict + put: without the lock, two posts could each see
            // size < MAX, both put, and overflow the cap — or evict the same key twice.
            synchronized(notificationAvatars) {
                if (notificationAvatars.size >= MAX_AVATARS) {
                    val evict = notificationAvatars.keys.firstOrNull()
                    if (evict != null) notificationAvatars.remove(evict)?.let { runCatching { it.recycle() } }
                }
                notificationAvatars[sbn.key] = newBitmap
            }
        }

        val item = JSONObject()
            .put("key", sbn.key)
            .put("sender", sender.trim())
            .put("preview", preview)
            .put("packageName", sbn.packageName)
            .put("kind", kind)
            .put("color", colorForPackage(sbn.packageName))
            .put("lastUpdated", System.currentTimeMillis())

        val current = readMessages().filterNot { it.optString("key") == sbn.key }
        val next = JSONArray()
        next.put(item)
        current.take(MAX_MESSAGES - 1).forEach { next.put(it) }
        prefs().edit().putString(HUB_MESSAGES_PREF, next.toString()).apply()
    }

    /**
     * Track the three live-dock signals off the notification stream: per-package badge dots
     * (clearable, content-bearing notifications), an ongoing call, and an ongoing
     * alarm/timer/stopwatch. Fires [onDockStateChanged] only on real transitions.
     */
    private fun updateDockLiveState(sbn: StatusBarNotification, posted: Boolean) {
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (sbn.packageName == packageName) return
        var changed = false
        val ongoing = !sbn.isClearable || (n.flags and Notification.FLAG_ONGOING_EVENT) != 0
        if (ongoing) {
            when {
                n.category == Notification.CATEGORY_CALL -> {
                    val next = if (posted) sbn.packageName else null
                    if (ongoingCallPackage != next) { ongoingCallPackage = next; changed = true }
                }
                n.category == Notification.CATEGORY_ALARM || n.category == "stopwatch" ||
                    sbn.packageName in CLOCK_PACKAGES -> {
                    val next = if (posted) sbn.packageName else null
                    if (ongoingTimerPackage != next) { ongoingTimerPackage = next; changed = true }
                }
            }
            // Live-activity snapshot: read once per event, then idle. Content-hash gating means a
            // byte-identical repost never wakes the launcher.
            if (posted) {
                val snap = liveSnapshot(sbn)
                if (snap != null) {
                    val prev = synchronized(liveActivities) { liveActivities.put(sbn.key, snap) }
                    if (prev == null || prev.contentHash != snap.contentHash) changed = true
                }
            } else {
                if (synchronized(liveActivities) { liveActivities.remove(sbn.key) } != null) changed = true
            }
        } else if (posted) {
            val extras = n.extras
            val hasContent = !extras.getCharSequence(Notification.EXTRA_TITLE).isNullOrBlank() ||
                !extras.getCharSequence(Notification.EXTRA_TEXT).isNullOrBlank()
            val badgeable = hasContent && n.category != Notification.CATEGORY_TRANSPORT &&
                n.category != Notification.CATEGORY_SERVICE && n.category != Notification.CATEGORY_SYSTEM
            if (badgeable) {
                changed = synchronized(dockBadgeKeys) { dockBadgeKeys.put(sbn.key, sbn.packageName) == null }
            }
        } else {
            changed = synchronized(dockBadgeKeys) { dockBadgeKeys.remove(sbn.key) != null }
        }
        if (changed) onDockStateChanged?.invoke()
    }

    /** Build the one-shot immutable snapshot of an ongoing notification, or null if not pip-worthy. */
    private fun liveSnapshot(sbn: StatusBarNotification): LiveActivitySnapshot? {
        if (sbn.packageName in LIVE_EXCLUDED_PACKAGES) return null
        val n = sbn.notification
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val line = (extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT))?.toString()?.trim().orEmpty()
        if (title.isBlank() && line.isBlank()) return null
        val isMedia = extras.containsKey(Notification.EXTRA_MEDIA_SESSION) ||
            n.category == Notification.CATEGORY_TRANSPORT
        // Plain fire-and-forget actions only — reply actions need a RemoteInput UI the pip doesn't have.
        val actions = n.actions.orEmpty().mapNotNull { a ->
            val pi = a.actionIntent ?: return@mapNotNull null
            if (a.remoteInputs?.isNotEmpty() == true) return@mapNotNull null
            val label = a.title?.toString()?.trim().orEmpty()
            if (label.isBlank()) null else LiveAction(label, pi)
        }.take(3)
        val progress = extras.getInt(Notification.EXTRA_PROGRESS, 0)
        val progressMax = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val kindRank = when {
            n.category == Notification.CATEGORY_CALL -> 0
            n.category == Notification.CATEGORY_NAVIGATION -> 1
            n.category == Notification.CATEGORY_ALARM || n.category == "stopwatch" ||
                sbn.packageName in CLOCK_PACKAGES -> 3
            isMedia -> 4
            else -> 2
        }
        val hash = buildString {
            append(title).append('|').append(line).append('|')
            append(progress).append('/').append(progressMax).append('|')
            actions.forEach { append(it.label).append(',') }
        }.hashCode()
        return LiveActivitySnapshot(
            key = sbn.key,
            pkg = sbn.packageName,
            title = title,
            line = line,
            progress = progress,
            progressMax = progressMax,
            indeterminate = extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false),
            kindRank = kindRank,
            whenMs = n.`when`,
            usesChronometer = extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false),
            accent = n.color,
            isMedia = isMedia,
            postTime = sbn.postTime,
            contentIntent = n.contentIntent,
            actions = actions,
            contentHash = hash,
        )
    }

    // Find a direct-reply action (RemoteInput) on this notification and remember it, so the launcher
    // can reply to a message (Telegram, WhatsApp, …) inline without opening the app.
    private fun captureReplyAction(sbn: StatusBarNotification) {
        val actions = sbn.notification.actions ?: return
        for (action in actions) {
            val intent = action.actionIntent ?: continue
            val remoteInput = action.remoteInputs?.firstOrNull { it.allowFreeFormInput } ?: continue
            replyActions[sbn.key] = ReplyAction(intent, remoteInput.resultKey, sbn.packageName)
            return
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        updateDockLiveState(sbn, posted = false)
        notificationIntents.remove(sbn.key)
        replyActions.remove(sbn.key)
        // Reply PendingIntents die with the notification, so the brief must drop this record and
        // re-collect. Do NOT recycle the avatar here — it is shared with notificationAvatars below.
        val removedBrief = briefRecords.remove(sbn.key) != null
        notificationAvatars.remove(sbn.key)?.let { runCatching { it.recycle() } }
        val next = JSONArray()
        readMessages()
            .filterNot { it.optString("key") == sbn.key }
            .forEach { next.put(it) }
        prefs().edit().putString(HUB_MESSAGES_PREF, next.toString()).apply()
        if (removedBrief) onBriefChanged?.invoke()
    }

    /**
     * Snapshot every actionable notification into [briefRecords] with its contentIntent and every
     * inline action (label + PendingIntent + RemoteInput). Skips group summaries and content-less
     * notifications. Never serialized — PendingIntents are process-lifetime handles.
     */
    private fun captureBriefRecord(sbn: StatusBarNotification) {
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        // Ongoing / non-dismissable notifications (security cams, transport controls, persistent
        // status) can't be cleared and aren't "Today" items — skip them so the brief stays actionable.
        if (!sbn.isClearable || n.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString()?.trim().orEmpty()
        if (title.isBlank() && text.isBlank()) return

        val actions = n.actions?.mapNotNull { a ->
            val pi = a.actionIntent ?: return@mapNotNull null
            val label = a.title?.toString()?.trim().orEmpty()
            if (label.isBlank()) return@mapNotNull null
            val remotes = a.remoteInputs
            val free = remotes?.firstOrNull { it.allowFreeFormInput }
            val extras2: Array<RemoteInput> = remotes?.filter { it !== free }?.toTypedArray() ?: emptyArray()
            RawAction(label, pi, free, extras2)
        }.orEmpty()

        // Nothing to act on and nothing to open → not worth a card.
        if (actions.isEmpty() && n.contentIntent == null) return

        val record = NotificationRecord(
            key = sbn.key,
            packageName = sbn.packageName,
            appLabel = packageManagerLabel(sbn.packageName),
            title = title,
            text = text,
            contentHash = notificationContentHash(sbn.packageName, title, text, actions),
            category = briefCategory(sbn),
            personName = title.takeIf { it.isNotBlank() },
            whenMs = sbn.postTime,
            contentIntent = n.contentIntent,
            actions = actions,
            avatar = notificationAvatars[sbn.key]
        )

        val unchanged = synchronized(briefRecords) {
            val previous = briefRecords.remove(sbn.key)
            if (briefRecords.size >= MAX_BRIEF) {
                briefRecords.keys.firstOrNull()?.let { briefRecords.remove(it) }
            }
            briefRecords[sbn.key] = record
            previous != null && previous.contentHash == record.contentHash
        }
        // Apps repost byte-identical notifications constantly — sync ticks, message-list redraws,
        // a chat rewriting the same summary line. Every invoke below drives a full brief refresh
        // that ends in an *uncached* ~700-token LLM generation (BriefGenerator.geminiRank), so a
        // phone doing nothing but receiving chatter sat in a near-permanent inference loop. That
        // is the "something pings it and it gets warm" path.
        //
        // The record itself is still replaced above — its PendingIntents are fresh handles and the
        // old ones die with the notification. Only the downstream refresh is skipped, and only when
        // the content hash says nothing a ranker could see has changed.
        if (!unchanged) onBriefChanged?.invoke()
    }

    private fun briefCategory(sbn: StatusBarNotification): String {
        val category = sbn.notification.category
        return when {
            category == Notification.CATEGORY_CALL || category == "missed_call" -> "call"
            category == Notification.CATEGORY_EMAIL || sbn.packageName in EMAIL_PACKAGES -> "email"
            category == Notification.CATEGORY_MESSAGE || sbn.packageName in MESSAGE_PACKAGES -> "message"
            else -> "other"
        }
    }

    private fun notificationContentHash(packageName: String, title: String, text: String, actions: List<RawAction>): String {
        val payload = buildString {
            append(packageName.trim().lowercase()).append('\n')
            append(title.trim()).append('\n')
            append(text.trim()).append('\n')
            actions.map { it.label.trim().lowercase() }.sorted().forEach { append(it).append('|') }
        }
        return payload.hashCode().toString(16)
    }

    private fun StatusBarNotification.isHubCandidate(): Boolean {
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        if (notification.category == Notification.CATEGORY_MESSAGE) return true
        if (notification.category == Notification.CATEGORY_EMAIL) return true
        if (packageName in NEWS_PACKAGES) return true
        if (packageName in MAPS_PACKAGES && isMapsContextNotification()) return true
        return packageName in MESSAGE_PACKAGES || packageName in EMAIL_PACKAGES
    }

    private fun StatusBarNotification.hubKind(): String {
        if (notification.category == Notification.CATEGORY_EMAIL || packageName in EMAIL_PACKAGES) return HUB_KIND_EMAIL
        if (packageName in NEWS_PACKAGES) return HUB_KIND_NEWS
        if (packageName in MAPS_PACKAGES) return HUB_KIND_MAPS
        return HUB_KIND_MESSAGE
    }

    private fun StatusBarNotification.isMapsContextNotification(): Boolean {
        val extras = notification.extras
        val text = listOf(
            extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
            extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(),
            extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        ).joinToString(" ")
        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return true
        return text.contains(Regex("\\b(navigat|driv|ETA|traffic|route|arrival)\\b", RegexOption.IGNORE_CASE))
    }

    private fun readMessages(): List<JSONObject> {
        val raw = prefs().getString(HUB_MESSAGES_PREF, "[]") ?: "[]"
        val array = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        return buildList {
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let { add(it) }
            }
        }
    }

    private fun packageManagerLabel(packageName: String): String {
        return runCatching {
            val info = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        }.getOrDefault("Message")
    }

    private fun isConversationPerson(sender: String, preview: String, packageName: String): Boolean {
        val cleanSender = sender.trim()
        val cleanPreview = preview.trim()
        if (cleanSender.isBlank()) return false
        if (cleanSender.equals(packageManagerLabel(packageName), ignoreCase = true)) return false
        if (cleanSender.equals(packageName.substringAfterLast('.'), ignoreCase = true)) return false
        if (cleanPreview.contains(Regex("\\b\\d+\\s+messages?\\s+from\\b", RegexOption.IGNORE_CASE))) return false
        if (cleanSender.contains(Regex("\\b\\d+\\s+messages?\\b", RegexOption.IGNORE_CASE))) return false
        return true
    }

    private fun colorForPackage(packageName: String): Int {
        val palette = intArrayOf(
            0xFF5FD0C4.toInt(),
            0xFF54A9EB.toInt(),
            0xFFF5C451.toInt(),
            0xFFFF5A3C.toInt(),
            0xFFC4B5FF.toInt(),
            0xFF8FD694.toInt()
        )
        return palette[abs(packageName.hashCode()) % palette.size]
    }

    private fun notificationAvatar(notification: Notification): Bitmap? {
        val largeIconDrawable = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            notification.getLargeIcon()?.loadDrawable(this)
        } else {
            @Suppress("DEPRECATION")
            notification.largeIcon?.let { android.graphics.drawable.BitmapDrawable(resources, it) }
        }
        return largeIconDrawable?.toBitmap(96, 96)
    }

    private fun Drawable.toBitmap(width: Int, height: Int): Bitmap {
        // Deliberately no BitmapDrawable shortcut. Returning the drawable's own bitmap handed back
        // memory owned by the notification's Icon, which is still live in the system UI — and both
        // eviction paths here call recycle() on whatever they stored, so we were recycling someone
        // else's bitmap ("trying to use a recycled bitmap" later, far from this code). It also
        // ignored the requested size, so a sender's full-resolution avatar was cached at whatever
        // dimensions it arrived with, up to MAX_AVATARS of them. Drawing into our own 96x96 costs
        // ~36 KB and makes ownership unambiguous.
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        setBounds(0, 0, canvas.width, canvas.height)
        draw(canvas)
        return bitmap
    }

    private fun prefs() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class IncomingMessage(val sender: String, val preview: String, val key: String, val whenMs: Long)

    /** A notification action the live-activity pip can fire. */
    data class LiveAction(val label: String, val intent: PendingIntent)

    /** Immutable one-shot read of an ongoing notification for the live dock. */
    data class LiveActivitySnapshot(
        val key: String,
        val pkg: String,
        val title: String,
        val line: String,
        val progress: Int,
        val progressMax: Int,
        val indeterminate: Boolean,
        /** Priority rank: 0 call, 1 navigation, 2 ride/delivery/other, 3 timer, 4 media. */
        val kindRank: Int,
        val whenMs: Long,
        val usesChronometer: Boolean,
        val accent: Int,
        val isMedia: Boolean,
        val postTime: Long,
        val contentIntent: PendingIntent?,
        val actions: List<LiveAction>,
        val contentHash: Int,
    )

    companion object {
        private const val PREFS_NAME = "teclas"
        private const val HUB_MESSAGES_PREF = "hub_messages"
        private const val HUB_KIND_MESSAGE = "message"
        private const val HUB_KIND_EMAIL = "email"

        /** Newest incoming person-message captured for [pkg], or across ALL messaging apps when
         *  [pkg] is null (used by the docked keyboard, which doesn't know the front app). Reads the
         *  same shared hub store the launcher uses — "what the person just asked" while you reply. */
        fun latestConversation(context: Context, pkg: String?): IncomingMessage? {
            val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(HUB_MESSAGES_PREF, "[]") ?: return null
            val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return null
            var best: IncomingMessage? = null
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (pkg != null && o.optString("packageName") != pkg) continue
                if (o.optString("kind") != HUB_KIND_MESSAGE) continue
                val whenMs = o.optLong("lastUpdated")
                if (best == null || whenMs > best!!.whenMs) {
                    best = IncomingMessage(o.optString("sender"), o.optString("preview"), o.optString("key"), whenMs)
                }
            }
            return best
        }
        private const val HUB_KIND_NEWS = "news"
        private const val HUB_KIND_MAPS = "maps"
        private const val MAX_MESSAGES = 12
        private const val MAX_AVATARS = 20
        private val DIRECT_OPEN_KINDS = setOf(HUB_KIND_EMAIL, HUB_KIND_NEWS, HUB_KIND_MAPS)
        // Shared between the listener service (writer) and MainActivity (reader). Wrapped in a
        // synchronized LinkedHashMap so per-op access is safe AND insertion order is preserved,
        // which the avatar FIFO eviction below relies on. Reads in MainActivity are single-key
        // get/remove (atomic here); the service's compound size-check-evict-put and the
        // clear-and-recycle sweep are guarded with synchronized(notificationAvatars) blocks.
        // TODO: These process-lifetime static maps are still a leak vector (bitmaps outlive any
        //       Activity). A lifecycle-scoped holder would be more robust, but that changes
        //       ownership/behavior, so it's out of scope for this thread-safety-only fix.
        val notificationIntents: MutableMap<String, PendingIntent> =
            Collections.synchronizedMap(LinkedHashMap())
        val notificationAvatars: MutableMap<String, Bitmap> =
            Collections.synchronizedMap(LinkedHashMap())

        // Full actionable snapshot of live notifications for the "Today" brief. Process-lifetime,
        // never serialized (holds live PendingIntents). FIFO-evicted at MAX_BRIEF. Insertion order
        // is preserved for the eviction below and for newest-first reads.
        val briefRecords: MutableMap<String, NotificationRecord> =
            Collections.synchronizedMap(LinkedHashMap())
        private const val MAX_BRIEF = 40

        /** Set by MainActivity to refresh the brief on notification post/remove. Debounce downstream. */
        @Volatile
        var onBriefChanged: (() -> Unit)? = null

        // ── Live-dock state ──────────────────────────────────────────────────
        // Everything below is written by the listener on events the system already delivers and
        // read by the launcher to paint dock badges — no polling anywhere. onDockStateChanged only
        // fires when the visible state actually changed (first badge for a package, last one gone,
        // a call/timer starting or ending), so a chatty app reposting the same notification never
        // wakes the launcher.

        /** notification key → package, for active clearable notifications worth a dock dot. */
        private val dockBadgeKeys: MutableMap<String, String> = Collections.synchronizedMap(LinkedHashMap())

        /** Package with an ongoing call notification, or null. */
        @Volatile var ongoingCallPackage: String? = null

        /** Package with an ongoing alarm/timer/stopwatch notification, or null. */
        @Volatile var ongoingTimerPackage: String? = null

        /** Set by MainActivity; invoked (on the listener thread) when dock badge state changes. */
        @Volatile var onDockStateChanged: (() -> Unit)? = null

        /** Packages that currently deserve a dock badge dot. Safe from any thread. */
        fun dockBadgePackages(): Set<String> = synchronized(dockBadgeKeys) { dockBadgeKeys.values.toSet() }

        private val CLOCK_PACKAGES = setOf("com.google.android.deskclock", "com.sec.android.app.clockpackage")

        // ── Live activities (ongoing notifications → dock takeover + pip) ────
        // One immutable snapshot per ongoing notification, read once at post time. Holds only
        // strings, ints, and PendingIntents (process-lifetime handles, same contract as
        // briefRecords) — never the Notification, its Bundle, or any Bitmap.

        private val liveActivities: MutableMap<String, LiveActivitySnapshot> =
            Collections.synchronizedMap(LinkedHashMap())

        /** Snapshot list of current live activities. Safe from any thread. */
        fun liveActivitySnapshots(): List<LiveActivitySnapshot> =
            synchronized(liveActivities) { liveActivities.values.toList() }

        fun liveActivitySnapshot(key: String): LiveActivitySnapshot? =
            synchronized(liveActivities) { liveActivities[key] }

        private val LIVE_EXCLUDED_PACKAGES = setOf("android", "com.android.systemui")

        @Volatile
        private var instance: TeclasNotificationListener? = null

        /** Dismiss a notification from Today: cancel it if connected, drop its record either way. */
        fun dismiss(key: String) {
            instance?.cancelByKey(key)
            synchronized(briefRecords) { briefRecords.remove(key) }
            onBriefChanged?.invoke()
        }

        /** Newest-first snapshot of captured notifications. Safe to call off the listener thread. */
        fun briefSnapshot(): List<NotificationRecord> =
            synchronized(briefRecords) { briefRecords.values.toList() }.asReversed()

        fun briefRecord(key: String): NotificationRecord? =
            synchronized(briefRecords) { briefRecords[key] }

        /** Dismiss a hub notification: drop it from the hub prefs, forget its intent/avatar, and —
         *  if we're connected — cancel the underlying system notification so it doesn't come back. */
        // A captured inline-reply action: the pending intent to fire plus the RemoteInput key the
        // target app expects the reply text under.
        data class ReplyAction(val intent: PendingIntent, val resultKey: String, val packageName: String)

        val replyActions: MutableMap<String, ReplyAction> =
            Collections.synchronizedMap(LinkedHashMap())

        /** True when the notification for [key] carries a direct-reply action we can drive. */
        fun canReply(key: String): Boolean = key.isNotBlank() && replyActions[key] != null

        /** Send [text] as an inline reply to the notification for [key] via its RemoteInput. */
        fun sendReply(context: Context, key: String, text: String): Boolean {
            val action = replyActions[key] ?: return false
            if (text.isBlank()) return false
            return runCatching {
                val fill = Intent()
                val results = android.os.Bundle().apply { putCharSequence(action.resultKey, text) }
                android.app.RemoteInput.addResultsToIntent(
                    arrayOf(android.app.RemoteInput.Builder(action.resultKey).build()), fill, results
                )
                action.intent.send(context, 0, fill)
                true
            }.getOrDefault(false)
        }

        fun dismiss(context: Context, key: String) {
            if (key.isBlank()) return
            runCatching { instance?.cancelNotification(key) }
            notificationIntents.remove(key)
            replyActions.remove(key)
            synchronized(briefRecords) { briefRecords.remove(key) }
            notificationAvatars.remove(key)?.let { runCatching { it.recycle() } }
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(HUB_MESSAGES_PREF, "[]") ?: "[]"
            val array = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
            val next = JSONArray()
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                if (o.optString("key") != key) next.put(o)
            }
            prefs.edit().putString(HUB_MESSAGES_PREF, next.toString()).apply()
            onBriefChanged?.invoke()
        }

        private val MESSAGE_PACKAGES = setOf(
            "com.google.android.apps.messaging",
            "com.whatsapp",
            "com.whatsapp.w4b",
            "org.telegram.messenger",
            "com.facebook.orca",
            "com.instagram.android",
            "com.Slack"
        )

        private val EMAIL_PACKAGES = setOf(
            "com.google.android.gm",
            "com.microsoft.office.outlook",
            "com.yahoo.mobile.client.android.mail",
            "com.readdle.spark",
            "com.protonmail.android",
            "me.bluemail.mail"
        )

        private val NEWS_PACKAGES = setOf(
            "com.google.android.apps.magazines"
        )

        private val MAPS_PACKAGES = setOf(
            "com.google.android.apps.maps"
        )
    }
}
