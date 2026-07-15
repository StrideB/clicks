package com.fran.teclas

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.fran.teclas.MainActivity.Companion.Line
import com.fran.teclas.predict.SpaceManager
import java.util.Locale

/**
 * MainActivity-side host for the universal-search results band: the three-zone results list
 * (answer hero, App-Library-style app grid, grouped rows), the instant-answer card builders
 * (Brave rich answers, sports scores, inline AI answers, command previews, the landmark ride
 * card), and the tap-defer machinery that holds rebuilds while a finger is down on a result.
 * Bodies are moved verbatim from MainActivity and run with the activity as receiver, so all
 * view helpers / theme tokens / search state keep resolving exactly as before. The search
 * DATA pipeline (query handling, API calls, ranking) stays in MainActivity.
 */
internal class SearchResultsHost(private val activity: MainActivity) {

    // ── Tap-defer machinery (wired into MainActivity.dispatchTouchEvent) ─────
    private var searchResultTouchActive = false
    private var searchResultRefreshPending = false

    // True while search results are the interactive surface (docked library search, widget
    // universal search, or unfolded search) with a live query.
    private fun searchResultsInteractive(): Boolean = with(activity) {
        query.isNotBlank() && (libraryOpen || isWidgetUniversalSearchActive() || isUnfoldedInnerLayoutActive())
    }

