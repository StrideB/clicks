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
import kotlin.math.sin

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

    // ── Row entrance animations: only rows that are NEW animate ──────────────
    // Every tile/row used to be born at alpha=0 with a staggered fade. A rebuild fires per
    // keystroke AND per async source (~6-9 per query, landing 300-3000ms apart), so rows were
    // continuously restarted at alpha=0 and the list spent most of fast typing half-transparent.
    // That was the flicker. Rows that survive a refresh now stay exactly where they are; only rows
    // absent from the previous render animate in. Same trick the host/glass plate already got.
    // Keyed per surface, for the same reason lastSignatures is: library and unfolded can both be
    // live at once. A single shared set meant one surface's build marked every row "seen", so the
    // other surface's first appearance animated nothing — and its reset re-animated the other
    // surface's whole list from alpha 0, which is the flicker this exists to kill.
    private val renderedRowKeys = mutableMapOf<String, Set<String>>()
    private var pendingRowKeys = mutableSetOf<String>()
    private var buildingSurface = ""

    private fun rowKey(result: SearchResult) = "${result.kind}|${result.title}|${result.subtitle}"

    /** Records [result] as rendered on the surface being built, and reports whether it's new. */
    private fun claimRowEntrance(result: SearchResult): Boolean {
        val key = rowKey(result)
        pendingRowKeys.add(key)
        return key !in renderedRowKeys[buildingSurface].orEmpty()
    }

    /** [surface] is being built from scratch (not swapped in place), so everything on it is
     *  legitimately new and should animate — e.g. search opening. */
    internal fun resetRowEntrances(surface: String) { renderedRowKeys.remove(surface) }

    // ── No-op rebuild guard ──────────────────────────────────────────────────
    // The ~9 async sources land 300-3000ms apart and most don't change what's actually visible
    // (semantic returns nothing new, the web fallback duplicates an existing row, a source resolves
    // to the same card). Each one still tore down and rebuilt the whole tree. Fingerprint what
    // would be rendered and skip the rebuild when it matches the tree already on screen. Cheap
    // because universalSearchResults() is memoized per generation.
    // Keyed per surface. A single shared signature would let one surface's rebuild convince another
    // attached surface that it was already up to date, leaving it showing stale content.
    private val lastSignatures = mutableMapOf<String, String>()

    private fun contentSignature(): String = with(activity) {
        val sb = StringBuilder(256)
        sb.append(query.trim()).append('#')
        val results = universalSearchResults()
        results.forEach { sb.append(rowKey(it)).append(';') }
        // Presentation, not just data: rowKey carries no accent, and a theme applied FROM a result
        // row repaints every row without changing a single title. Without these, applying a theme
        // in place would match the old signature and the repaint would be skipped.
        sb.append('#').append(goKeyColor).append('/').append(activeNeuTokens.mode)
            .append('/').append(searchFontSizePref())
        // Rendered (the predicted-apps grid) but derived from predictContext/Predictor rather than
        // from results, so it can change while everything above is byte-identical.
        val pkgs = results.mapNotNull { it.target?.packageName }.toSet()
        contextSuggestionResults(pkgs)?.let { (label, items) ->
            sb.append('#').append(label)
            items.forEach { sb.append(it.title).append(',') }
        }
        sb.append('#').append(searchCommandPreview()?.hashCode() ?: 0)
        sb.append('#').append(searchSportsCard()?.hashCode() ?: 0)
        sb.append('#').append(searchPlaces()?.hashCode() ?: 0)
        sb.append('#').append(searchPlacesRefinement())
        sb.append('#').append(searchRichAnswer()?.hashCode() ?: 0)
        sb.append('#').append(searchRideOffer()?.hashCode() ?: 0)
        val ai = searchAiAnswer()
        sb.append('#').append(ai?.answer?.hashCode() ?: 0).append('/').append(ai?.loading)
        return sb.toString()
    }

    /**
     * True when nothing [surface] renders has changed since it was last built. Always records the
     * new signature, so callers that rebuild anyway (first appearance) stay in sync. Only safe to
     * act on when that surface is still attached — see refreshWidgetSearchContent.
     */
    internal fun searchContentUnchanged(surface: String): Boolean {
        val sig = contentSignature()
        if (lastSignatures[surface] == sig) return true
        lastSignatures[surface] = sig
        return false
    }

    /** Re-apply a saved scroll offset to a freshly built list. Set before the first draw so the
     *  restore is invisible rather than a visible jump. */
    internal fun restoreScroll(fresh: View, y: Int) {
        if (y <= 0 || fresh !is ScrollView) return
        fresh.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                fresh.viewTreeObserver.removeOnPreDrawListener(this)
                fresh.scrollY = y
                return true
            }
        })
    }

    // True while search results are the interactive surface (docked library search, widget
    // universal search, or unfolded search) with a live query.
    private fun searchResultsInteractive(): Boolean = with(activity) {
        query.isNotBlank() && (libraryOpen || isWidgetUniversalSearchActive() || isUnfoldedInnerLayoutActive())
    }

    // See searchResultTouchActive. Down on the results (not the keyboard) opens a defer window.
    // MUST be called BEFORE the event is dispatched, so the window is already armed when an async
    // source lands mid-touch.
    internal fun trackSearchResultTouchDown(event: MotionEvent) { with(activity) {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            searchResultTouchActive = searchResultsInteractive() &&
                !isInsideKeyboard(event.rawX, event.rawY)
        }
    } }

    /**
     * Closes the defer window and flushes any rebuild held back during the touch.
     *
     * MUST be called AFTER the event has been dispatched. View.onTouchEvent enqueues its own
     * PerformClick on ACTION_UP, and the rebuild's removeView() detaches the row — which cancels
     * any PerformClick still sitting in the queue. Flushing before dispatch therefore posted the
     * rebuild ahead of the click and ate the tap: the first tap only consumed the pending flag and
     * the second one worked, which is what "you have to tap twice" was. Ordering is the whole fix —
     * post the flush after dispatch and the click is already queued in front of it.
     */
    internal fun trackSearchResultTouchUp(event: MotionEvent) { with(activity) {
        val done = event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL
        if (done && searchResultTouchActive) {
            searchResultTouchActive = false
            if (searchResultRefreshPending) {
                searchResultRefreshPending = false
                if (hasContentFrame()) contentFrame.post { flushDeferredSearchRefresh() }
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
        // Decide reuse BEFORE building: on a first appearance every row really is new and should
        // animate; on a refresh the surviving rows must not.
        val reuse = widgetSearchHost?.takeIf { it.parent === area }
        if (reuse == null) resetRowEntrances("widget")
        // Only skip when the surface is still attached; if it isn't, the tree was torn down
        // elsewhere and must be rebuilt regardless of the signature.
        val unchanged = searchContentUnchanged("widget")
        if (reuse != null && unchanged) return
        val keepScroll = (widgetSearchList as? ScrollView)?.scrollY ?: 0
        val fresh = searchResultsList(widgetMode = true, surface = "widget")
        if (glass) padSearchContentForGlass(fresh)
        reuse?.let { host ->
            // In-place swap on refresh — no blur recreation, no re-entrance animation.
            widgetSearchList?.let { host.removeView(it) }
            host.addView(fresh, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            widgetSearchList = fresh
            // A swap resets scrollY to 0 — restore it, or any async source landing yanks a
            // scrolled-down user back to the top.
            restoreScroll(fresh, keepScroll)
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
    // "unfolded-focus", NOT "unfolded": this is the focus canvas (unfoldedFocusContentArea, driven
    // by refreshUnfoldedFocusContent), a different surface with a different lifecycle from the
    // unfolded library content (unfoldedLibraryContentArea, driven by refreshUnfoldedLibraryContent),
    // which owns "unfolded". They're reachable from different routers — refreshSearchResultsUi goes
    // to one, refreshSearchSurfaces to the other — so sharing a key let one surface's build mark
    // every row "seen" and silently suppress the other's entrance animations.
    internal fun unfoldedSearchResultsList(): View =
        unfoldedSearchResultsDashboard(surface = "unfolded-focus")

    private fun unfoldedSearchResultsDashboard(surface: String): View = with(activity) {
        buildingSurface = surface
        pendingRowKeys = mutableSetOf()
        fontScaleMemo = 0f

        val results = universalSearchResults()
        val command = searchCommandPreview()
        val aiInline = searchAiAnswer()
        val rich = searchRichAnswer()
        val sports = searchSportsCard()
        val places = searchPlaces()
        val hero: View? = when {
            sports != null -> sportsScoreCard(sports)
            places != null -> nearbyPlacesCard(places)
            rich != null -> braveRichCard(rich)
            aiInline != null -> searchAiAnswerCard(aiInline)
            else -> null
        }
        val visibleResults = results
        val appResults = visibleResults.filter { it.kind == SearchKind.APP }
        val otherResults = visibleResults.filter { it.kind != SearchKind.APP }

        val dashboard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
            setPadding(0, dp(8), 0, dp(4))

            addView(unfoldedSearchScrollColumn {
                command?.let {
                    addView(searchZoneHeader("Action"))
                    addView(searchCommandCard(it), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(72)).apply {
                        bottomMargin = dp(14)
                    })
                }
                hero?.let {
                    addView(searchZoneHeader("Answer"), zoneHeaderParams())
                    addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dp(14)
                    })
                    searchRideOffer()?.let { ride ->
                        addView(braveRideCard(ride), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            bottomMargin = dp(14)
                        })
                    }
                }
                if (appResults.isNotEmpty()) {
                    addView(searchZoneHeader("Apps"), zoneHeaderParams())
                    addView(searchAppGrid(appResults, columns = 5), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dp(12)
                    })
                }
                if (visibleResults.isEmpty() && command == null && hero == null) {
                    addView(TextView(context).apply {
                        text = "No results for \"$query\""
                        textSize = 14f * searchFontScale()
                        gravity = Gravity.CENTER
                        setTextColor(activeNeuTokens.inkDim)
                        includeFontPadding = false
                    }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(220)))
                }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.18f).apply {
                rightMargin = dp(14)
            })

            addView(unfoldedSearchDivider(), LinearLayout.LayoutParams(dp(1), LinearLayout.LayoutParams.MATCH_PARENT).apply {
                topMargin = dp(8)
                bottomMargin = dp(8)
            })

            addView(unfoldedSearchScrollColumn {
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
                contextSuggestionResults(results.mapNotNull { it.target?.packageName }.toSet())?.let { (label, items) ->
                    addView(searchZoneHeader(label), zoneHeaderParams().apply { topMargin = dp(8) })
                    addView(searchAppGrid(items, columns = 4), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.92f).apply {
                leftMargin = dp(14)
            })
        }
        renderedRowKeys[surface] = pendingRowKeys
        dashboard
    }

    private fun unfoldedSearchScrollColumn(build: LinearLayout.() -> Unit): ScrollView = with(activity) {
        ScrollView(this).apply {
            clipToPadding = false
            isVerticalScrollBarEnabled = false
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(4), 0, dp(16))
                build()
            }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun unfoldedSearchDivider(): View = with(activity) {
        View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
                Color.TRANSPARENT,
                adjustAlpha(activeNeuTokens.ink, if (activeNeuTokens.mode == NeuMode.LIGHT) 0.16f else 0.10f),
                Color.TRANSPARENT
            ))
        }
    }

    // Grid and list surfaces now share one presentation — the three-zone layout. (Previously this
    // rendered 2-column "bento" cards with kind pills; unified so the redesign shows on every surface,
    // wide/unfolded included.) [surface] must name the caller's surface so row-entrance diffing and
    // the rebuild signature stay per-surface.
    // No default for [surface] on purpose: a silent default is how the unfolded canvas ended up
    // sharing the docked library's entrance state. Callers must name their surface.
    internal fun searchResultsGrid(surface: String): View =
        searchResultsList(widgetMode = false, surface = surface)

    // Universal search presentation — three zones, read top-down, hugging the docked search bar:
    //   ZONE 1  one instant answer (score card > rich answer > AI), never a stack of cards
    //   ZONE 2  matching apps as bare launcher icons (icon-pack honoured), no boxes/plates/tags
    //   ZONE 3  everything else in one list, grouped under quiet headers, no per-row kind pills
    private fun searchResultsList(widgetMode: Boolean = false, surface: String): View = with(activity) { ScrollView(this).apply {
        clipToPadding = false
        isVerticalScrollBarEnabled = false   // search results scroll cleanly — no scrollbar track
        buildingSurface = surface            // which surface's entrance state claimRowEntrance reads
        pendingRowKeys = mutableSetOf()      // collected by claimRowEntrance during the build below
        fontScaleMemo = 0f                   // re-read the pref once for this build, then reuse
        val results = universalSearchResults()
        val command = searchCommandPreview()
        val aiInline = searchAiAnswer()
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
            val places = searchPlaces()
            val hero: View? = when {
                sports != null -> sportsScoreCard(sports)
                // "restaurants near me" — Places answers local intent better than a Brave POI.
                places != null -> nearbyPlacesCard(places)
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

            // SearchRouter already decides whether an "Ask AI" row belongs here — when the answer is
            // rendering inline it isn't added at all, so there's nothing left to filter out.
            val visibleResults = results
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

            if (visibleResults.isNotEmpty() || hero != null) {
                addView(searchHoldHintFooter(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(10)
                })
            }
        })
        // Everything built above has claimed its entrance; these are the rows on this surface now.
        renderedRowKeys[surface] = pendingRowKeys
    } }

    // One quiet line under the results. Wording is a promise, not an apology: tap is the way,
    // hold is the certainty.
    private fun searchHoldHintFooter(): View = with(activity) {
        TextView(this).apply {
            text = "Tap to open · press & hold if a tap doesn't take"
            textSize = 9.5f * searchFontScale()
            gravity = Gravity.CENTER
            letterSpacing = 0.04f
            setTextColor(activeNeuTokens.inkFaint)
            includeFontPadding = false
        }
    }

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
        // "Open" leads: hold is also the guaranteed way to action a result, so the launch
        // must be the first thing under the finger — pinning is the secondary errand here.
        popup.menu.add(0, 3, 0, "Open ${result.title}")
        popup.menu.add(0, 1, 1, if (onDock) "Remove from dock" else "Pin to dock")
        if (space != null) {
            popup.menu.add(0, 2, 2,
                if (pinned) "Unpin from ${space.emoji} ${space.name} board" else "Pin to ${space.emoji} ${space.name} board")
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                3 -> openSearchResult(result)
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
    // Memoized per render: called once per TextView (~30-40 times a build), and the default
    // argument made each of those a SharedPreferences lookup. Reset at the top of searchResultsList,
    // so a font-size change still takes effect on the next build.
    private var fontScaleMemo = 0f

    private fun searchFontScale(): Float {
        if (fontScaleMemo <= 0f) {
            val progress = activity.searchFontSizePref().coerceIn(0, 100)
            fontScaleMemo = 0.90f + progress / 100f * 0.50f   // 0→0.90x, 50→1.15x (Medium), 100→1.40x
        }
        return fontScaleMemo
    }

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
        val entering = claimRowEntrance(result)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            setPadding(dp(3), dp(4), dp(3), dp(2))
            if (entering) {
                alpha = 0f
                translationY = dp(8).toFloat()
                postDelayed({
                    animate().alpha(1f).translationY(0f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
                }, (index * 28L).coerceAtMost(240L))
            }
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
            } else {
                // Hold = same action as tap: a guaranteed way to fire the result even if a tap
                // gets eaten by a scroll, a refresh, or a gesture detector upstream.
                val hold = result.longAction ?: { openSearchResult(result) }
                setOnLongClickListener { haptic(this); hold(); true }
            }
        }
    } }

    // ZONE 3 — one clean row: icon + title + secondary line. No kind pill (the header names it).
    private fun searchGroupedRow(result: SearchResult, index: Int): View { with(activity) {
        val entering = claimRowEntrance(result)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            setPadding(dp(6), dp(7), dp(6), dp(7))
            if (entering) {
                alpha = 0f
                translationY = dp(6).toFloat()
                postDelayed({
                    animate().alpha(1f).translationY(0f).setDuration(200).setInterpolator(DecelerateInterpolator()).start()
                }, (index * 24L).coerceAtMost(220L))
            }
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
            // Hold = same action as tap (unless the row has its own long-press behavior).
            val hold = result.longAction ?: { openSearchResult(result) }
            setOnLongClickListener { haptic(this); hold(); true }
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

    /**
     * Whether a card fetched for [cached] is still worth showing while [live] loads.
     *
     * These used to demand exact equality, so typing one more character blanked the hero card and
     * it only came back after the debounce (300-450ms) PLUS a network round-trip — the card
     * visibly popping out and back on every keystroke. Refining a query ("miami heat" ->
     * "miami heat score") keeps the answer on screen while the sharper one loads. Diverging
     * queries ("weather paris" -> "weather london") still drop it immediately, so a stale answer
     * never sits under a query it doesn't answer. This is the pattern searchPlaces() already used.
     */
    private fun cardStillRelevant(cached: String, live: String): Boolean {
        if (cached.isBlank() || live.isBlank()) return false
        return cached == live || live.startsWith(cached) || cached.startsWith(live)
    }

    /** The Brave rich answer for the live query — kept while a refinement of it loads. */
    internal fun searchRichAnswer(): BraveSearchApi.RichAnswer? = with(activity) {
        braveRichAnswer?.takeIf { cardStillRelevant(braveRichQuery, query.trim()) }
    }

    /** The ride offer to render under the landmark hero card — set only when the answer is a
     *  landmark and the user is within ride range of it (see resolveLandmarkRide). */
    private fun searchRideOffer(): MainActivity.RideOffer? = with(activity) {
        braveRideOffer?.takeIf {
            cardStillRelevant(braveRichQuery, query.trim()) && braveRichAnswer?.vertical == "landmark"
        }
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
        sportsCard?.takeIf { cardStillRelevant(sportsQuery, query.trim()) }
    }

    /** Nearby-places hero card (Google Places): one row per place with rating, price, open-now
     *  and distance; tapping a row opens that exact place in Google Maps by place_id. The
     *  "Google Maps" footer is REQUIRED attribution under Places' terms — don't drop it. */
    private fun nearbyPlacesCard(places: List<PlacesApi.Place>): View { with(activity) {
        val accent = 0xFF4285F4.toInt()   // Google blue
        val here = AgenticLocation.lastKnown(this)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(11))
            background = searchCardBackground(SearchKind.ANSWER, true, 18)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply {
                    text = "📍"
                    gravity = Gravity.CENTER
                    textSize = 13f
                    includeFontPadding = false
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(adjustAlpha(accent, 0.14f))
                    }
                }, LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(9) })
                addView(TextView(context).apply {
                    val intent = searchPlaceIntent()
                    val sel = searchPlacesRefinement()
                    text = listOfNotNull(intent?.label ?: "Nearby", sel).joinToString(" · ")
                    textSize = 13f * searchFontScale()
                    setTextColor(activeNeuTokens.inkDim)
                    includeFontPadding = false
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(30)))

            // The follow-up question, answered instantly: a broad intent ("food") offers
            // refinements as chips instead of waiting on a model to ask what you're in the mood for.
            // They behave as TABS — they stay put while a selection loads, and only the rows swap.
            val chips = searchPlaceIntent()?.chips.orEmpty()
            val selected = searchPlacesRefinement()
            if (chips.isNotEmpty()) {
                addView(android.widget.HorizontalScrollView(context).apply {
                    isHorizontalScrollBarEnabled = false
                    clipToPadding = false
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        chips.forEach { chip ->
                            val on = chip == selected
                            addView(TextView(context).apply {
                                text = chip
                                textSize = 11.5f * searchFontScale()
                                setTextColor(if (on) activeNeuTokens.base else activeNeuTokens.ink)
                                typeface = if (on) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.DEFAULT
                                includeFontPadding = false
                                isClickable = true
                                gravity = Gravity.CENTER
                                setPadding(dp(12), dp(6), dp(12), dp(6))
                                background = if (on) GradientDrawable().apply {
                                    cornerRadius = dp(99).toFloat()
                                    setColor(activeNeuTokens.ink)
                                } else Neu.drawable(activeNeuTokens, dp(99).toFloat(), NeuLevel.RAISED_SM)
                                setOnClickListener { haptic(this); applySearchChip(chip) }
                            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                                if (chip != chips.first()) marginStart = dp(6)
                            })
                        }
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(2); bottomMargin = dp(2)
                })
            }

            places.take(4).forEachIndexed { index, place ->
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    isClickable = true
                    setPadding(dp(11), dp(9), dp(11), dp(9))
                    background = Neu.drawable(activeNeuTokens, dp(12).toFloat(), NeuLevel.PRESSED_SM)
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        addView(TextView(context).apply {
                            text = place.name
                            textSize = 14f * searchFontScale()
                            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                            setTextColor(activeNeuTokens.ink)
                            includeFontPadding = false
                            maxLines = 1
                            ellipsize = android.text.TextUtils.TruncateAt.END
                        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                        place.rating?.let { r ->
                            addView(mono("★ ${String.format(Locale.US, "%.1f", r)}", 10f, 0xFFF5A623.toInt()).apply {
                                setPadding(dp(6), 0, 0, 0)
                            })
                        }
                    }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    // Secondary line: open/closed · price · distance · type — only what we have.
                    val bits = mutableListOf<String>()
                    place.openNow?.let { bits.add(if (it) "Open" else "Closed") }
                    if (place.priceLevel.isNotBlank()) bits.add(place.priceLevel)
                    if (here != null && place.lat != null && place.lng != null) {
                        val out = FloatArray(1)
                        android.location.Location.distanceBetween(here.latitude, here.longitude, place.lat, place.lng, out)
                        bits.add(if (out[0] < 1000) "${out[0].toInt()} m" else String.format(Locale.US, "%.1f km", out[0] / 1000f))
                    }
                    place.type.takeIf { it.isNotBlank() }?.let { bits.add(it) }
                    addView(TextView(context).apply {
                        text = bits.joinToString("  ·  ")
                        textSize = 11f * searchFontScale()
                        setTextColor(if (place.openNow == false) activeNeuTokens.inkFaint else activeNeuTokens.inkDim)
                        includeFontPadding = false
                        setPadding(0, dp(2), 0, 0)
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    setOnClickListener {
                        haptic(this)
                        openUrlDirectly(place.mapsUrl())
                    }
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = if (index == 0) dp(4) else dp(6)
                })
            }
            // Required by the Places terms when rendering outside a Google Map.
            addView(mono("DATA · ${PlacesApi.ATTRIBUTION.uppercase(Locale.US)}", 7.5f, activeNeuTokens.inkFaint).apply {
                letterSpacing = 0.1f
                setPadding(0, dp(9), 0, 0)
            })
        }
    } }

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

    /**
     * The inline answer, and the whole point of it: the answer IS the card. No echoed prompt (the
     * user just typed it, it's in the bar two inches up), no avatar, no chrome competing with the
     * text. Not clickable either — this used to open the AI pane, which is exactly the "takes me to
     * a popup" behaviour the band is meant to replace. The answer is the destination.
     * Long-press copies it.
     */
    private fun searchAiAnswerCard(state: AiAnswerState): View { with(activity) {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(13), dp(14), dp(12))
            background = searchCardBackground(SearchKind.AI, true, 16)
            if (state.loading) {
                addView(AnswerShimmerView(this@apply.context), LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(34)))
            } else {
                addView(TextView(context).apply {
                    text = state.answer
                    textSize = 15f * searchFontScale()
                    setTextColor(activeNeuTokens.ink)
                    includeFontPadding = false
                    setLineSpacing(dp(5).toFloat(), 1f)
                    setTextIsSelectable(false)
                    isLongClickable = true
                    setOnLongClickListener {
                        haptic(this)
                        val cb = activity.getSystemService(android.content.ClipboardManager::class.java)
                        cb?.setPrimaryClip(android.content.ClipData.newPlainText("answer", state.answer))
                        Toast.makeText(activity, "Answer copied", Toast.LENGTH_SHORT).show()
                        true
                    }
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            // Quiet attribution. Says which brain answered, so an on-device answer is legible as one.
            addView(mono(
                if (state.loading) "THINKING" else if (GeminiClient.localReady()) "ON-DEVICE" else "TECLAS AI",
                8f, Neu.PURPLE
            ).apply {
                letterSpacing = 0.14f
                setPadding(0, dp(10), 0, 0)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    } }

    /** Three-dot pulse while the model generates — a placeholder with the answer's own rhythm,
     *  rather than a spinner or a jumping "Thinking..." string that reflows the band. */
    private inner class AnswerShimmerView(ctx: android.content.Context) : View(ctx) {
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Neu.PURPLE }
        private val startedAt = System.currentTimeMillis()
        private val radius = activity.dp(3).toFloat()
        private val gap = activity.dp(11).toFloat()
        private val left = activity.dp(2).toFloat()

        override fun onDraw(canvas: Canvas) {
            val t = (System.currentTimeMillis() - startedAt) / 420.0
            val cy = height / 2f
            repeat(3) { i ->
                val phase = (sin(t - i * 0.6) + 1.0) / 2.0
                dot.alpha = (70 + 150 * phase).toInt().coerceIn(0, 255)
                canvas.drawCircle(left + radius + i * gap, cy, radius, dot)
            }
            postInvalidateOnAnimation()
        }
    }
}
