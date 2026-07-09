package com.fran.teclas

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Scroller
import android.widget.TextView
import com.fran.teclas.MainActivity.Companion.Ink
import com.fran.teclas.MainActivity.Companion.InkDim
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * MainActivity-side host for the Spotify/Music library pane: Spotify library preload +
 * caches, the compact in-pane library overlay (tabs, playlist drill-down, search), the
 * full-screen library panel, the iPod-style click-wheel docks, and the music-black
 * transport dock. Bodies are moved verbatim from MainActivity and run with the activity
 * as receiver, so all view helpers/theme tokens keep resolving exactly as before.
 */
internal class MusicPaneHost(private val activity: MainActivity) {

    // ── Spotify library caches (shared by compact + full library UIs) ────────
    internal var spotifyCachedRecent = listOf<SpotifyTrack>()
    internal var spotifyCachedRecentArts = listOf<android.graphics.Bitmap?>()
    internal var spotifyCachedPlaylists = listOf<SpotifyPlaylist>()
    internal var spotifyCachedPlaylistArts = listOf<android.graphics.Bitmap?>()
    internal var spotifyCachedTopTracks = listOf<SpotifyTrack>()
    internal var spotifyCachedTopArts = listOf<android.graphics.Bitmap?>()
    internal var spotifyCachedLikedSongs = listOf<SpotifyTrack>()
    internal var spotifyCachedLikedArts = listOf<android.graphics.Bitmap?>()
    private var compactLibraryScrollRef: ScrollView? = null
    private var compactLibraryTargetY = 0
    internal var spotifyCompactOverlay: View? = null
    internal var spotifyFullLibraryDismiss: (() -> Unit)? = null