    // See searchResultTouchActive. Down on the results (not the keyboard) starts a defer window;
    // up/cancel ends it and flushes any rebuild that was held back — the flush is posted so it runs
    // AFTER this touch's click has been delivered to the card, so the tap actually fires.
    internal fun trackSearchResultTouch(event: MotionEvent) { with(activity) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                searchResultTouchActive = searchResultsInteractive() &&
                    !isInsideKeyboard(event.rawX, event.rawY)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (searchResultTouchActive) {
                    searchResultTouchActive = false
                    if (searchResultRefreshPending) {
                        searchResultRefreshPending = false
                        if (hasContentFrame()) contentFrame.post { flushDeferredSearchRefresh() }
                    }
                }
            }
        }
    } }

    private fun flushDeferredSearchRefresh() { with(activity) {
        when {
            libraryOpen -> refreshLibraryContent()
            isWidgetUniversalSearchActive() -> refreshWidgetSearchContent()
            isUnfoldedInnerLayoutActive() -> refreshUnfoldedLibraryContent()
        }
    } }

    // Async search callbacks call this before rebuilding the results list. While a finger is down on
    // the results, hold the rebuild (set pending) so the touched card isn't destroyed mid-tap; the
    // rebuild is flushed on finger-up. Returns true when the caller should skip its rebuild.
    internal fun deferSearchRebuildWhileTouching(): Boolean { with(activity) {
        if (searchResultTouchActive && query.isNotBlank()) {
            searchResultRefreshPending = true
            return true
        }
        return false
    } }

    // Persistent host for the widget search results surface: the glass plate + entrance
    // animation live on the host, which survives refreshes — only the results list inside is
    // swapped. Recreating the blur and replaying the animation per keystroke caused flicker.
    private var widgetSearchHost: FrameLayout? = null
    private var widgetSearchList: View? = null

    internal fun refreshWidgetSearchContent() { with(activity) {
        if (deferSearchRebuildWhileTouching()) return
        val area = widgetSearchContentArea ?: return
        val glass = focusSurfaceGlassEnabled()
        val fresh = searchResultsList(widgetMode = true)
        if (glass) padSearchContentForGlass(fresh)
        widgetSearchHost?.takeIf { it.parent === area }?.let { host ->
            // In-place swap on refresh — no blur recreation, no re-entrance animation.
            widgetSearchList?.let { host.removeView(it) }
            host.addView(fresh, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            widgetSearchList = fresh
            return
        }
        area.removeAllViews()
        val host = searchGlassHost(fresh, glass)
        area.addView(host, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        widgetSearchHost = host
        widgetSearchList = fresh
        // Entrance animation only when the search panel first appears.
        host.alpha = 0f
        host.translationY = dp(52).toFloat()
        host.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(400)
            .setInterpolator(DecelerateInterpolator())
            .start()
    } }

    /** Host frame for a search results surface: optional glass plate below the content. Kept
     *  across refreshes so only the content view is swapped. */
    internal fun searchGlassHost(content: View, glass: Boolean): FrameLayout { with(activity) {
        return FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            if (glass) addView(
                if (nativeGlassSurfaceActive()) NativeFoldGlassPanel(context, radiusDp = 24)
                else DynamicGlassPlate(context, radiusDp = 24, strength = 1.72f, edgeInsetDp = 0).apply { setGlassProgress(1f) },
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            addView(content, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
    } }

    internal fun padSearchContentForGlass(content: View) { with(activity) {
        content.setPadding(
            content.paddingLeft + dp(10),
            content.paddingTop + dp(10),
            content.paddingRight + dp(10),
            content.paddingBottom + dp(10)
        )
    } }

    // Unfolded/inner search panel shares the same three-zone presentation as every other surface.
    // (Previously rendered its own 2-column tiles with kind pills via unfoldedSearchResultTile.)
    internal fun unfoldedSearchResultsList(): View = searchResultsList(widgetMode = false)

    // Grid and list surfaces now share one presentation — the three-zone layout. (Previously this
    // rendered 2-column "bento" cards with kind pills; unified so the redesign shows on every surface,
    // wide/unfolded included.)
    internal fun searchResultsGrid(): View = searchResultsList(widgetMode = false)

    // Universal search presentation — three zones, read top-down, hugging the docked search bar:
    //   ZONE 1  one instant answer (score card > rich answer > AI), never a stack of cards
    //   ZONE 2  matching apps as bare launcher icons (icon-pack honoured), no boxes/plates/tags
    //   ZONE 3  everything else in one list, grouped under quiet headers, no per-row kind pills
    private fun searchResultsList(widgetMode: Boolean = false): View = with(activity) { ScrollView(this).apply {
        clipToPadding = false
        isVerticalScrollBarEnabled = false   // search results scroll cleanly — no scrollbar track
        val results = universalSearchResults()
        val command = searchCommandPreview()
        val aiInline = searchAiInlineState()
        val rich = searchRichAnswer()
        val sports = searchSportsCard()
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, if (widgetMode) dp(10) else dp(12), 0, if (widgetMode) dp(18) else dp(10))

            // Command preview ("message alex …") is an action affordance — kept above the answer.
            command?.let {
                addView(searchCommandCard(it), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(68)).apply {
                    bottomMargin = dp(12)
                })
            }

            // ZONE 1 — a single instant answer. Only the strongest vertical becomes the hero.
            val hero: View? = when {
                sports != null -> sportsScoreCard(sports)
                rich != null -> braveRichCard(rich)
                aiInline != null -> searchAiAnswerCard(aiInline)
                else -> null
            }
            hero?.let {
                addView(searchZoneHeader("Answer"), zoneHeaderParams())
                addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(14)
                })
                // A ride offer belongs WITH the landmark answer — render it right under the hero
                // card (not down in the kind-grouped zone, where it'd sink below music).
                searchRideOffer()?.let { ride ->
                    addView(braveRideCard(ride), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dp(14)
                    })
                }
            }

            val visibleResults = if (aiInline != null) results.filterNot { it.kind == SearchKind.AI && it.title == "Ask Gemini" } else results
            if (visibleResults.isEmpty() && command == null && hero == null) {
                addView(TextView(context).apply {
                    text = "No results for \"$query\""
                    textSize = 13f * searchFontScale()
                    gravity = Gravity.CENTER
                    setTextColor(activeNeuTokens.inkDim)
                    includeFontPadding = false
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(180)))
            }

            // ZONE 2 — apps as an App-Library-style grid (the launcher's own icons / user's icon pack).
            val appResults = visibleResults.filter { it.kind == SearchKind.APP }
            if (appResults.isNotEmpty()) {
                addView(searchZoneHeader("Apps"), zoneHeaderParams())
                addView(searchAppGrid(appResults), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(12)
                })
            }

            // ZONE 3 — everything else, grouped, no pills. Headers carry the "kind".
            val otherResults = visibleResults.filter { it.kind != SearchKind.APP }
            var rowIndex = 0
            SEARCH_GROUP_ORDER.forEach { groupName ->
                val groupItems = otherResults.filter { searchGroupLabel(it.kind) == groupName }
                if (groupItems.isNotEmpty()) {
                    addView(searchZoneHeader(groupName), zoneHeaderParams())
                    groupItems.forEach { result ->
                        addView(searchGroupedRow(result, rowIndex++), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            bottomMargin = dp(2)
                        })
                    }
                }
            }

            // Context suggestions (predicted apps) — a labelled App-Library-style grid.
            contextSuggestionResults(results.mapNotNull { it.target?.packageName }.toSet())?.let { (label, items) ->
                addView(searchZoneHeader(label), zoneHeaderParams().apply { topMargin = dp(6) })
                addView(searchAppGrid(items), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        })
    } }

    /**
     * Long-press menu for an app in search results: pin it straight to the favorites dock (the
     * fixed "your apps" dock, not the context one) — so searching "instagram" lets you pin it
     * without leaving search. Also offers pinning to the active Space's board.
     */
    private fun showSearchAppMenu(anchor: View, result: SearchResult) { with(activity) {
        val pkg = result.target?.packageName ?: return
        val onDock = pkg in favoritePackages()
        val space = activeSpaceForUi()
        val pinned = space != null && pkg in space.pinned
        val popup = android.widget.PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, if (onDock) "Remove from dock" else "Pin to dock")
        if (space != null) {
            popup.menu.add(0, 2, 1,
                if (pinned) "Unpin from ${space.emoji} ${space.name} board" else "Pin to ${space.emoji} ${space.name} board")
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    setHomePresence(pkg, !onDock)
                    Toast.makeText(
                        this,
                        if (onDock) "Removed from dock" else "Pinned ${result.title} to dock",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                2 -> if (space != null) {
                    val next = if (pinned) space.pinned - pkg else space.pinned + pkg
                    SpaceManager.update(this, space.copy(pinned = next))
                    Toast.makeText(
                        this,
                        if (pinned) "Unpinned from ${space.name}" else "Pinned ${result.title} to ${space.name}",
                        Toast.LENGTH_SHORT,
                    ).show()
                    if (spaceBoardOverlay != null) reloadSpaceBoardForActiveSpace()
                }
            }
            true
        }
        popup.show()
    } }

    // Zone order for the grouped "everything else" list (must match searchGroupLabel outputs).
    private val SEARCH_GROUP_ORDER = listOf("Rides", "People", "Messages", "Calendar", "Files", "Music", "Travel", "Settings", "Web", "Ask AI")

    private fun searchGroupLabel(kind: SearchKind): String = when (kind) {
        SearchKind.RIDE -> "Rides"
        SearchKind.CONTACT, SearchKind.EMAIL -> "People"
        SearchKind.MESSAGE -> "Messages"
        SearchKind.CALENDAR -> "Calendar"
        SearchKind.FILE -> "Files"
        SearchKind.MUSIC -> "Music"
        SearchKind.TRAVEL -> "Travel"
        SearchKind.SETTING -> "Settings"
        SearchKind.WEB, SearchKind.ANSWER -> "Web"
        SearchKind.AI -> "Ask AI"
        SearchKind.APP -> "Apps"
    }

    // Scale factor from the user's search text-size pref (the pref itself and its settings
    // label stay in MainActivity: searchFontSizePref / searchFontSizeLabel).
    private fun searchFontScale(progress: Int = activity.searchFontSizePref()): Float =
        0.90f + progress.coerceIn(0, 100) / 100f * 0.50f   // 0→0.90x, 50→1.15x (Medium), 100→1.40x

    private fun searchZoneHeader(text: String): TextView = with(activity) {
        TextView(this).apply {
            this.text = text
            textSize = 15.5f * searchFontScale()
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(activeNeuTokens.ink)
            includeFontPadding = false
            setPadding(dp(2), dp(4), 0, dp(8))
        }
    }

    private fun zoneHeaderParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    // ZONE 2 — apps presented like the App Library grid (fixed columns, tap/long-press only — no
    // horizontal scroll, so nothing here reads as draggable).
    private fun searchAppGrid(items: List<SearchResult>, columns: Int = 4): View = with(activity) { LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        items.chunked(columns).forEachIndexed { rowIndex, rowItems ->
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                rowItems.forEachIndexed { columnIndex, result ->
                    addView(searchAppTile(result, rowIndex * columns + columnIndex), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }
                repeat(columns - rowItems.size) { addView(View(context), LinearLayout.LayoutParams(0, 1, 1f)) }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (rowIndex > 0) topMargin = dp(4)
            })
        }
    } }

    // A single app tile — same icon frame/size and label treatment as the App Library grid
    // (appTile), so search results and the library read as one consistent presentation. Tap/
    // long-press stay search-specific (pin to dock / pin to Space, not the library icon menu).
    private fun searchAppTile(result: SearchResult, index: Int): View { with(activity) {
        val app = result.target?.packageName?.let { pkg -> apps.firstOrNull { it.packageName == pkg } }
        val builtIn = result.target?.let { target -> builtInLauncherApps().firstOrNull { it.target.id == target.id } }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            alpha = 0f
            translationY = dp(8).toFloat()
            setPadding(dp(3), dp(4), dp(3), dp(2))
            postDelayed({
                animate().alpha(1f).translationY(0f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
            }, (index * 28L).coerceAtMost(240L))
            val iconFrame = appLibraryIconFrameSize()
            if (app != null || builtIn != null) {
                addView(FrameLayout(context).apply {
                    elevation = dp(3).toFloat(); background = libraryIconButtonBackground(13, Line)
                    addView(ImageView(context).apply {
                        setImageDrawable(iconFor(app?.toLibraryApp() ?: builtIn!!))
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        adjustViewBounds = true
                        setPadding(appIconInnerPadding(), appIconInnerPadding(), appIconInnerPadding(), appIconInnerPadding())
                    }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                }, LinearLayout.LayoutParams(iconFrame, iconFrame))
            } else {
                addView(searchResultIcon(result), LinearLayout.LayoutParams(iconFrame, iconFrame))
            }
            addView(TextView(context).apply {
                text = highlightedLabel(result.title, query, activeNeuTokens.ink)
                textSize = 10.5f * searchFontScale()
                gravity = Gravity.CENTER
                setTextColor(activeNeuTokens.inkDim)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                includeFontPadding = false
                setPadding(0, dp(6), 0, 0)
            }, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnClickListener { haptic(this); openSearchResult(result) }
            if (result.target?.packageName != null) {
                setOnLongClickListener { haptic(this); showSearchAppMenu(this, result); true }
            } else result.longAction?.let { longAction ->
                setOnLongClickListener { haptic(this); longAction(); true }
            }
        }
    } }

    // ZONE 3 — one clean row: icon + title + secondary line. No kind pill (the header names it).
    private fun searchGroupedRow(result: SearchResult, index: Int): View { with(activity) {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            alpha = 0f
            translationY = dp(6).toFloat()
            setPadding(dp(6), dp(7), dp(6), dp(7))
            postDelayed({
                animate().alpha(1f).translationY(0f).setDuration(200).setInterpolator(DecelerateInterpolator()).start()
            }, (index * 24L).coerceAtMost(220L))
            addView(searchResultIcon(result), LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(12) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = highlightedLabel(result.title, query, activeNeuTokens.ink)
                    textSize = 14f * searchFontScale()
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setTextColor(activeNeuTokens.ink)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    includeFontPadding = false
                }, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                if (result.subtitle.isNotBlank()) {
                    addView(TextView(context).apply {
                        text = result.subtitle
                        textSize = 11.5f * searchFontScale()
                        setTextColor(activeNeuTokens.inkFaint)
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        includeFontPadding = false
                        setPadding(0, dp(2), 0, 0)
                    }, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            setOnClickListener { haptic(this); openSearchResult(result) }
            result.longAction?.let { longAction ->
                setOnLongClickListener { haptic(this); longAction(); true }
            }
        }
    } }

    private fun searchResultIcon(result: SearchResult): View { with(activity) {
        val app = result.target?.packageName?.let { pkg -> apps.firstOrNull { it.packageName == pkg } }
        val builtIn = result.target?.let { target -> builtInLauncherApps().firstOrNull { it.target.id == target.id } }
        return FrameLayout(this).apply {
            background = if (result.kind == SearchKind.APP && (app != null || builtIn != null)) {
                libraryIconButtonBackground(12, Line)
            } else {
                Neu.drawable(activeNeuTokens, dp(12).toFloat(), NeuLevel.PRESSED_SM)
            }
            setPadding(dp(5), dp(5), dp(5), dp(5))
            if (result.kind == SearchKind.APP && (app != null || builtIn != null)) {
                addView(ImageView(context).apply {
                    setImageDrawable(iconFor(app?.toLibraryApp() ?: builtIn!!))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            } else {
                addView(TextView(context).apply {
                    text = searchKindGlyph(result.kind)
                    gravity = Gravity.CENTER
                    textSize = if (result.kind == SearchKind.AI) 11f else 14f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(searchKindAccent(result.kind))
                    background = Neu.drawable(activeNeuTokens, dp(9).toFloat(), NeuLevel.RAISED_SM)
                }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            }
        }
    } }

    private fun searchCardBackground(kind: SearchKind, isBest: Boolean, radiusDp: Int): Drawable { with(activity) {
        if (!focusSurfaceGlassEnabled()) {
            val base = Neu.drawable(activeNeuTokens, dp(radiusDp).toFloat(), if (isBest) NeuLevel.RAISED else NeuLevel.RAISED_SM)
            if (!isBest) return base
            val ring = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = dp(radiusDp).toFloat()
                setStroke(dp(1), adjustAlpha(searchKindAccent(kind), 0.72f))
            }
            return LayerDrawable(arrayOf(base, ring))
        }
        val solid = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            if (activeNeuTokens.mode == NeuMode.LIGHT) 0xFFF1F3F6.toInt() else activeNeuTokens.baseHi,
            if (activeNeuTokens.mode == NeuMode.LIGHT) 0xFFE8EBF0.toInt() else activeNeuTokens.base,
            if (activeNeuTokens.mode == NeuMode.LIGHT) 0xFFDDE2E9.toInt() else activeNeuTokens.base
        )).apply {
            cornerRadius = dp(radiusDp).toFloat()
        }
        val base = Neu.drawable(activeNeuTokens, dp(radiusDp).toFloat(), if (isBest) NeuLevel.RAISED else NeuLevel.RAISED_SM)
        if (!isBest) return LayerDrawable(arrayOf(solid, base))
        val ring = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(1), searchKindAccent(kind))
        }
        return LayerDrawable(arrayOf(solid, base, ring))
    } }

    private fun searchKindTag(kind: SearchKind): TextView = with(activity) { mono(kind.name, 7.5f, activeNeuTokens.inkFaint).apply {
        gravity = Gravity.CENTER
        letterSpacing = 0.1f
        setPadding(dp(7), 0, dp(7), 0)
        background = Neu.drawable(activeNeuTokens, dp(9).toFloat(), NeuLevel.PRESSED_SM)
    } }

    private fun searchKindAccent(kind: SearchKind): Int = with(activity) { when (kind) {
        SearchKind.APP -> Neu.BLUE
        SearchKind.CONTACT -> Neu.ORANGE
        SearchKind.EMAIL -> Neu.ACCENT
        SearchKind.MESSAGE -> Neu.TEAL
        SearchKind.CALENDAR -> Neu.AMBER
        SearchKind.TRAVEL -> Neu.PURPLE
        SearchKind.AI -> Neu.PURPLE
        SearchKind.MUSIC -> Neu.GREEN
        SearchKind.FILE -> Neu.BLUE
        SearchKind.SETTING -> goKeyColor
        SearchKind.WEB -> Neu.BLUE
        SearchKind.ANSWER -> 0xFFFB542B.toInt()   // Brave orange
        SearchKind.RIDE -> 0xFF1FBAD6.toInt()      // ride/transport teal
    } }

    private fun searchKindGlyph(kind: SearchKind): String = when (kind) {
        SearchKind.CONTACT -> "P"
        SearchKind.EMAIL -> "@"
        SearchKind.MESSAGE -> "M"
        SearchKind.CALENDAR -> "C"
        SearchKind.AI -> "AI"
        SearchKind.APP -> "A"
        SearchKind.TRAVEL -> "✈"
        SearchKind.MUSIC -> "♪"
        SearchKind.FILE -> "F"
        SearchKind.SETTING -> "⚙"
        SearchKind.WEB -> "W"
        SearchKind.ANSWER -> "✦"
        SearchKind.RIDE -> "🚗"
    }

    private fun searchCommandPreview(): SearchCommandPreview? { with(activity) {
        val clean = query.trim()
        if (clean.isBlank()) return null
        val verb = clean.substringBefore(' ').lowercase(Locale.US)
        val body = clean.substringAfter(' ', "").trim()
        if (body.isBlank() && verb !in listOf("ask", "ai", "gemini")) return null
        return when (verb) {
            "text", "sms", "message" -> SearchCommandPreview("Message ${body.substringBefore(' ')}", "SEND MESSAGE · MESSAGES", "➤")
            "email", "mail" -> SearchCommandPreview("Email ${body.substringBefore(' ')}", "COMPOSE EMAIL", "➤")
            "call" -> SearchCommandPreview("Call $body", "START CALL · PHONE", "✆")
            "calendar", "schedule" -> SearchCommandPreview(body.ifBlank { "Create event" }, "CREATE EVENT · CALENDAR", "＋")
            "open", "launch" -> SearchCommandPreview("Open $body", "OPEN APP", "➤")
            "play" -> SearchCommandPreview("Play $body", "START MUSIC SEARCH", "▶")
            "search", "google", "web" -> SearchCommandPreview(body.ifBlank { clean }, "SEARCH GOOGLE IN TECLAS", "G")
            "ask", "ai", "gemini" -> SearchCommandPreview(body.ifBlank { clean }, "ASK TECLAS AI", "AI")
            else -> null
        }
    } }

    private fun searchCommandCard(command: SearchCommandPreview): View { with(activity) {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            setPadding(dp(10), dp(8), dp(12), dp(8))
            background = searchCommandBackground()
            addView(TextView(context).apply {
                text = command.glyph
                gravity = Gravity.CENTER
                textSize = if (command.glyph == "AI") 12f else 17f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Neu.GREEN)
                background = Neu.drawable(activeNeuTokens, dp(12).toFloat(), NeuLevel.RAISED_SM)
            }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(11) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply {
                    text = command.title
                    textSize = 14f
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    setTextColor(activeNeuTokens.ink)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    includeFontPadding = false
                }, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                addView(mono(command.subtitle, 9f, Neu.GREEN).apply {
                    letterSpacing = 0.08f
                    setPadding(0, dp(5), 0, 0)
                }, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(searchKindTag(SearchKind.AI).apply {
                text = "DO THIS"
                setTextColor(Neu.GREEN)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(22)).apply { marginStart = dp(8) })
            setOnClickListener {
                haptic(this)
                if (executeTypeToDoCommand(query)) {
                    query = ""
                    if (libraryOpen) refreshLibraryContent() else render()
                }
            }
        }
    } }

    private fun searchCommandBackground(): Drawable { with(activity) {
        if (!focusSurfaceGlassEnabled()) {
            val base = Neu.drawable(activeNeuTokens, dp(15).toFloat(), NeuLevel.RAISED)
            val ring = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = dp(15).toFloat()
                setStroke(dp(1), adjustAlpha(Neu.GREEN, 0.72f))
            }
            return LayerDrawable(arrayOf(base, ring))
        }
        val solid = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            if (activeNeuTokens.mode == NeuMode.LIGHT) 0xFFF1F3F6.toInt() else activeNeuTokens.baseHi,
            if (activeNeuTokens.mode == NeuMode.LIGHT) 0xFFE8EBF0.toInt() else activeNeuTokens.base,
            if (activeNeuTokens.mode == NeuMode.LIGHT) 0xFFDDE2E9.toInt() else activeNeuTokens.base
        )).apply {
            cornerRadius = dp(15).toFloat()
        }
        val base = Neu.drawable(activeNeuTokens, dp(15).toFloat(), NeuLevel.RAISED)
        val ring = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = dp(15).toFloat()
            setStroke(dp(1), Neu.GREEN)
        }
        return LayerDrawable(arrayOf(solid, base, ring))
    } }

    /** The Brave rich answer for the live query, or null once the query moves on. */
    internal fun searchRichAnswer(): BraveSearchApi.RichAnswer? = with(activity) {
        braveRichAnswer?.takeIf { braveRichQuery == query.trim() }
    }

    /** The ride offer to render under the landmark hero card — set only when the answer is a
     *  landmark and the user is within ride range of it (see resolveLandmarkRide). */
    private fun searchRideOffer(): MainActivity.RideOffer? = with(activity) {
        braveRideOffer?.takeIf { braveRichQuery == query.trim() && braveRichAnswer?.vertical == "landmark" }
    }

    /** Uber ride card shown directly under a landmark answer. Tap opens Uber with the landmark
     *  preloaded as the destination and pickup at the rider's location. */
    private fun braveRideCard(ride: MainActivity.RideOffer): View { with(activity) {
        val uberColor = if (activeNeuTokens.mode == NeuMode.LIGHT) 0xFF000000.toInt() else activeNeuTokens.ink
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = searchCardBackground(SearchKind.RIDE, false, 16)
            addView(TextView(context).apply {
                text = "🚗"
                gravity = Gravity.CENTER
                textSize = 15f
                includeFontPadding = false
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(adjustAlpha(uberColor, 0.12f))
                }
            }, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(12) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = "Ride to ${ride.name}"
                    textSize = 14.5f * searchFontScale()
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setTextColor(activeNeuTokens.ink)
                    includeFontPadding = false
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                addView(TextView(context).apply {
                    text = "Uber · pickup at your location"
                    textSize = 11.5f * searchFontScale()
                    setTextColor(activeNeuTokens.inkDim)
                    includeFontPadding = false
                    setPadding(0, dp(2), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(mono("GO", 10f, uberColor).apply {
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(6), dp(12), dp(6))
                background = Neu.drawable(activeNeuTokens, dp(10).toFloat(), NeuLevel.RAISED_SM)
            })
            setOnClickListener {
                haptic(this)
                openUberRide(ride.lat, ride.lng, ride.name)
            }
        }
    } }

    internal fun searchSportsCard(): SportsApi.ScoreCard? = with(activity) {
        sportsCard?.takeIf { sportsQuery == query.trim() }
    }

    /** Hero-style instant-answer card (Brave rich data): the value floats big over a full-bleed
     *  chart at the card's bottom edge, with a tinted change pill and a circular vertical badge.
     *  Provider credit stays in the detail line. Tap opens the full Brave page. */
    private fun braveRichCard(rich: BraveSearchApi.RichAnswer): View { with(activity) {
        val accent = 0xFFFB542B.toInt()   // Brave orange
        val deltaColor = if (rich.deltaUp) 0xFF46C184.toInt() else 0xFFE45B5B.toInt()
        val lineColor = if (rich.delta == null) accent else deltaColor
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            background = searchCardBackground(SearchKind.ANSWER, true, 18)
            clipToOutline = true   // the full-bleed chart must respect the rounded corners
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), if (rich.spark.size >= 2) dp(2) else dp(14))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(TextView(context).apply {
                        text = if (rich.glyph == "✦") verticalBadgeGlyph(rich.vertical) else rich.glyph
                        gravity = Gravity.CENTER
                        textSize = 13f
                        setTextColor(accent)
                        typeface = Typeface.DEFAULT_BOLD
                        includeFontPadding = false
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(adjustAlpha(accent, 0.14f))
                        }
                    }, LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(8) })
                    addView(TextView(context).apply {
                        text = rich.label
                        textSize = 13f
                        setTextColor(activeNeuTokens.inkDim)
                        includeFontPadding = false
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    rich.delta?.let { delta ->
                        addView(TextView(context).apply {
                            text = delta
                            textSize = 11.5f
                            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                            setTextColor(deltaColor)
                            includeFontPadding = false
                            setPadding(dp(10), dp(4), dp(10), dp(4))
                            background = GradientDrawable().apply {
                                cornerRadius = dp(99).toFloat()
                                setColor(adjustAlpha(deltaColor, 0.15f))
                            }
                        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            marginStart = dp(8)
                        })
                    }
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(TextView(context).apply {
                    text = rich.headline
                    textSize = if (rich.headline.length > 16) 24f else 34f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    letterSpacing = -0.02f
                    setTextColor(activeNeuTokens.ink)
                    includeFontPadding = false
                    maxLines = 2
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(9)
                })
                // Detail + provider credit in one quiet line — Brave's data partners require it.
                val credit = listOf(rich.detail, rich.provider.ifBlank { "Brave Search" })
                    .filter { it.isNotBlank() }.joinToString("  ·  ")
                if (credit.isNotBlank()) {
                    addView(TextView(context).apply {
                        text = credit
                        textSize = 11.5f
                        setTextColor(activeNeuTokens.inkDim)
                        includeFontPadding = false
                        setLineSpacing(dp(2).toFloat(), 1f)
                        maxLines = 3
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(4)
                    })
                }
                if (rich.forecast.isNotEmpty()) {
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        rich.forecast.forEach { day ->
                            addView(LinearLayout(context).apply {
                                orientation = LinearLayout.VERTICAL
                                gravity = Gravity.CENTER_HORIZONTAL
                                setPadding(0, dp(7), 0, dp(7))
                                background = GradientDrawable().apply {
                                    cornerRadius = dp(11).toFloat()
                                    setColor(adjustAlpha(activeNeuTokens.inkFaint, 0.08f))
                                }
                                addView(mono(day.day, 7.5f, activeNeuTokens.inkFaint).apply { letterSpacing = 0.08f })
                                addView(TextView(context).apply {
                                    text = day.glyph
                                    gravity = Gravity.CENTER
                                    textSize = 15f
                                    includeFontPadding = false
                                    setPadding(0, dp(4), 0, dp(4))
                                }, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                                addView(TextView(context).apply {
                                    text = day.hi
                                    gravity = Gravity.CENTER
                                    textSize = 11f
                                    typeface = Typeface.DEFAULT_BOLD
                                    setTextColor(activeNeuTokens.ink)
                                    includeFontPadding = false
                                }, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                                addView(TextView(context).apply {
                                    text = day.lo
                                    gravity = Gravity.CENTER
                                    textSize = 10f
                                    setTextColor(activeNeuTokens.inkDim)
                                    includeFontPadding = false
                                    setPadding(0, dp(1), 0, 0)
                                }, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                                if (day !== rich.forecast.first()) marginStart = dp(7)
                            })
                        }
                    }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(10)
                    })
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            if (rich.spark.size >= 2) {
                // Full bleed: the chart runs edge to edge and sits flush with the card's bottom.
                addView(sparklineView(rich.spark, lineColor),
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(62)).apply { topMargin = dp(6) })
            }
            setOnClickListener {
                haptic(this)
                // Local POIs jump straight to Google Maps; other verticals open the full answer
                // page on Brave. Never a bare search-results redirect when a target exists.
                openUrlDirectly(rich.url ?: "https://search.brave.com/search?q=${Uri.encode(query.trim())}")
            }
        }
    } }

    /** Badge character for verticals whose payload has no glyph of its own. */
    private fun verticalBadgeGlyph(vertical: String): String = when (vertical) {
        "cryptocurrency" -> "₿"
        "stocks", "stock" -> "◆"
        "currency" -> "$"
        "calculator", "unit_conversion", "unix_timestamp" -> "="
        "definitions" -> "Aa"
        else -> "✦"
    }

    /** Widget-style live-scores card (ESPN): league header, one row per game with team lines and
     *  a status chip — green while the game is live. Tap opens the game or scoreboard on ESPN. */
    private fun sportsScoreCard(card: SportsApi.ScoreCard): View { with(activity) {
        val accent = 0xFFCC0000.toInt()   // ESPN red
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            setPadding(dp(14), dp(12), dp(14), dp(11))
            background = searchCardBackground(SearchKind.ANSWER, true, 16)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply {
                    text = card.glyph
                    gravity = Gravity.CENTER
                    textSize = 13f
                    setTextColor(accent)
                    includeFontPadding = false
                    background = Neu.drawable(activeNeuTokens, dp(9).toFloat(), NeuLevel.RAISED_SM)
                }, LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(9) })
                addView(mono(card.label.uppercase(Locale.US), 8.5f, activeNeuTokens.inkDim).apply {
                    letterSpacing = 0.12f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(searchKindTag(SearchKind.ANSWER))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(30)))
            val single = card.games.size == 1
            card.games.forEachIndexed { index, game ->
                addView(sportsGameRow(game, single), LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(if (index == 0) 7 else 10) })
            }
            if (single && card.detail.isNotBlank()) {
                addView(TextView(context).apply {
                    text = card.detail
                    textSize = 11.5f
                    setTextColor(activeNeuTokens.inkDim)
                    includeFontPadding = false
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(6)
                })
            }
            addView(mono("DATA · ESPN", 7.5f, activeNeuTokens.inkFaint).apply {
                letterSpacing = 0.1f
                setPadding(0, dp(8), 0, 0)
            })
            setOnClickListener {
                haptic(this)
                openUrlDirectly(card.link)
            }
        }
    } }

    /** One game inside the scores card: away over home with right-aligned scores (leader bold),
     *  status chip at the end — clock while live, date/time before tip-off, "Final" after. */
    private fun sportsGameRow(game: SportsApi.Game, single: Boolean): View = with(activity) {
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(sportsTeamLine(game.awayName, game.awayScore, game.awayBold, single))
                addView(sportsTeamLine(game.homeName, game.homeScore, game.homeBold, single).apply {
                    setPadding(0, dp(if (single) 4 else 2), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (game.status.isNotBlank()) {
                val chipColor = if (game.live) 0xFF43B97F.toInt() else activeNeuTokens.inkDim
                addView(mono(game.status.uppercase(Locale.US), if (single) 9f else 8f, chipColor).apply {
                    letterSpacing = 0.06f
                    maxLines = 1
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                    background = Neu.drawable(activeNeuTokens, dp(9).toFloat(), NeuLevel.PRESSED_SM)
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dp(10)
                })
            }
        }
    }

    private fun sportsTeamLine(name: String, score: String, bold: Boolean, single: Boolean): View = with(activity) {
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = name
                textSize = if (single) 16f else 13f
                typeface = Typeface.create("sans-serif-medium", if (bold) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (bold) activeNeuTokens.ink else activeNeuTokens.inkDim)
                includeFontPadding = false
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(context).apply {
                text = score.ifBlank { "–" }
                textSize = if (single) 20f else 14f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(if (bold) activeNeuTokens.ink else activeNeuTokens.inkDim)
                includeFontPadding = false
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(8)
            })
        }
    }

    /** Minimal sparkline: normalized line over a soft fill, no axes or labels. */
    private fun sparklineView(points: List<Float>, color: Int): View = with(activity) { object : View(this) {
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f * resources.displayMetrics.density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            this.color = color
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            this.color = adjustAlpha(color, 0.16f)
        }
        override fun onDraw(canvas: Canvas) {
            val min = points.min()
            val span = (points.max() - min).takeIf { it > 0f } ?: 1f
            val inset = dp(2).toFloat()
            val w = width - 2 * inset
            val h = height - 2 * inset
            val path = Path()
            points.forEachIndexed { i, v ->
                val x = inset + w * i / (points.size - 1)
                val y = inset + h * (1f - (v - min) / span)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(Path(path).apply {
                lineTo(inset + w, height.toFloat()); lineTo(inset, height.toFloat()); close()
            }, fill)
            canvas.drawPath(path, line)
        }
    } }

    private fun searchAiInlineState(): AiAnswerState? { with(activity) {
        val clean = query.trim()
        if (clean.isBlank() || !looksLikeAiQuestion(clean)) return null
        val target = aiTarget(clean)
        return aiAnswersById[target.id] ?: AiAnswerState(clean, "Tap to ask Gemini from Teclas.", false)
    } }

    private fun searchAiAnswerCard(state: AiAnswerState): View { with(activity) {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            setPadding(dp(12), dp(11), dp(12), dp(12))
            background = searchCardBackground(SearchKind.AI, true, 16)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply {
                    text = "C"
                    gravity = Gravity.CENTER
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Neu.PURPLE)
                    background = Neu.drawable(activeNeuTokens, dp(9).toFloat(), NeuLevel.RAISED_SM)
                }, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(9) })
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(context).apply {
                        text = "Teclas AI"
                        textSize = 12f
                        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                        setTextColor(activeNeuTokens.ink)
                        includeFontPadding = false
                    }, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    addView(mono(if (state.loading) "GEMINI · THINKING" else "GEMINI", 8f, Neu.PURPLE).apply {
                        letterSpacing = 0.12f
                        setPadding(0, dp(3), 0, 0)
                    }, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }, LinearLayout.LayoutParams.MATCH_PARENT, dp(32))
            addView(TextView(context).apply {
                text = state.prompt
                textSize = 11f
                setTextColor(activeNeuTokens.inkDim)
                includeFontPadding = false
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = Neu.drawable(activeNeuTokens, dp(11).toFloat(), NeuLevel.PRESSED_SM)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(TextView(context).apply {
                text = state.answer
                textSize = 12f
                setTextColor(activeNeuTokens.ink)
                includeFontPadding = false
                setLineSpacing(dp(3).toFloat(), 1f)
                setPadding(dp(10), dp(9), dp(10), dp(9))
                background = Neu.drawable(activeNeuTokens, dp(11).toFloat(), NeuLevel.PRESSED_SM)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            })
            setOnClickListener {
                haptic(this)
                askGemini(query)
            }
        }
    } }
}