    private fun clickWheelDock(): View { with(activity) {
        return FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
                0xFF101216.toInt(),
                0xFF050506.toInt(),
                0xFF000000.toInt()
            )).apply {
                setStroke(dp(1), 0xFF20232A.toInt())
            }
            val wheelSize = clickWheelSize()
            addView(WheelWellView(context), FrameLayout.LayoutParams(wheelSize + dp(18), wheelSize + dp(18), Gravity.CENTER))
            addView(ClickWheelView(context).apply {
                onCenter = {
                    haptic(this)
                    mediaSessionSource.togglePlayPause()
                }
                onLeft = {
                    haptic(this)
                    mediaSessionSource.skipToPrevious()
                }
                onRight = {
                    haptic(this)
                    mediaSessionSource.skipToNext()
                }
                onBottom = {
                    haptic(this)
                    mediaSessionSource.togglePlayPause()
                }
                onTop = {
                    haptic(this)
                    mediaSessionSource.openSourceApp()
                }
                onScroll = { steps ->
                    mediaSessionSource.adjustVolume(steps)
                    showVolumeHud()
                }
            }, FrameLayout.LayoutParams(wheelSize, wheelSize, Gravity.CENTER))
        }
    } }

    // ── Spotify preload ───────────────────────────────────────────────────────

    private var lastSpotifyPreloadMs = 0L

    fun preloadSpotifyLibrary() { with(activity) {
        // Full preload is 4 API pages + up to 200 art downloads; never repeat it within 10 minutes.
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastSpotifyPreloadMs < 10 * 60_000L) return
        lastSpotifyPreloadMs = nowMs
        mediaUiScope.launch(Dispatchers.IO) {
            try {
                coroutineScope {
                    val recentD = async { spotifyApi.getRecentlyPlayed(limit = 50) }
                    val playlistsD = async { spotifyApi.getPlaylists(limit = 50) }
                    val topD = async { spotifyApi.getTopTracks(limit = 50, timeRange = "long_term") }
                    val likedD = async { spotifyApi.getLikedSongs(limit = 50) }
                    val recent = recentD.await()
                    val playlists = playlistsD.await()
                    val top = topD.await()
                    val liked = likedD.await()
                    // Tracks from the same album share art: fetch each distinct URL once and share
                    // the Bitmap instance across all four caches instead of ~200 separate downloads.
                    val artUrls = (recent.mapNotNull { it.albumArtUrl } + playlists.mapNotNull { it.imageUrl } +
                        top.mapNotNull { it.albumArtUrl } + liked.mapNotNull { it.albumArtUrl }).distinct()
                    val artByUrl = artUrls.map { url ->
                        async { url to runCatching { spotifyApi.fetchAlbumArt(url) }.getOrNull() }
                    }.awaitAll().toMap()
                    val recentArts = recent.map { it.albumArtUrl?.let(artByUrl::get) }
                    val playlistArts = playlists.map { it.imageUrl?.let(artByUrl::get) }
                    val topArts = top.map { it.albumArtUrl?.let(artByUrl::get) }
                    val likedArts = liked.map { it.albumArtUrl?.let(artByUrl::get) }
                    spotifyCachedRecent = recent
                    spotifyCachedRecentArts = recentArts
                    spotifyCachedPlaylists = playlists
                    spotifyCachedPlaylistArts = playlistArts
                    spotifyCachedTopTracks = top
                    spotifyCachedTopArts = topArts
                    spotifyCachedLikedSongs = liked
                    spotifyCachedLikedArts = likedArts
                }
            } catch (_: Exception) {}
        }
    } }

    // ── Compact playlist / track-list drill-down ─────────────────────────────

    fun showCompactPlaylistDetail(playlist: SpotifyPlaylist) { with(activity) {
        val overlay = spotifyCompactOverlay as? android.view.ViewGroup ?: return
        val detail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0A0C0F.toInt())
            translationX = overlay.width.toFloat()
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(12), dp(14), dp(10))
        }
        header.addView(TextView(this).apply {
            text = "‹"; textSize = 24f; setTextColor(0xFF6B7280.toInt())
            setPadding(dp(10), 0, dp(10), 0)
            setOnClickListener { haptic(this); closeCompactPlaylistDetail() }
        })
        header.addView(TextView(this).apply {
            text = playlist.name; textSize = 14f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(0xFFF3F0E7.toInt()); maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val actRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(4), dp(14), dp(10))
        }
        fun aBtn(lbl: String, bg: Int, fg: Int, click: () -> Unit) = TextView(this).apply {
            text = lbl; textSize = 11f; gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif", Typeface.BOLD); setTextColor(fg)
            background = GradientDrawable().apply { setColor(bg); cornerRadius = dp(18).toFloat() }
            setPadding(dp(14), dp(7), dp(14), dp(7))
            setOnClickListener { haptic(this); click() }
        }
        actRow.addView(aBtn("▶  Play All", 0xFF1ED760.toInt(), 0xFF000000.toInt()) {
            mediaUiScope.launch { spotifyApi.playContext(playlist.uri, 0) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
        actRow.addView(aBtn("⇄  Shuffle", 0xFF1A1D22.toInt(), 0xFFCED2DA.toInt()) {
            mediaUiScope.launch { spotifyApi.setShuffle(true); spotifyApi.playContext(playlist.uri, 0) }
        })

        val trackScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER
        }
        val trackList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, dp(8), dp(24))
        }
        trackList.addView(TextView(this).apply {
            text = "Loading…"; textSize = 12f; setTextColor(0xFF6B7280.toInt())
            setPadding(dp(16), dp(12), 0, 0)
        })
        trackScroll.addView(trackList)

        detail.addView(header, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        detail.addView(View(this).apply { setBackgroundColor(0x12FFFFFF) }, LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
        detail.addView(actRow, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        detail.addView(View(this).apply { setBackgroundColor(0x08FFFFFF) }, LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
        detail.addView(trackScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        overlay.addView(detail, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        detail.animate().translationX(0f).setDuration(280)
            .setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
        compactDetailView = detail
        compactLibraryScrollRef = trackScroll; compactLibraryTargetY = 0
        compactSelectedView = null

        mediaUiScope.launch {
            val tracks = withContext(Dispatchers.IO) { spotifyApi.getPlaylistTracks(playlist.id, limit = 100) }
            val arts = withContext(Dispatchers.IO) {
                coroutineScope { tracks.map { t -> async { t.albumArtUrl?.let { runCatching { spotifyApi.fetchAlbumArt(it) }.getOrNull() } } }.awaitAll() }
            }
            withContext(Dispatchers.Main) {
                trackList.removeAllViews()
                if (tracks.isEmpty()) {
                    trackList.addView(TextView(activity).apply {
                        text = "No tracks found"; textSize = 12f; setTextColor(0xFF6B7280.toInt()); setPadding(dp(16), dp(12), 0, 0)
                    })
                } else {
                    tracks.forEachIndexed { i, track ->
                        trackList.addView(compactTrackRow(i + 1, arts.getOrNull(i), track.name, track.artist, track.popularity) {
                            mediaUiScope.launch { spotifyApi.playContext(playlist.uri, i) }
                        })
                        if (i < tracks.lastIndex) trackList.addView(View(activity).apply { setBackgroundColor(0x08FFFFFF) }, LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
                    }
                }
            }
        }
    } }

    fun compactTrackRow(num: Int?, art: android.graphics.Bitmap?, title: String, artist: String, popularity: Int = 0, onClick: () -> Unit): View = with(activity) {
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56); setPadding(dp(8), dp(6), dp(8), dp(6))
            setOnClickListener { haptic(this); popThenRun(this) { onClick() } }
            if (num != null) {
                addView(TextView(activity).apply {
                    text = "$num"; textSize = 10f; gravity = Gravity.CENTER
                    setTextColor(0xFF4B5563.toInt()); minWidth = dp(28)
                })
            }
            val thumb = ImageView(activity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                if (art != null) setImageBitmap(art) else setBackgroundColor(0xFF1A1D22.toInt())
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(4).toFloat()) }
                }
                clipToOutline = true
            }
            addView(thumb, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(10) })
            val col = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
            col.addView(TextView(activity).apply {
                text = title; textSize = 12.5f; maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(0xFFF3F0E7.toInt())
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
            })
            val subRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            }
            subRow.addView(TextView(activity).apply {
                text = artist; textSize = 10.5f; maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(0xFF6B7280.toInt())
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (popularity > 0) {
                // Small popularity bar — Spotify doesn't share actual play counts
                val barTrack = FrameLayout(activity).apply {
                    background = GradientDrawable().apply { setColor(0xFF1A1D22.toInt()); cornerRadius = dp(2).toFloat() }
                }
                val barFill = View(activity).apply {
                    background = GradientDrawable().apply { setColor(0xFF1ED760.toInt()); cornerRadius = dp(2).toFloat() }
                }
                barTrack.addView(barFill, FrameLayout.LayoutParams((dp(40) * popularity / 100f).toInt(), dp(3)))
                barTrack.addView(View(activity), FrameLayout.LayoutParams(dp(40), dp(3)))
                subRow.addView(barTrack, LinearLayout.LayoutParams(dp(40), dp(3)).apply { marginStart = dp(8) })
            }
            col.addView(subRow, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    // ── Compact Spotify overlay on contentFrame ───────────────────────────────

    fun showCompactSpotifyLibrary() { with(activity) {
        if (spotifyCompactOverlay != null) return
        val SpotifyGreen = 0xFF1ED760.toInt()
        val CardBg = 0xFF141720.toInt()

        val overlay = object : LinearLayout(this) {
            private var swipeStartX = 0f; private var swipeStartY = 0f
            override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> { swipeStartX = ev.rawX; swipeStartY = ev.rawY }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = ev.rawX - swipeStartX; val dy = ev.rawY - swipeStartY
                        if (dx < -dp(40) && kotlin.math.abs(dy) < kotlin.math.abs(dx) * 0.65f) return true
                    }
                }
                return false
            }
            override fun onTouchEvent(ev: MotionEvent): Boolean {
                if (ev.action == MotionEvent.ACTION_UP) {
                    val dx = ev.rawX - swipeStartX; val dy = ev.rawY - swipeStartY
                    if (dx < -dp(40) && kotlin.math.abs(dy) < kotlin.math.abs(dx) * 0.65f) {
                        dismissCompactSpotifyLibrary(); return true
                    }
                }
                return true
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
                0xFF0B0D10.toInt(), 0xFF07080A.toInt()
            )).apply { setStroke(dp(1), 0xFF181B20.toInt()) }
            translationX = contentFrame.width.toFloat()
        }

        val tabLabels = listOf("TOP", "RECENT", "PLAYLISTS", "SEARCH")
        val tabViews = mutableListOf<TextView>()
        var activeTab = 0

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(10), dp(4))
        }
        header.addView(ImageView(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(SpotifyGreen) }
        }, LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(6) })
        header.addView(mono("SPOTIFY", 8.5f, SpotifyGreen).apply { letterSpacing = 0.18f },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        tabLabels.forEachIndexed { i, lbl ->
            val tv = mono(lbl, 8.5f, if (i == 0) SpotifyGreen else InkDim).apply {
                letterSpacing = 0.10f; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(if (i == 0) 10 else 14), dp(12), dp(8), dp(12))
                minimumHeight = dp(48); isClickable = true; isFocusable = false
            }
            tabViews.add(tv); header.addView(tv)
        }

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER
            // Let pop-scale overshoot draw past row bounds instead of clipping.
            clipChildren = false; clipToPadding = false
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(4), dp(12), dp(24))
            clipChildren = false; clipToPadding = false
        }
        scroll.addView(body)
        compactLibraryScrollRef = scroll; compactLibraryTargetY = 0
        compactMainScroll = scroll

        val searchField = EditText(this).apply {
            hint = "Search Spotify…"; textSize = 13f; setTextColor(Ink); setHintTextColor(InkDim)
            setSingleLine(); imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            background = GradientDrawable().apply { setColor(0xFF1A1D22.toInt()); cornerRadius = dp(10).toFloat(); setStroke(dp(1), 0xFF2A2E36.toInt()) }
            setPadding(dp(14), dp(9), dp(14), dp(9))
        }
        val searchWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(4), dp(12), dp(6))
            visibility = View.GONE; addView(searchField, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val personalizedKeys = listOf("discover weekly", "daily mix", "release radar", "on repeat", "repeat rewind", "time capsule", "your top songs", "wrapped")
        fun isPersonalized(name: String) = personalizedKeys.any { name.lowercase().contains(it) }

        fun gridRow(vararg views: View) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; weightSum = 3f
            clipChildren = false; clipToPadding = false
            views.forEachIndexed { i, v -> addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = dp(8) }) }
            repeat(3 - views.size) { addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(8) }) }
        }

        fun squareCard(art: android.graphics.Bitmap?, fallback: IntArray, title: String, sub: String, onClick: () -> Unit): View =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; isClickable = true
                background = GradientDrawable().apply { setColor(CardBg); cornerRadius = dp(14).toFloat(); setStroke(dp(1), 0xFF22262E.toInt()) }
                setPadding(dp(8), dp(8), dp(8), dp(9))
                setOnClickListener { haptic(this); popThenRun(this) { onClick() } }
                val frame = object : FrameLayout(activity) { override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, w) }.also { f ->
                    f.clipToOutline = true
                    f.outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(8).toFloat()) } }
                    f.addView(ImageView(activity).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        if (art != null) setImageBitmap(art) else background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, fallback).apply { cornerRadius = dp(8).toFloat() }
                    }, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                }
                addView(frame, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                addView(TextView(context).apply { text = title; textSize = 10.5f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; typeface = Typeface.create("sans-serif", Typeface.BOLD); setTextColor(Ink); setPadding(dp(2), dp(6), dp(2), 0) }, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                addView(TextView(context).apply { text = sub; textSize = 9.5f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setTextColor(InkDim); setPadding(dp(2), dp(2), dp(2), 0) }, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }

        fun populateGrid(items: List<Pair<String, String>>, arts: List<android.graphics.Bitmap?>, fallback: IntArray = intArrayOf(0xFF1A1D22.toInt(), 0xFF0D1014.toInt()), accent: Int = 0, onClick: (Int) -> Unit) {
            items.chunked(3).forEachIndexed { rowIdx, row ->
                val cards = row.mapIndexed { col, (t, s) -> squareCard(arts.getOrNull(rowIdx * 3 + col), fallback, t, s) { onClick(rowIdx * 3 + col) } }
                body.addView(gridRow(*cards.toTypedArray()), LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                body.addView(View(activity), LinearLayout.LayoutParams.MATCH_PARENT, dp(10))
            }
        }

        fun sectionLabel(text: String, color: Int = InkDim) = mono(text, 8.5f, color).apply {
            letterSpacing = 0.18f; setPadding(dp(2), dp(6), 0, dp(8))
        }

        fun selectTab(idx: Int) {
            activeTab = idx
            compactLibraryActiveTab = idx
            tabViews.forEachIndexed { i, tv ->
                tv.setTextColor(if (i == idx) SpotifyGreen else InkDim)
                tv.typeface = if (i == idx) Typeface.create("sans-serif", Typeface.BOLD) else Typeface.DEFAULT
            }
            searchWrap.visibility = if (idx == 3) View.VISIBLE else View.GONE
            body.removeAllViews(); scroll.scrollTo(0, 0); compactLibraryTargetY = 0
            when (idx) {
                0 -> { // TOP
                    val top = spotifyCachedTopTracks
                    if (top.isEmpty()) {
                        body.addView(TextView(this).apply { text = "Reconnect Spotify to load your top tracks"; textSize = 12f; setTextColor(InkDim); setPadding(dp(2), dp(8), 0, 0) })
                    } else {
                        body.addView(sectionLabel("YOUR TOP TRACKS  •  ALL TIME", 0xFF8BE8FF.toInt()))
                        val grid = top.take(9)
                        populateGrid(grid.map { it.name to it.artist }, spotifyCachedTopArts.take(9),
                            intArrayOf(0xFF1A2030.toInt(), 0xFF0A1020.toInt())) { i ->
                            mediaUiScope.launch { spotifyApi.playTrack(grid[i].uri) }
                        }
                        body.addView(sectionLabel("ALL ${top.size} TRACKS"))
                        top.forEachIndexed { i, track ->
                            body.addView(compactTrackRow(i + 1, spotifyCachedTopArts.getOrNull(i), track.name, track.artist, track.popularity) {
                                mediaUiScope.launch { spotifyApi.playTrack(track.uri) }
                            })
                            if (i < top.lastIndex) body.addView(View(activity).apply { setBackgroundColor(0x08FFFFFF) }, LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
                        }
                    }
                }
                1 -> { // RECENT
                    val recent = spotifyCachedRecent
                    if (recent.isEmpty()) {
                        body.addView(TextView(this).apply { text = "Play something in Spotify to see it here"; textSize = 12f; setTextColor(InkDim); setPadding(dp(2), dp(8), 0, 0) })
                    } else {
                        body.addView(sectionLabel("RECENTLY PLAYED"))
                        populateGrid(recent.map { it.name to it.artist }, spotifyCachedRecentArts) { i ->
                            recent.getOrNull(i)?.let { t -> mediaUiScope.launch { spotifyApi.playTrack(t.uri) } }
                        }
                    }
                }
                2 -> { // PLAYLISTS
                    val liked = spotifyCachedLikedSongs
                    if (liked.isNotEmpty()) {
                        body.addView(compactTrackRow(null, null, "Liked Songs", "${liked.size} songs") {
                            showCompactPlaylistDetail(SpotifyPlaylist("liked", "Liked Songs", "", liked.size, null, "liked"))
                        })
                        body.addView(View(activity).apply { setBackgroundColor(0x08FFFFFF) }, LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
                    }
                    val personalized = spotifyCachedPlaylists.indices.filter { isPersonalized(spotifyCachedPlaylists[it].name) }
                    val mine = spotifyCachedPlaylists.indices.filter { !isPersonalized(spotifyCachedPlaylists[it].name) }
                    if (personalized.isNotEmpty()) {
                        body.addView(sectionLabel("MADE FOR YOU", 0xFF8BE8FF.toInt()))
                        populateGrid(personalized.map { spotifyCachedPlaylists[it].name to spotifyCachedPlaylists[it].ownerName.ifBlank { "Spotify" } },
                            personalized.map { spotifyCachedPlaylistArts.getOrNull(it) }, intArrayOf(0xFF1A2830.toInt(), 0xFF0A1018.toInt())
                        ) { i -> personalized.getOrNull(i)?.let { idx2 -> showCompactPlaylistDetail(spotifyCachedPlaylists[idx2]) } }
                    }
                    if (mine.isNotEmpty()) {
                        body.addView(sectionLabel("YOUR PLAYLISTS"))
                        populateGrid(mine.map { spotifyCachedPlaylists[it].name to spotifyCachedPlaylists[it].ownerName.ifBlank { "My playlist" } },
                            mine.map { spotifyCachedPlaylistArts.getOrNull(it) }
                        ) { i -> mine.getOrNull(i)?.let { idx2 -> showCompactPlaylistDetail(spotifyCachedPlaylists[idx2]) } }
                    }
                }
                3 -> { // SEARCH
                    searchField.requestFocus()
                    searchField.postDelayed({
                        val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                        imm?.showSoftInput(searchField, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                    }, 120)
                }
            }
        }

        tabViews.forEachIndexed { i, tv -> tv.setOnClickListener { selectTab(i) } }
        compactLibrarySelectTab = { idx -> selectTab(idx) }

        var searchJob: Job? = null
        searchField.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s?.toString()?.trim() ?: ""
                searchJob?.cancel(); body.removeAllViews()
                if (q.length < 2) return
                searchJob = mediaUiScope.launch {
                    delay(280)
                    val tracks = withContext(Dispatchers.IO) { spotifyApi.search(q, limit = 20) }
                    val arts = withContext(Dispatchers.IO) { coroutineScope { tracks.map { t -> async { t.albumArtUrl?.let { url -> runCatching { spotifyApi.fetchAlbumArt(url) }.getOrNull() } } }.awaitAll() } }
                    body.removeAllViews()
                    if (tracks.isEmpty()) {
                        body.addView(TextView(activity).apply { text = "No results for \"$q\""; textSize = 12f; setTextColor(InkDim); setPadding(dp(4), dp(10), 0, 0) })
                    } else {
                        tracks.forEachIndexed { i, track ->
                            body.addView(compactTrackRow(null, arts.getOrNull(i), track.name, track.artist, track.popularity) {
                                mediaUiScope.launch { spotifyApi.playTrack(track.uri) }
                            })
                            if (i < tracks.lastIndex) body.addView(View(activity).apply { setBackgroundColor(0x08FFFFFF) }, LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
                        }
                    }
                }
            }
        })

        selectTab(0)

        overlay.addView(header, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        overlay.addView(View(this).apply { setBackgroundColor(0x10FFFFFF) }, LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
        overlay.addView(searchWrap, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        overlay.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        spotifyCompactOverlay = overlay
        contentFrame.addView(overlay, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

        overlay.animate().translationX(0f).setDuration(380)
            .setInterpolator(android.view.animation.DecelerateInterpolator(2.0f)).start()
    } }

    fun dismissCompactSpotifyLibrary() { with(activity) {
        val overlay = spotifyCompactOverlay ?: return
        spotifyCompactOverlay = null
        compactLibraryScrollRef = null; compactLibraryTargetY = 0; compactOverscroll = 0f
        compactSelectedView = null
        compactLibrarySelectTab = null; compactLibraryActiveTab = 0
        compactDetailView = null; compactMainScroll = null
        val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(overlay.windowToken, 0)
        overlay.animate().translationX(contentFrame.width.toFloat()).setDuration(280)
            .setInterpolator(android.view.animation.AccelerateInterpolator(1.8f))
            .withEndAction { contentFrame.removeView(overlay) }.start()
    } }

    fun showSpotifySearchOverlay() { with(activity) {
        if (!spotifyAuth.isConnected) return
        // If compact library isn't open yet, open it first then add search on top
        if (spotifyCompactOverlay == null) showCompactSpotifyLibrary()

        val parent = spotifyCompactOverlay ?: return

        // Check if search overlay already exists
        if (parent.findViewWithTag<View>("search_overlay") != null) return

        val searchOverlay = LinearLayout(this).apply {
            tag = "search_overlay"
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xF5101215.toInt())
            translationY = -parent.height.toFloat().coerceAtLeast(dp(400).toFloat())
        }

        val searchField = EditText(this).apply {
            hint = "Search Spotify…"
            textSize = 15f
            setTextColor(0xFFF3F0E7.toInt())
            setHintTextColor(0xFF6B7280.toInt())
            setSingleLine()
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            background = GradientDrawable().apply {
                setColor(0xFF1A1D22.toInt())
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), 0xFF2E333B.toInt())
            }
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }

        val resultsScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
        }
        val resultsList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(16))
        }
        resultsScroll.addView(resultsList, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        val dismissBtn = TextView(this).apply {
            text = "✕"
            textSize = 16f
            setTextColor(0xFF8B8F99.toInt())
            setPadding(dp(8), dp(4), dp(4), dp(4))
            setOnClickListener {
                haptic(this)
                searchOverlay.animate().translationY(-searchOverlay.height.toFloat())
                    .setDuration(260).withEndAction { (parent as? android.view.ViewGroup)?.removeView(searchOverlay) }.start()
                // Hide soft keyboard
                val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                imm?.hideSoftInputFromWindow(searchField.windowToken, 0)
            }
        }
        headerRow.addView(searchField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        headerRow.addView(dismissBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        searchOverlay.addView(headerRow, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        searchOverlay.addView(resultsScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        (parent as? android.view.ViewGroup)?.addView(searchOverlay,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // Register as scroll ref so click wheel navigates results
        compactLibraryScrollRef = resultsScroll; compactLibraryTargetY = 0

        // Animate in from top
        searchOverlay.post {
            searchOverlay.translationY = -searchOverlay.height.toFloat()
            searchOverlay.animate().translationY(0f).setDuration(300)
                .setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
        }

        // Auto-focus and show keyboard
        searchField.requestFocus()
        searchField.postDelayed({
            val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            imm?.showSoftInput(searchField, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }, 180)

        fun buildResultRow(track: SpotifyTrack, art: android.graphics.Bitmap?, idx: Int): View =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(52)
                setPadding(dp(14), dp(6), dp(14), dp(6))
                setBackgroundColor(if (idx % 2 == 0) 0x00000000 else 0x08FFFFFF)
                setOnClickListener {
                    haptic(this)
                    mediaUiScope.launch { spotifyApi.playTrack(track.uri) }
                }
                val thumb = ImageView(activity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    if (art != null) setImageBitmap(art)
                    else setBackgroundColor(0xFF1A1D22.toInt())
                    outlineProvider = object : android.view.ViewOutlineProvider() {
                        override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(4).toFloat()) }
                    }
                    clipToOutline = true
                }
                addView(thumb, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) })
                val col = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                col.addView(TextView(activity).apply {
                    text = track.name; textSize = 13f; maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(0xFFF3F0E7.toInt())
                    typeface = Typeface.create("sans-serif", Typeface.BOLD)
                })
                col.addView(TextView(activity).apply {
                    text = track.artist; textSize = 11f; maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(0xFF8B8F99.toInt())
                })
                addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }

        var searchJob: Job? = null
        searchField.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s?.toString()?.trim() ?: ""
                searchJob?.cancel()
                resultsList.removeAllViews()
                if (q.length < 2) return
                searchJob = mediaUiScope.launch {
                    delay(260)
                    val tracks = spotifyApi.search(q, limit = 20)
                    val arts = tracks.map { t ->
                        t.albumArtUrl?.let { url -> runCatching { spotifyApi.fetchAlbumArt(url) }.getOrNull() }
                    }
                    withContext(Dispatchers.Main) {
                        resultsList.removeAllViews()
                        if (tracks.isEmpty()) {
                            resultsList.addView(TextView(activity).apply {
                                text = "No results for \"$q\""; textSize = 12f
                                setTextColor(0xFF6B7280.toInt()); setPadding(dp(14), dp(12), 0, 0)
                            })
                        } else {
                            tracks.forEachIndexed { i, t -> resultsList.addView(buildResultRow(t, arts.getOrNull(i), i)) }
                        }
                    }
                }
            }
        })
    } }

    // ── Music dock: library pager + click wheel ──────────────────────────────

    // ── iPod-style wheel selection over the compact library ──────────────────
    // Rotation moves a highlighted row/card instead of scrolling the page; the
    // center button activates it. Selectable items are discovered by walking
    // the active scroll container, so tab switches, playlist drill-down, and
    // search results all work without registration bookkeeping.
    private var compactSelectedView: View? = null
    // Bridge to the overlay's local selectTab() so the wheel's press-and-hold
    // ‹‹/›› can page through TOP / RECENT / PLAYLISTS / SEARCH.
    private var compactLibrarySelectTab: ((Int) -> Unit)? = null
    private var compactLibraryActiveTab = 0
    // Playlist/folder drill-down state: LIBRARY acts as back while one is open,
    // and the wheel's scroll target is restored to the main list on close.
    private var compactDetailView: View? = null
    private var compactMainScroll: ScrollView? = null

    private fun closeCompactPlaylistDetail(): Boolean { with(activity) {
        val detail = compactDetailView ?: return false
        compactDetailView = null
        val slideOut = (spotifyCompactOverlay?.width ?: detail.width).toFloat()
        detail.animate().translationX(slideOut).setDuration(240)
            .setInterpolator(android.view.animation.AccelerateInterpolator(1.8f))
            .withEndAction { (detail.parent as? ViewGroup)?.removeView(detail) }.start()
        compactLibraryScrollRef = compactMainScroll
        compactLibraryTargetY = compactMainScroll?.scrollY ?: 0
        compactSelectedView = null
        compactOverscroll = 0f
        return true
    } }

    private fun compactLibraryTabStep(delta: Int): Boolean { with(activity) {
        val select = compactLibrarySelectTab ?: return false
        val next = (compactLibraryActiveTab + delta).coerceIn(0, 3)
        if (next == compactLibraryActiveTab) return false
        select(next)
        return true
    } }

    private fun compactSelectableItems(): List<View> { with(activity) {
        val root = compactLibraryScrollRef?.getChildAt(0) as? ViewGroup ?: return emptyList()
        val out = mutableListOf<View>()
        fun walk(vg: ViewGroup) {
            for (i in 0 until vg.childCount) {
                val c = vg.getChildAt(i)
                if (c.visibility != View.VISIBLE) continue
                if (c.isClickable) out.add(c) else if (c is ViewGroup) walk(c)
            }
        }
        walk(root)
        return out
    } }

    private fun compactItemTop(v: View): Int { with(activity) {
        val content = compactLibraryScrollRef?.getChildAt(0) ?: return 0
        var y = 0
        var cur: View = v
        while (cur !== content) {
            y += cur.top
            cur = cur.parent as? View ?: break
        }
        return y
    } }

    private fun firstVisibleCompactIndex(sv: ScrollView, items: List<View>): Int { with(activity) {
        items.forEachIndexed { i, v ->
            if (compactItemTop(v) + v.height > sv.scrollY + dp(4)) return i
        }
        return 0
    } }

    private fun setCompactSelection(items: List<View>, idx: Int) { with(activity) {
        val v = items.getOrNull(idx) ?: return
        if (compactSelectedView !== v) {
            compactSelectedView?.foreground = null
            compactSelectedView = v
            v.foreground = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setStroke(dp(2), 0xFF1ED760.toInt())
                setColor(0x141ED760)
            }
        }
        scrollCompactSelectionIntoView(v)
    } }

    private fun scrollCompactSelectionIntoView(v: View) { with(activity) {
        val sv = compactLibraryScrollRef ?: return
        val content = sv.getChildAt(0) ?: return
        val top = compactItemTop(v)
        val bottom = top + v.height
        val margin = dp(10)
        val cur = sv.scrollY
        var next = cur
        if (top < cur + margin) next = top - margin
        else if (bottom > cur + sv.height - margin) next = bottom - sv.height + margin
        val maxY = (content.height - sv.height).coerceAtLeast(0)
        next = next.coerceIn(0, maxY)
        compactLibraryTargetY = next
        if (next != cur) sv.smoothScrollTo(0, next)
    } }

    // Elastic edge stretch: wheel ticks past the list edge translate the library
    // content with diminishing resistance, then spring back on release.
    private var compactOverscroll = 0f

    private fun applyCompactOverscroll(sv: ScrollView, overflowPx: Int) { with(activity) {
        val child = sv.getChildAt(0) ?: return
        val max = dp(48).toFloat()
        child.animate().cancel()
        val resistance = 1f - (kotlin.math.abs(compactOverscroll) / max).coerceIn(0f, 1f)
        compactOverscroll = (compactOverscroll - overflowPx * 0.45f * resistance).coerceIn(-max, max)
        child.translationY = compactOverscroll
    } }

    private fun releaseCompactOverscroll() { with(activity) {
        if (compactOverscroll == 0f) return
        compactOverscroll = 0f
        val child = compactLibraryScrollRef?.getChildAt(0) ?: return
        child.animate().translationY(0f).setDuration(320)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.2f))
            .start()
    } }

    fun musicDockView(): View { with(activity) {
        val wheelBg = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            0xFF101216.toInt(), 0xFF050506.toInt(), 0xFF000000.toInt()
        )).apply { setStroke(dp(1), 0xFF20232A.toInt()) }
        return FrameLayout(this).apply {
            background = wheelBg
            val wheelSize = clickWheelSize()
            addView(WheelWellView(context), FrameLayout.LayoutParams(wheelSize + dp(18), wheelSize + dp(18), Gravity.CENTER))
            addView(ClickWheelView(context).apply {
                sourceLabel = if (spotifyAuth.isConnected) "LIBRARY" else "SOURCE"
                onLibrary = if (spotifyAuth.isConnected) ({
                    haptic(this)
                    if (spotifyCompactOverlay != null) {
                        // Inside a playlist/folder → LIBRARY steps back first;
                        // at the top level it dismisses the overlay.
                        if (!closeCompactPlaylistDetail()) dismissCompactSpotifyLibrary()
                    } else {
                        // Single tap → compact library
                        showCompactSpotifyLibrary()
                    }
                }) else null
                // Press-and-hold LIBRARY → full-screen library.
                onLongLibrary = if (spotifyAuth.isConnected) ({ showSpotifyFullLibrary() }) else null
                // Press-and-hold OK → search.
                onLongCenter = if (spotifyAuth.isConnected) ({ showSpotifySearchOverlay() }) else null
                onCenter = {
                    haptic(this)
                    val sel = compactSelectedView
                    if (compactLibraryScrollRef != null && sel != null && sel.isAttachedToWindow) sel.performClick()
                    else mediaSessionSource.togglePlayPause()
                }
                onLeft = { haptic(this); mediaSessionSource.skipToPrevious() }
                onRight = { haptic(this); mediaSessionSource.skipToNext() }
                onBottom = { haptic(this); mediaSessionSource.togglePlayPause() }
                // Press-and-hold ‹‹/›› pages through library tabs — only while
                // the compact library is open; elsewhere a hold stays a skip.
                onLongLeft = {
                    if (spotifyCompactOverlay != null) { compactLibraryTabStep(-1); true } else false
                }
                onLongRight = {
                    if (spotifyCompactOverlay != null) { compactLibraryTabStep(1); true } else false
                }
                // Flywheel glide only makes sense on the library list — a seek
                // that keeps drifting after finger-up would feel broken.
                flingAllowed = { compactLibraryScrollRef != null }
                onScrollEnd = { releaseCompactOverscroll() }
                onScroll = { steps ->
                    val sv = compactLibraryScrollRef
                    if (sv != null) {
                        val items = compactSelectableItems()
                        if (items.isNotEmpty()) {
                            val curIdx = items.indexOf(compactSelectedView)
                            // No selection yet → start from the first visible item.
                            val target = if (curIdx >= 0) curIdx + steps
                                         else firstVisibleCompactIndex(sv, items) + if (steps > 0) steps - 1 else steps
                            val clamped = target.coerceIn(0, items.lastIndex)
                            if (target != clamped) {
                                // Pushed past the list edge: stretch, and kill any
                                // glide now so the spring-back doesn't wait for
                                // leftover momentum to decay.
                                applyCompactOverscroll(sv, (target - clamped) * dp(56))
                                cancelFling()
                            } else if (compactOverscroll != 0f) {
                                releaseCompactOverscroll()
                            }
                            setCompactSelection(items, clamped)
                        }
                    } else {
                        val info = mediaSessionSource.nowPlaying.value
                        if (info != null && info.durationMs > 0) {
                            val elapsed = android.os.SystemClock.elapsedRealtime() - info.lastUpdateElapsedMs
                            val pos = if (info.isPlaying) (info.positionMs + elapsed).coerceAtMost(info.durationMs) else info.positionMs
                            val seekMs = (pos + steps * 8_000L).coerceIn(0L, info.durationMs)
                            mediaSessionSource.seekTo(seekMs)
                        }
                    }
                }
            }, FrameLayout.LayoutParams(wheelSize, wheelSize, Gravity.CENTER))
        }
    } }

    // Two-page swipe container: smooth paging with velocity snapping.
    inner class DockPageSwiper(context: Context) : ViewGroup(context) {
        private val scroller = Scroller(context, DecelerateInterpolator())
        private val velocity = VelocityTracker.obtain()
        private var downX = 0f
        private var downY = 0f
        private var dragging = false
        private var currentPage = 0

        fun addPage(v: View) { addView(v) }

        fun goToPage(page: Int, animate: Boolean = true) {
            currentPage = page.coerceIn(0, childCount - 1)
            val target = currentPage * width
            if (animate) {
                scroller.startScroll(scrollX, 0, target - scrollX, 0, 260)
                invalidate()
            } else {
                scroller.abortAnimation()
                scrollTo(target, 0)
            }
        }

        override fun computeScroll() {
            if (scroller.computeScrollOffset()) {
                scrollTo(scroller.currX, 0)
                invalidate()
            }
        }

        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            setMeasuredDimension(widthSpec, heightSpec)
            val pw = MeasureSpec.getSize(widthSpec)
            val ph = MeasureSpec.getSize(heightSpec)
            for (i in 0 until childCount) {
                getChildAt(i).measure(
                    MeasureSpec.makeMeasureSpec(pw, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(ph, MeasureSpec.EXACTLY)
                )
            }
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val pw = r - l
            for (i in 0 until childCount) {
                getChildAt(i).layout(i * pw, 0, (i + 1) * pw, b - t)
            }
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y; dragging = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = kotlin.math.abs(ev.x - downX)
                    val dy = kotlin.math.abs(ev.y - downY)
                    if (!dragging && dx > activity.dp(8) && dx > dy * 1.3f) {
                        dragging = true
                        if (!scroller.isFinished) scroller.abortAnimation()
                        return true
                    }
                }
            }
            return false
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            velocity.addMovement(ev)
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (!scroller.isFinished) scroller.abortAnimation()
                    downX = ev.x
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val raw = (downX - ev.x + currentPage * width).toInt()
                    scrollTo(raw.coerceIn(0, (childCount - 1) * width), 0)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    velocity.computeCurrentVelocity(1000)
                    val vx = velocity.xVelocity
                    val page = when {
                        vx < -600 -> currentPage + 1
                        vx > 600 -> currentPage - 1
                        scrollX > currentPage * width + width / 2 -> currentPage + 1
                        scrollX < currentPage * width - width / 2 -> currentPage - 1
                        else -> currentPage
                    }
                    goToPage(page)
                    velocity.clear()
                    dragging = false
                }
            }
            return true
        }
    }

    // Spotify library page: header + horizontal scrolling track cards.
    private fun spotifyLibraryPage(onSwipeToWheel: () -> Unit): View { with(activity) {
        val SpotifyGreen = 0xFF1ED760.toInt()
        val CardBg = 0xFF141720.toInt()
        val CardStroke = 0xFF22262E.toInt()

        val container = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
                0xFF0B0D10.toInt(), 0xFF07080A.toInt(), 0xFF030304.toInt()
            )).apply { setStroke(dp(1), 0xFF181B20.toInt()) }
        }

        // ── Scrollable body (behind header) ──────────────────────────────────
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(12))
        }
        scroll.addView(body)

        // ── Header row: ● SPOTIFY  RECENT · PLAYLISTS · SEARCH  WHEEL › ─────
        val tabLabels = listOf("RECENT", "PLAYLISTS", "SEARCH")
        val tabViews = mutableListOf<TextView>()
        var activeTab = 0

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(9), dp(12), dp(8))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFF0B0D10.toInt(), 0x000B0D10)).apply { }
        }

        // Spotify dot + label (tap to expand full library)
        header.addView(ImageView(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(SpotifyGreen) }
        }, LinearLayout.LayoutParams(dp(6), dp(6)).apply { marginEnd = dp(5) })
        header.addView(mono("SPOTIFY  ↑", 8.5f, SpotifyGreen).apply {
            letterSpacing = 0.18f
            isClickable = true
            setOnClickListener {
                haptic(this)
                showSpotifyFullLibrary()
            }
        })

        // Spacer
        header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))

        // Tab links
        tabLabels.forEachIndexed { i, label ->
            val tv = mono(label, 8.5f, if (i == 0) SpotifyGreen else InkDim).apply {
                letterSpacing = 0.10f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(8), dp(12))
                minimumHeight = dp(48)
                isClickable = true; isFocusable = false
            }
            tabViews.add(tv)
            header.addView(tv)
        }

        // Wheel link
        header.addView(mono("WHEEL ›", 8.5f, InkDim).apply {
            letterSpacing = 0.08f
            setPadding(dp(10), dp(3), 0, dp(3))
            isClickable = true
            setOnClickListener { onSwipeToWheel() }
        })

        // Search field (hidden until SEARCH tab active)
        val searchField = EditText(this).apply {
            hint = "Search Spotify…"
            textSize = 12f
            setTextColor(Ink)
            setHintTextColor(InkDim)
            setSingleLine()
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            background = GradientDrawable().apply {
                setColor(0xFF1A1D22.toInt()); cornerRadius = dp(10).toFloat()
                setStroke(dp(1), 0xFF2A2E36.toInt())
            }
            setPadding(dp(12), dp(7), dp(12), dp(7))
            visibility = View.GONE
        }
        val searchWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(12), dp(6))
            visibility = View.GONE
            addView(searchField, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        outer.addView(header, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        outer.addView(View(this).apply { setBackgroundColor(0x10FFFFFF) }, LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
        outer.addView(searchWrap, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        outer.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        container.addView(outer, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

        // ── Card helpers ──────────────────────────────────────────────────────
        fun gridRow(vararg views: View) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; weightSum = 2f
            views.forEachIndexed { i, v ->
                addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = dp(10) })
            }
            if (views.size == 1) addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(10) })
        }

        fun squareCard(art: android.graphics.Bitmap?, fallback: IntArray, title: String, sub: String, stroke: Int = CardStroke, onClick: () -> Unit): View =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; isClickable = true
                background = GradientDrawable().apply { setColor(CardBg); cornerRadius = dp(14).toFloat(); setStroke(dp(1), stroke) }
                setPadding(dp(8), dp(8), dp(8), dp(9))
                setOnClickListener { haptic(this); onClick() }
                val frame = object : FrameLayout(activity) { override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, w) }.also { f ->
                    f.clipToOutline = true
                    f.outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(8).toFloat()) } }
                    f.addView(ImageView(activity).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        if (art != null) setImageBitmap(art) else background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, fallback).apply { cornerRadius = dp(8).toFloat() }
                    }, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                }
                addView(frame, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                addView(TextView(context).apply { text = title; textSize = 11f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; typeface = Typeface.create("sans-serif", Typeface.BOLD); setTextColor(Ink); setPadding(dp(2), dp(7), dp(2), 0) }, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                addView(TextView(context).apply { text = sub; textSize = 9.5f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setTextColor(InkDim); setPadding(dp(2), dp(2), dp(2), 0) }, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }

        fun populateGrid(items: List<Pair<String, String>>, arts: List<android.graphics.Bitmap?>, fallback: IntArray = intArrayOf(0xFF1A1D22.toInt(), 0xFF0D1014.toInt()), onClick: (Int) -> Unit) {
            items.chunked(2).forEachIndexed { rowIdx, row ->
                val cards = row.mapIndexed { col, (t, s) -> squareCard(arts.getOrNull(rowIdx * 2 + col), fallback, t, s) { onClick(rowIdx * 2 + col) } }
                body.addView(gridRow(*cards.toTypedArray()), LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                body.addView(View(activity), LinearLayout.LayoutParams.MATCH_PARENT, dp(10))
            }
        }


        val personalizedKeys = listOf("discover weekly", "daily mix", "release radar", "on repeat", "repeat rewind", "time capsule", "your top songs", "wrapped")
        fun isPersonalized(name: String) = personalizedKeys.any { name.lowercase().contains(it) }

        // ── Tab switching ─────────────────────────────────────────────────────
        var searchJob: Job? = null

        fun selectTab(idx: Int) {
            activeTab = idx
            tabViews.forEachIndexed { i, tv ->
                tv.setTextColor(if (i == idx) SpotifyGreen else InkDim)
                tv.typeface = if (i == idx) Typeface.create("sans-serif", Typeface.BOLD) else Typeface.DEFAULT
            }
            searchWrap.visibility = if (idx == 2) View.VISIBLE else View.GONE
            body.removeAllViews()
            searchJob?.cancel()

            when (idx) {
                0 -> { // RECENT
                    if (spotifyCachedRecent.isEmpty()) {
                        body.addView(TextView(activity).apply { text = "Loading…"; textSize = 11f; setTextColor(InkDim); setPadding(dp(2), dp(8), 0, 0) })
                    } else {
                        populateGrid(spotifyCachedRecent.map { it.name to it.artist }, spotifyCachedRecentArts) { i ->
                            spotifyCachedRecent.getOrNull(i)?.let { t -> mediaUiScope.launch { spotifyApi.playTrack(t.uri) } }
                        }
                    }
                }
                1 -> { // PLAYLISTS
                    if (spotifyCachedPlaylists.isEmpty()) {
                        body.addView(TextView(activity).apply { text = "Loading…"; textSize = 11f; setTextColor(InkDim); setPadding(dp(2), dp(8), 0, 0) })
                    } else {
                        val personalized = spotifyCachedPlaylists.indices.filter { isPersonalized(spotifyCachedPlaylists[it].name) }
                        val mine = spotifyCachedPlaylists.indices.filter { !isPersonalized(spotifyCachedPlaylists[it].name) }
                        if (personalized.isNotEmpty()) {
                            body.addView(mono("MADE FOR YOU", 8.5f, 0xFF8BE8FF.toInt()).apply { letterSpacing = 0.18f; setPadding(dp(2), dp(6), 0, dp(6)) })
                            populateGrid(
                                personalized.map { spotifyCachedPlaylists[it].name to "${spotifyCachedPlaylists[it].trackCount} tracks" },
                                personalized.map { spotifyCachedPlaylistArts.getOrNull(it) },
                                intArrayOf(0xFF1A2830.toInt(), 0xFF0A1018.toInt())
                            ) { i -> personalized.getOrNull(i)?.let { idx2 -> mediaUiScope.launch { spotifyApi.playContext(spotifyCachedPlaylists[idx2].uri) } } }
                        }
                        if (mine.isNotEmpty()) {
                            body.addView(mono("YOUR PLAYLISTS", 8.5f, InkDim).apply { letterSpacing = 0.18f; setPadding(dp(2), if (personalized.isNotEmpty()) dp(4) else dp(6), 0, dp(6)) })
                            populateGrid(
                                mine.map { spotifyCachedPlaylists[it].name to "${spotifyCachedPlaylists[it].trackCount} tracks" },
                                mine.map { spotifyCachedPlaylistArts.getOrNull(it) }
                            ) { i -> mine.getOrNull(i)?.let { idx2 -> mediaUiScope.launch { spotifyApi.playContext(spotifyCachedPlaylists[idx2].uri) } } }
                        }
                        if (spotifyCachedPlaylists.isEmpty()) {
                            body.addView(TextView(activity).apply { text = "No playlists found — reconnect Spotify"; textSize = 11f; setTextColor(InkDim); setPadding(dp(2), dp(8), 0, 0) })
                        }
                    }
                }
                2 -> { // SEARCH
                    searchField.requestFocus()
                    searchField.postDelayed({
                        val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                        imm?.showSoftInput(searchField, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                    }, 120)
                }
            }
        }

        tabViews.forEachIndexed { i, tv -> tv.setOnClickListener { selectTab(i) } }

        // Search watcher
        searchField.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s?.toString()?.trim() ?: ""
                searchJob?.cancel()
                body.removeAllViews()
                if (q.length < 2) return
                searchJob = mediaUiScope.launch {
                    delay(320)
                    val tracks = withContext(Dispatchers.IO) { spotifyApi.search(q, limit = 10) }
                    val arts = withContext(Dispatchers.IO) { coroutineScope { tracks.map { t -> async { t.albumArtUrl?.let { url -> runCatching { spotifyApi.fetchAlbumArt(url) }.getOrNull() } } }.awaitAll() } }
                    if (tracks.isEmpty()) {
                        body.addView(TextView(activity).apply { text = "No results"; textSize = 11f; setTextColor(InkDim); setPadding(dp(2), dp(8), 0, 0) })
                    } else {
                        populateGrid(tracks.map { it.name to it.artist }, arts) { i ->
                            tracks.getOrNull(i)?.let { t -> mediaUiScope.launch { spotifyApi.playTrack(t.uri) } }
                        }
                    }
                }
            }
        })

        // ── Fetch data ────────────────────────────────────────────────────────
        mediaUiScope.launch(Dispatchers.IO) {
            val recentDeferred = async { spotifyApi.getRecentlyPlayed(limit = 10) }
            val playlistsDeferred = async { spotifyApi.getPlaylists(limit = 50) }
            val recentTracks = recentDeferred.await()
            val playlists = playlistsDeferred.await()
            coroutineScope {
                val recentArts = recentTracks.map { t -> async { t.albumArtUrl?.let { url -> runCatching { spotifyApi.fetchAlbumArt(url) }.getOrNull() } } }.awaitAll()
                val playlistArts = playlists.map { p -> async { p.imageUrl?.let { url -> runCatching { spotifyApi.fetchAlbumArt(url) }.getOrNull() } } }.awaitAll()
                spotifyCachedRecent = recentTracks
                spotifyCachedRecentArts = recentArts
                spotifyCachedPlaylists = playlists
                spotifyCachedPlaylistArts = playlistArts
                launch(Dispatchers.Main) { selectTab(activeTab) }
            }
        }

        // Show loading state immediately
        body.addView(TextView(this).apply { text = "Loading…"; textSize = 11f; setTextColor(InkDim); setPadding(dp(2), dp(8), 0, 0) })

        return container
    } }

    private fun showSpotifyFullLibrary() { with(activity) {
        if (!requirePro(ProFeature.SPOTIFY_LIBRARY)) return
        val SpotifyGreen = 0xFF1ED760.toInt()
        val CardBg = 0xFF141720.toInt()
        val screenH = resources.displayMetrics.heightPixels
        val statusBarH = run {
            val id = resources.getIdentifier("status_bar_height", "dimen", "android")
            if (id > 0) resources.getDimensionPixelSize(id) else 0
        }

        // ── Scrim + panel ─────────────────────────────────────────────────────
        val scrim = FrameLayout(this).apply { setBackgroundColor(0x00000000); isClickable = true }

        var panelSwipeStartX = 0f; var panelSwipeStartY = 0f
        var panelSwipeConsumed = false
        var panelDismiss: (() -> Unit)? = null
        var panelSelectTab: ((Int) -> Unit)? = null
        var panelActiveTab: (() -> Int)? = null
        val panel = object : LinearLayout(this) {
            override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> {
                        panelSwipeStartX = ev.rawX; panelSwipeStartY = ev.rawY; panelSwipeConsumed = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = ev.rawX - panelSwipeStartX; val dy = ev.rawY - panelSwipeStartY
                        val adx = kotlin.math.abs(dx); val ady = kotlin.math.abs(dy)
                        // Intercept horizontal swipes (tab change) or long downward swipes (dismiss)
                        if (!panelSwipeConsumed && adx > dp(28) && adx > ady * 1.2f) return true
                        if (!panelSwipeConsumed && dy > dp(120) && ady > adx * 1.4f) return true
                    }
                }
                return false
            }
            override fun onTouchEvent(ev: MotionEvent): Boolean {
                if (ev.action == MotionEvent.ACTION_UP && !panelSwipeConsumed) {
                    val dx = ev.rawX - panelSwipeStartX; val dy = ev.rawY - panelSwipeStartY
                    val adx = kotlin.math.abs(dx); val ady = kotlin.math.abs(dy)
                    when {
                        // Long swipe down → dismiss
                        dy > dp(120) && ady > adx * 1.4f -> { panelSwipeConsumed = true; panelDismiss?.invoke() }
                        // Swipe right → next tab
                        dx > dp(28) && adx > ady * 1.2f -> {
                            panelSwipeConsumed = true
                            val cur = panelActiveTab?.invoke() ?: 0
                            val next = (cur + 1).coerceAtMost(3)
                            if (next != cur) { haptic(this); panelSelectTab?.invoke(next) }
                        }
                        // Swipe left → previous tab
                        dx < -dp(28) && adx > ady * 1.2f -> {
                            panelSwipeConsumed = true
                            val cur = panelActiveTab?.invoke() ?: 0
                            val prev = (cur - 1).coerceAtLeast(0)
                            if (prev != cur) { haptic(this); panelSelectTab?.invoke(prev) }
                        }
                    }
                }
                return true
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFF0F1215.toInt(), 0xFF090B0D.toInt()))
                .apply { setStroke(dp(1), 0xFF1E2228.toInt()) }
            setPadding(0, statusBarH, 0, 0)
            translationY = screenH.toFloat()
            isClickable = true; isFocusable = true
        }

        val decorView = window.decorView as FrameLayout
        scrim.addView(panel, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        decorView.addView(scrim, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

        fun dismiss() {
            spotifyFullLibraryDismiss = null
            panel.animate().translationY(screenH.toFloat()).setDuration(300)
                .setInterpolator(android.view.animation.AccelerateInterpolator(1.8f))
                .withEndAction { decorView.removeView(scrim) }.start()
            scrim.animate().alpha(0f).setDuration(240).start()
        }
        panelDismiss = ::dismiss
        spotifyFullLibraryDismiss = ::dismiss

        // ── Header row: tabs + dismiss pill ──────────────────────────────────
        val tabLabels = listOf("TOP", "RECENT", "PLAYLISTS", "SEARCH")
        val tabViews = mutableListOf<TextView>()
        var activeTab = 0

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(12), dp(4))
        }
        tabLabels.forEachIndexed { i, lbl ->
            val tv = mono(lbl, 9.5f, if (i == 0) SpotifyGreen else InkDim).apply {
                letterSpacing = 0.12f
                gravity = Gravity.CENTER_VERTICAL
                typeface = if (i == 0) Typeface.create("sans-serif", Typeface.BOLD) else Typeface.DEFAULT
                // Large touch target — 48dp min height, generous horizontal padding
                setPadding(dp(if (i == 0) 4 else 18), dp(14), dp(12), dp(14))
                minimumHeight = dp(48)
                isClickable = true; isFocusable = false
            }
            tabViews.add(tv); headerRow.addView(tv)
        }
        headerRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        headerRow.addView(TextView(this).apply {
            text = "↓"; textSize = 16f; setTextColor(InkDim); gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(0xFF1A1D22.toInt()); cornerRadius = dp(20).toFloat(); setStroke(dp(1), 0xFF2A2E38.toInt()) }
            setPadding(dp(14), dp(5), dp(14), dp(7)); isClickable = true
            setOnClickListener { haptic(this); dismiss() }
        })

        val searchField = EditText(this).apply {
            hint = "Search Spotify…"; textSize = 14f; setTextColor(Ink); setHintTextColor(InkDim)
            setSingleLine(); imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            background = GradientDrawable().apply { setColor(0xFF1A1D22.toInt()); cornerRadius = dp(12).toFloat(); setStroke(dp(1), 0xFF2A2E36.toInt()) }
            setPadding(dp(16), dp(10), dp(16), dp(10)); visibility = View.GONE
        }
        val searchWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), dp(8))
            visibility = View.GONE
            addView(searchField, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(4), dp(16), dp(32)) }
        scroll.addView(body)

        panel.addView(headerRow, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        panel.addView(View(this).apply { setBackgroundColor(0x12FFFFFF) }, LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
        panel.addView(searchWrap, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        panel.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // ── Card / row helpers ────────────────────────────────────────────────
        fun gridRow(vararg views: View) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; weightSum = 3f
            views.forEachIndexed { i, v -> addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = dp(10) }) }
            repeat(3 - views.size) { addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(10) }) }
        }

        fun squareCard(art: android.graphics.Bitmap?, fallback: IntArray, title: String, sub: String, stroke: Int = 0xFF22262E.toInt(), onClick: () -> Unit): View =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; isClickable = true
                background = GradientDrawable().apply { setColor(CardBg); cornerRadius = dp(16).toFloat(); setStroke(dp(1), stroke) }
                setPadding(dp(10), dp(10), dp(10), dp(11))
                setOnClickListener { haptic(this); onClick() }
                val frame = object : FrameLayout(activity) { override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, w) }.apply {
                    clipToOutline = true
                    outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(10).toFloat()) } }
                    addView(ImageView(activity).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        if (art != null) setImageBitmap(art) else background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, fallback).apply { cornerRadius = dp(10).toFloat() }
                    }, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                }
                addView(frame, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                addView(TextView(context).apply { text = title; textSize = 11.5f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; typeface = Typeface.create("sans-serif", Typeface.BOLD); setTextColor(Ink); setPadding(dp(2), dp(8), dp(2), 0) }, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                addView(TextView(context).apply { text = sub; textSize = 10f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setTextColor(InkDim); setPadding(dp(2), dp(3), dp(2), 0) }, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }

        fun populateGrid(items: List<Pair<String, String>>, arts: List<android.graphics.Bitmap?>, fallback: IntArray = intArrayOf(0xFF1A1D22.toInt(), 0xFF0D1014.toInt()), stroke: Int = 0xFF22262E.toInt(), onClick: (Int) -> Unit) {
            items.chunked(3).forEachIndexed { rowIdx, row ->
                val cards = row.mapIndexed { col, (t, s) -> squareCard(arts.getOrNull(rowIdx * 3 + col), fallback, t, s, stroke) { onClick(rowIdx * 3 + col) } }
                body.addView(gridRow(*cards.toTypedArray()), LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                body.addView(View(activity), LinearLayout.LayoutParams.MATCH_PARENT, dp(12))
            }
        }

        fun trackRow(rank: Int?, art: android.graphics.Bitmap?, title: String, sub: String, badge: String = "", onClick: () -> Unit) =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(8), dp(16), dp(8)); isClickable = true
                setOnClickListener { haptic(this); onClick() }
                if (rank != null) {
                    addView(TextView(activity).apply {
                        text = rank.toString(); textSize = 11f; setTextColor(InkDim)
                        gravity = Gravity.CENTER; minWidth = dp(28)
                    })
                }
                val thumb = FrameLayout(activity).apply {
                    clipToOutline = true
                    outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(6).toFloat()) } }
                }
                thumb.addView(ImageView(activity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    if (art != null) setImageBitmap(art) else setBackgroundColor(0xFF1A1D22.toInt())
                }, FrameLayout.LayoutParams(dp(44), dp(44)))
                addView(thumb, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(4); marginEnd = dp(12) })
                val info = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                info.addView(TextView(activity).apply { text = title; textSize = 13f; typeface = Typeface.create("sans-serif", Typeface.BOLD); setTextColor(Ink); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                info.addView(TextView(activity).apply { text = sub; textSize = 11f; setTextColor(InkDim); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (badge.isNotBlank()) {
                    addView(TextView(activity).apply {
                        text = badge; textSize = 9f; setTextColor(SpotifyGreen)
                        background = GradientDrawable().apply { setColor(0x221ED760); cornerRadius = dp(10).toFloat() }
                        setPadding(dp(6), dp(3), dp(6), dp(3))
                    })
                }
            }

        fun sectionLabel(text: String, color: Int = InkDim) =
            mono(text, 9f, color).apply { letterSpacing = 0.18f; setPadding(dp(2), dp(6), 0, dp(10)) }

        // ── Playlist detail slide-in ──────────────────────────────────────────
        fun showPlaylistDetail(playlist: SpotifyPlaylist) {
            val detail = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF0B0E11.toInt())
                translationX = panel.width.toFloat()
            }
            val dh = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(14), dp(16), dp(10))
            }
            dh.addView(TextView(this).apply {
                text = "‹"; textSize = 26f; setTextColor(InkDim); setPadding(dp(10), 0, dp(10), 0); isClickable = true
                setOnClickListener { detail.animate().translationX(panel.width.toFloat()).setDuration(260).setInterpolator(android.view.animation.AccelerateInterpolator(1.6f)).withEndAction { panel.removeView(detail) }.start() }
            })
            dh.addView(TextView(this).apply {
                text = playlist.name; textSize = 15f; typeface = Typeface.create("sans-serif", Typeface.BOLD); setTextColor(Ink); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val acts = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(16), dp(8), dp(16), dp(12)); gravity = Gravity.CENTER_VERTICAL }
            fun aBtn(lbl: String, bg: Int, fg: Int, click: () -> Unit) = TextView(this).apply {
                text = lbl; textSize = 12f; typeface = Typeface.create("sans-serif", Typeface.BOLD); setTextColor(fg); gravity = Gravity.CENTER
                background = GradientDrawable().apply { setColor(bg); cornerRadius = dp(20).toFloat() }
                setPadding(dp(18), dp(9), dp(18), dp(9)); isClickable = true; setOnClickListener { haptic(this); click() }
            }
            acts.addView(aBtn("▶  Play All", SpotifyGreen, 0xFF000000.toInt()) { mediaUiScope.launch { spotifyApi.playContext(playlist.uri, 0) } },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(10) })
            acts.addView(aBtn("⇄  Shuffle", 0xFF1A1D22.toInt(), Ink) { mediaUiScope.launch { spotifyApi.setShuffle(true); spotifyApi.playContext(playlist.uri, 0) } })
            val ts = ScrollView(this).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
            val tb = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, dp(8), dp(32)) }
            ts.addView(tb)
            tb.addView(TextView(this).apply { text = "Loading tracks…"; textSize = 13f; setTextColor(InkDim); setPadding(dp(18), dp(12), 0, 0) })
            detail.addView(dh, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            detail.addView(View(this).apply { setBackgroundColor(0x10FFFFFF) }, LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
            detail.addView(acts, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            detail.addView(View(this).apply { setBackgroundColor(0x08FFFFFF) }, LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
            detail.addView(ts, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            panel.addView(detail, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            detail.animate().translationX(0f).setDuration(300).setInterpolator(android.view.animation.DecelerateInterpolator(2.0f)).start()

            mediaUiScope.launch {
                val tracks = withContext(Dispatchers.IO) { spotifyApi.getPlaylistTracks(playlist.id, limit = 100) }
                val arts2 = withContext(Dispatchers.IO) { coroutineScope { tracks.map { t -> async { t.albumArtUrl?.let { runCatching { spotifyApi.fetchAlbumArt(it) }.getOrNull() } } }.awaitAll() } }
                tb.removeAllViews()
                if (tracks.isEmpty()) {
                    tb.addView(TextView(activity).apply { text = "No tracks found"; textSize = 13f; setTextColor(InkDim); setPadding(dp(18), dp(12), 0, 0) })
                } else {
                    tracks.forEachIndexed { idx2, track ->
                        tb.addView(trackRow(idx2 + 1, arts2.getOrNull(idx2), track.name, track.artist) {
                            mediaUiScope.launch { spotifyApi.playContext(playlist.uri, idx2) }
                        })
                        tb.addView(View(activity).apply { setBackgroundColor(0x08FFFFFF) }, LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
                    }
                }
            }
        }

        // ── Track list detail (for TOP / RECENT / LIKED) ──────────────────────
        fun showTrackListDetail(title: String, tracks: List<SpotifyTrack>, arts: List<android.graphics.Bitmap?>, showRank: Boolean = false) {
            val detail = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF0B0E11.toInt())
                translationX = panel.width.toFloat()
            }
            val dh = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(14), dp(16), dp(10))
            }
            dh.addView(TextView(this).apply {
                text = "‹"; textSize = 26f; setTextColor(InkDim); setPadding(dp(10), 0, dp(10), 0); isClickable = true
                setOnClickListener { detail.animate().translationX(panel.width.toFloat()).setDuration(260).setInterpolator(android.view.animation.AccelerateInterpolator(1.6f)).withEndAction { panel.removeView(detail) }.start() }
            })
            dh.addView(TextView(this).apply {
                text = title; textSize = 15f; typeface = Typeface.create("sans-serif", Typeface.BOLD); setTextColor(Ink)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val ts = ScrollView(this).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
            val tb = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(4), dp(8), dp(32)) }
            ts.addView(tb)
            tracks.forEachIndexed { i, track ->
                val badge = if (showRank) "#${i + 1}" else ""
                tb.addView(trackRow(if (showRank) null else null, arts.getOrNull(i), track.name, track.artist, badge) {
                    mediaUiScope.launch { spotifyApi.playTrack(track.uri) }
                })
                tb.addView(View(activity).apply { setBackgroundColor(0x08FFFFFF) }, LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
            }
            detail.addView(dh, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            detail.addView(View(this).apply { setBackgroundColor(0x10FFFFFF) }, LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
            detail.addView(ts, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            panel.addView(detail, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            detail.animate().translationX(0f).setDuration(300).setInterpolator(android.view.animation.DecelerateInterpolator(2.0f)).start()
        }

        // ── Tab logic ─────────────────────────────────────────────────────────
        val personalizedKeys = listOf("discover weekly", "daily mix", "release radar", "on repeat", "repeat rewind", "time capsule", "your top songs", "wrapped")
        fun isPersonalized(name: String) = personalizedKeys.any { name.lowercase().contains(it) }

        var searchJob: Job? = null

        fun selectTab(idx: Int) {
            activeTab = idx
            tabViews.forEachIndexed { i, tv ->
                tv.setTextColor(if (i == idx) SpotifyGreen else InkDim)
                tv.typeface = if (i == idx) Typeface.create("sans-serif", Typeface.BOLD) else Typeface.DEFAULT
            }
            searchWrap.visibility = if (idx == 3) View.VISIBLE else View.GONE
            body.removeAllViews()
            scroll.scrollTo(0, 0)

            when (idx) {
                0 -> { // TOP TRACKS
                    val top = spotifyCachedTopTracks
                    if (top.isEmpty()) {
                        body.addView(TextView(this).apply { text = "Reconnect Spotify to load your top tracks"; textSize = 13f; setTextColor(InkDim); setPadding(dp(2), dp(12), 0, 0) })
                    } else {
                        body.addView(sectionLabel("YOUR TOP TRACKS  •  ALL TIME", 0xFF8BE8FF.toInt()))
                        // Show top 9 as a grid (most visual)
                        val gridTracks = top.take(9)
                        populateGrid(gridTracks.map { it.name to it.artist }, spotifyCachedTopArts.take(9),
                            intArrayOf(0xFF1A2030.toInt(), 0xFF0A1020.toInt()), 0xFF1E3050.toInt()) { i ->
                            mediaUiScope.launch { spotifyApi.playTrack(gridTracks[i].uri) }
                        }
                        // Show all as a list below
                        body.addView(sectionLabel("ALL ${top.size} TRACKS"))
                        top.forEachIndexed { i, track ->
                            body.addView(trackRow(i + 1, spotifyCachedTopArts.getOrNull(i), track.name, track.artist,
                                if (i < 3) "★" else "") {
                                mediaUiScope.launch { spotifyApi.playTrack(track.uri) }
                            })
                            body.addView(View(activity).apply { setBackgroundColor(0x08FFFFFF) }, LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
                        }
                    }
                }
                1 -> { // RECENT
                    val recent = spotifyCachedRecent
                    if (recent.isEmpty()) {
                        body.addView(TextView(this).apply { text = "Play something in Spotify to see it here"; textSize = 13f; setTextColor(InkDim); setPadding(dp(2), dp(12), 0, 0) })
                    } else {
                        body.addView(sectionLabel("RECENTLY PLAYED"))
                        recent.forEachIndexed { i, track ->
                            body.addView(trackRow(null, spotifyCachedRecentArts.getOrNull(i), track.name, track.artist) {
                                mediaUiScope.launch { spotifyApi.playTrack(track.uri) }
                            })
                            body.addView(View(activity).apply { setBackgroundColor(0x08FFFFFF) }, LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
                        }
                    }
                }
                2 -> { // PLAYLISTS
                    val playlists = spotifyCachedPlaylists
                    val liked = spotifyCachedLikedSongs

                    // Liked songs virtual entry
                    if (liked.isNotEmpty()) {
                        body.addView(sectionLabel("LIBRARY"))
                        body.addView(LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                            setPadding(dp(4), dp(10), dp(16), dp(10)); isClickable = true
                            setOnClickListener { haptic(this); showTrackListDetail("Liked Songs", liked, spotifyCachedLikedArts) }
                            val thumb = FrameLayout(activity).apply {
                                clipToOutline = true
                                outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(6).toFloat()) } }
                            }
                            thumb.addView(View(activity).apply { setBackgroundColor(SpotifyGreen) }, FrameLayout.LayoutParams(dp(44), dp(44)))
                            thumb.addView(TextView(activity).apply { text = "♥"; textSize = 20f; setTextColor(0xFF000000.toInt()); gravity = Gravity.CENTER }, FrameLayout.LayoutParams(dp(44), dp(44)))
                            addView(thumb, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(4); marginEnd = dp(12) })
                            val info = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                            info.addView(TextView(activity).apply { text = "Liked Songs"; textSize = 13f; typeface = Typeface.create("sans-serif", Typeface.BOLD); setTextColor(Ink) })
                            info.addView(TextView(activity).apply { text = "${liked.size} songs"; textSize = 11f; setTextColor(InkDim) })
                            addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                            addView(TextView(activity).apply { text = "›"; textSize = 20f; setTextColor(InkDim) })
                        })
                        body.addView(View(activity).apply { setBackgroundColor(0x08FFFFFF) }, LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
                    }

                    val personalized = playlists.indices.filter { isPersonalized(playlists[it].name) }
                    val mine = playlists.indices.filter { !isPersonalized(playlists[it].name) }

                    if (personalized.isNotEmpty()) {
                        body.addView(sectionLabel("MADE FOR YOU", 0xFF8BE8FF.toInt()))
                        personalized.forEach { pidx ->
                            val p = playlists[pidx]; val art = spotifyCachedPlaylistArts.getOrNull(pidx)
                            body.addView(LinearLayout(this).apply {
                                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                                setPadding(dp(4), dp(10), dp(16), dp(10)); isClickable = true
                                setOnClickListener { haptic(this); showPlaylistDetail(p) }
                                val thumb = FrameLayout(activity).apply {
                                    clipToOutline = true
                                    outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(6).toFloat()) } }
                                }
                                thumb.addView(if (art != null) ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageBitmap(art) }
                                    else View(activity).apply { setBackgroundColor(0xFF1A2830.toInt()) }, FrameLayout.LayoutParams(dp(44), dp(44)))
                                addView(thumb, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(4); marginEnd = dp(12) })
                                val info = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                                info.addView(TextView(activity).apply { text = p.name; textSize = 13f; typeface = Typeface.create("sans-serif", Typeface.BOLD); setTextColor(Ink); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                                info.addView(TextView(activity).apply { text = p.ownerName.ifBlank { "Spotify" }; textSize = 11f; setTextColor(InkDim) })
                                addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                                addView(TextView(activity).apply { text = "›"; textSize = 20f; setTextColor(InkDim) })
                            })
                            body.addView(View(activity).apply { setBackgroundColor(0x08FFFFFF) }, LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
                        }
                    }
                    if (mine.isNotEmpty()) {
                        body.addView(sectionLabel("YOUR PLAYLISTS"))
                        mine.forEach { pidx ->
                            val p = playlists[pidx]; val art = spotifyCachedPlaylistArts.getOrNull(pidx)
                            body.addView(LinearLayout(this).apply {
                                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                                setPadding(dp(4), dp(10), dp(16), dp(10)); isClickable = true
                                setOnClickListener { haptic(this); showPlaylistDetail(p) }
                                val thumb = FrameLayout(activity).apply {
                                    clipToOutline = true
                                    outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(6).toFloat()) } }
                                }
                                thumb.addView(if (art != null) ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageBitmap(art) }
                                    else View(activity).apply { setBackgroundColor(0xFF1A1D22.toInt()) }, FrameLayout.LayoutParams(dp(44), dp(44)))
                                addView(thumb, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(4); marginEnd = dp(12) })
                                val info = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                                info.addView(TextView(activity).apply { text = p.name; textSize = 13f; typeface = Typeface.create("sans-serif", Typeface.BOLD); setTextColor(Ink); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                                info.addView(TextView(activity).apply { text = p.ownerName.ifBlank { "My playlist" }; textSize = 11f; setTextColor(InkDim) })
                                addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                                addView(TextView(activity).apply { text = "›"; textSize = 20f; setTextColor(InkDim) })
                            })
                            body.addView(View(activity).apply { setBackgroundColor(0x08FFFFFF) }, LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
                        }
                    }
                    if (playlists.isEmpty() && liked.isEmpty()) {
                        body.addView(TextView(this).apply { text = "No playlists — reconnect Spotify to grant access"; textSize = 13f; setTextColor(InkDim); setPadding(dp(2), dp(12), 0, 0) })
                    }
                }
                3 -> { // SEARCH
                    searchField.requestFocus()
                    searchField.postDelayed({
                        val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                        imm?.showSoftInput(searchField, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                    }, 120)
                }
            }
        }

        tabViews.forEachIndexed { i, tv -> tv.setOnClickListener { selectTab(i) } }
        panelSelectTab = ::selectTab
        panelActiveTab = { activeTab }

        searchField.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s?.toString()?.trim() ?: ""
                searchJob?.cancel(); body.removeAllViews()
                if (q.length < 2) return
                searchJob = mediaUiScope.launch {
                    delay(320)
                    val tracks = withContext(Dispatchers.IO) { spotifyApi.search(q, limit = 20) }
                    val arts = withContext(Dispatchers.IO) { coroutineScope { tracks.map { t -> async { t.albumArtUrl?.let { runCatching { spotifyApi.fetchAlbumArt(it) }.getOrNull() } } }.awaitAll() } }
                    if (tracks.isEmpty()) {
                        body.addView(TextView(activity).apply { text = "No results for \"$q\""; textSize = 13f; setTextColor(InkDim); setPadding(dp(2), dp(12), 0, 0) })
                    } else {
                        tracks.forEachIndexed { i, track ->
                            body.addView(trackRow(null, arts.getOrNull(i), track.name, track.artist) {
                                mediaUiScope.launch { spotifyApi.playTrack(track.uri) }
                            })
                            body.addView(View(activity).apply { setBackgroundColor(0x08FFFFFF) }, LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
                        }
                    }
                }
            }
        })

        selectTab(0)

        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(260).start()
        panel.animate().translationY(0f).setDuration(420)
            .setInterpolator(android.view.animation.DecelerateInterpolator(2.2f)).start()
    } }



    // Click wheel page inside pager — SOURCE button navigates back to library.
    private fun clickWheelDockPage(onLibraryTapped: () -> Unit): View { with(activity) {
        return FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
                0xFF101216.toInt(), 0xFF050506.toInt(), 0xFF000000.toInt()
            )).apply { setStroke(dp(1), 0xFF20232A.toInt()) }
            val wheelSize = clickWheelSize()
            addView(WheelWellView(context), FrameLayout.LayoutParams(wheelSize + dp(18), wheelSize + dp(18), Gravity.CENTER))
            addView(ClickWheelView(context).apply {
                sourceLabel = "LIBRARY"
                onLibrary = { haptic(this); onLibraryTapped() }
                onCenter = { haptic(this); mediaSessionSource.togglePlayPause() }
                onLeft = { haptic(this); mediaSessionSource.skipToPrevious() }
                onRight = { haptic(this); mediaSessionSource.skipToNext() }
                onBottom = { haptic(this); mediaSessionSource.togglePlayPause() }
                onScroll = { steps ->
                    mediaSessionSource.adjustVolume(steps)
                    showVolumeHud()
                }
            }, FrameLayout.LayoutParams(wheelSize, wheelSize, Gravity.CENTER))
        }
    } }

    fun musicBlackDock(): View { with(activity) {
        return FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
                0xFF12151A.toInt(),
                0xFF08090B.toInt(),
                0xFF020203.toInt()
            )).apply {
                setStroke(dp(1), 0xFF20242C.toInt())
            }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                addView(musicBlackTransportButton("◀", accent = false) {
                    haptic(this)
                    mediaSessionSource.skipToPrevious()
                }, LinearLayout.LayoutParams(dp(66), dp(66)).apply { marginEnd = dp(24) })
                addView(musicBlackTransportButton(if (mediaSessionSource.nowPlaying.value?.isPlaying == true) "Ⅱ" else "▶", accent = true) {
                    haptic(this)
                    mediaSessionSource.togglePlayPause()
                }, LinearLayout.LayoutParams(dp(82), dp(82)))
                addView(musicBlackTransportButton("▶", accent = false) {
                    haptic(this)
                    mediaSessionSource.skipToNext()
                }, LinearLayout.LayoutParams(dp(66), dp(66)).apply { marginStart = dp(24) })
            }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        }
    } }

    fun musicBlackTransportButton(label: String, accent: Boolean, action: TextView.() -> Unit): TextView { with(activity) {
        return TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            includeFontPadding = false
            textSize = if (accent) 27f else 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (accent) 0xFFFFFFFF.toInt() else 0xFFC9CED6.toInt())
            background = musicBlackTransportBackground(accent)
            isClickable = true
            elevation = dp(if (accent) 10 else 7).toFloat()
            setOnClickListener { action() }
        }
    } }

    private fun musicBlackTransportBackground(accent: Boolean): Drawable { with(activity) {
        val skirt = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFF050608.toInt())
        }
        val rim = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            if (accent) 0xFFFF7B2D.toInt() else 0xFF303640.toInt(),
            if (accent) 0xFF9D250D.toInt() else 0xFF10141A.toInt()
        )).apply {
            shape = GradientDrawable.OVAL
            setStroke(dp(1), if (accent) 0xFFFF9B4A.toInt() else 0xFF090B0F.toInt())
        }
        val face = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            if (accent) 0xFFFF6A21.toInt() else 0xFF252B33.toInt(),
            if (accent) 0xFFE53910.toInt() else 0xFF171B21.toInt(),
            if (accent) 0xFFA6280B.toInt() else 0xFF0A0C10.toInt()
        )).apply {
            shape = GradientDrawable.OVAL
            setStroke(dp(1), if (accent) 0xFFFFB066.toInt() else 0xFF303741.toInt())
        }
        val glint = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            if (accent) 0x66FFFFFF else 0x32FFFFFF,
            0x00FFFFFF
        )).apply {
            shape = GradientDrawable.OVAL
        }
        return LayerDrawable(arrayOf(skirt, rim, face, glint)).apply {
            val drop = dp(if (accent) 8 else 6)
            setLayerInset(0, dp(1), drop, dp(1), 0)
            setLayerInset(1, dp(2), dp(1), dp(2), drop)
            setLayerInset(2, dp(4), dp(3), dp(4), drop + dp(2))
            setLayerInset(3, dp(13), dp(8), dp(13), dp(if (accent) 48 else 40))
        }
    } }
}
