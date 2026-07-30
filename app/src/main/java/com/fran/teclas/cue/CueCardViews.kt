package com.fran.teclas.cue

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.fran.teclas.BuildConfig
import com.fran.teclas.MainActivity
import com.fran.teclas.Neu
import com.fran.teclas.NeuLevel
import com.fran.teclas.adjustAlpha
import com.fran.teclas.mono
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Cue records rendered in the launcher's own visual language.
 *
 * These are Teclas neumorphic surfaces — `Neu.drawable` plates, mono labels,
 * the same radii and ink tokens the rest of search already uses — not a port of
 * Cue's web design system. What Cue contributes is the data and the accent
 * spine; the launcher owns how it looks, so a clinic card can sit next to a
 * weather card without either looking pasted in.
 *
 * Every view returned carries its own LayoutParams (including bottom margin),
 * so the caller adds them with a bare `addView(view)`.
 */
internal object CueCardViews {

    fun build(activity: MainActivity, results: CueResults, blurPhi: Boolean): List<View> {
        if (results.mode == "signin") return listOf(header(activity, results), connectCard(activity))
        if (results.mode == "account") return accountViews(activity)

        val hasNothing = !results.loading &&
            results.cards.isEmpty() &&
            results.gate == null &&
            results.error == null
        if (hasNothing) return emptyList()

        val views = mutableListOf<View>()
        views += header(activity, results)

        when {
            results.gate != null -> views += notice(
                activity,
                "Out of scope",
                "This record type needs ${results.gate}. You are signed in as " +
                    (CueSession.identity(activity)?.role?.uppercase() ?: "an unknown role") + ".",
                Neu.AMBER,
            )
            results.error != null -> views += notice(activity, "Cue unavailable", results.error, Neu.ACCENT)
            results.loading && results.cards.isEmpty() -> views += notice(
                activity, "Searching Cue", "Looking up “${results.query}”…",
                activity.activeNeuTokens.inkDim,
            )
        }

        results.summary?.takeIf { results.cards.size > 1 }?.let { views += summaryStrip(activity, it) }

        // A single result is the record you asked for — render it expanded.
        val detail = results.mode == "detail" || results.cards.size == 1
        results.cards.forEach { record -> views += recordCard(activity, record, detail, blurPhi) }
        return views
    }

    // ── Section header ───────────────────────────────────────────────────────

    private fun header(activity: MainActivity, results: CueResults): View {
        with(activity) {
            val accent = results.type?.let { CueTone.forType(it) } ?: goKeyColor
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(mono(
                    "CUE" + (results.type?.let { " · ${CueTone.tagFor(it)}" } ?: ""),
                    10f, accent,
                ).apply { letterSpacing = 0.16f })
                addView(View(context).apply {
                    setBackgroundColor(adjustAlpha(accent, 0.28f))
                }, LinearLayout.LayoutParams(0, dp(1), 1f).apply {
                    marginStart = dp(9)
                    gravity = Gravity.CENTER_VERTICAL
                })
            }

            val header = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(4), dp(4), dp(4), dp(8))
                addView(row, wrapRow())
                // The active sort answers "why is this one first?" — so say it out loud.
                results.sortedBy?.let { sort ->
                    addView(mono("SORTED BY ${sort.uppercase()}", 9f, activeNeuTokens.inkFaint).apply {
                        letterSpacing = 0.13f
                        setPadding(0, dp(5), 0, 0)
                    })
                }
            }
            header.layoutParams = stacked(activity, 0)
            return header
        }
    }

    private fun notice(activity: MainActivity, title: String, body: String, accent: Int): View {
        with(activity) {
            val view = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(13), dp(12), dp(13), dp(12))
                background = Neu.drawable(activeNeuTokens, dp(14).toFloat(), NeuLevel.PRESSED)
                addView(mono(title.uppercase(), 10f, accent).apply { letterSpacing = 0.13f })
                addView(TextView(context).apply {
                    text = body
                    textSize = 13.5f
                    setTextColor(activeNeuTokens.inkDim)
                    setPadding(0, dp(6), 0, 0)
                    includeFontPadding = false
                })
            }
            view.layoutParams = stacked(activity, 9)
            return view
        }
    }

    /** The signed-out state: one tap to the sign-in screen, no settings hunt. */
    private fun connectCard(activity: MainActivity): View {
        with(activity) {
            val accent = goKeyColor
            val view = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                setPadding(dp(13), dp(12), dp(12), dp(12))
                background = SpineDrawable(
                    Neu.drawable(activeNeuTokens, dp(15).toFloat(), NeuLevel.RAISED), accent, dp(3).toFloat(),
                )
                addView(TextView(context).apply {
                    text = "◈"
                    gravity = Gravity.CENTER
                    textSize = 15f
                    setTextColor(accent)
                    background = Neu.drawable(activeNeuTokens, dp(12).toFloat(), NeuLevel.RAISED_SM)
                    includeFontPadding = false
                }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(11) })

                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(context).apply {
                        text = "Connect to Cue"
                        textSize = 16f
                        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                        setTextColor(activeNeuTokens.ink)
                        includeFontPadding = false
                    })
                    addView(mono("SIGN IN TO SEARCH CLINIC RECORDS", 10f, accent).apply {
                        letterSpacing = 0.08f
                        setPadding(0, dp(5), 0, 0)
                    })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

                setOnClickListener {
                    haptic(this)
                    CueBridge.openSignIn(activity)
                }
            }
            view.layoutParams = stacked(activity, 8)
            return view
        }
    }

    /** Who you are signed in as, plus the two controls worth reaching quickly. */
    private fun accountViews(activity: MainActivity): List<View> {
        with(activity) {
            val identity = CueSession.identity(activity)
            val accent = goKeyColor
            val masking = CueBridge.isMaskingEnabled(activity)

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(13), dp(13), dp(12), dp(13))
                background = SpineDrawable(
                    Neu.drawable(activeNeuTokens, dp(15).toFloat(), NeuLevel.RAISED), accent, dp(3).toFloat(),
                )
                addView(TextView(context).apply {
                    text = identity?.displayName ?: "Signed in to Cue"
                    textSize = 17f
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    setTextColor(activeNeuTokens.ink)
                    includeFontPadding = false
                }, wrapRow())
                identity?.let {
                    addView(mono(
                        listOf(it.role.uppercase(), "${it.permissions.size} GRANTS")
                            .filter { part -> part.isNotBlank() }.joinToString(" · "),
                        9f, accent,
                    ).apply {
                        letterSpacing = 0.09f
                        setPadding(0, dp(5), 0, 0)
                    }, wrapRow())
                    addView(fieldGrid(activity, listOf(
                        CueField("Clinic", it.organizationName),
                        CueField("Staff id", it.staffId.ifBlank { "—" }),
                    ) + if (it.ambiguous) listOf(
                        CueField("Scope", "Default guess — pick a clinic"),
                    ) else emptyList(), blur = false, detail = true), wrapRow().apply { topMargin = dp(10) })
                }

                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(mono(
                        if (masking) "MASKING ON" else "MASKING OFF",
                        8f, if (masking) Neu.GREEN else Neu.AMBER,
                    ).apply {
                        letterSpacing = 0.1f
                        gravity = Gravity.CENTER
                        setPadding(dp(9), dp(5), dp(9), dp(5))
                        background = Neu.drawable(activeNeuTokens, dp(8).toFloat(), NeuLevel.RAISED_SM)
                        isClickable = true
                        setOnClickListener {
                            haptic(this)
                            CueBridge.setMaskingEnabled(activity, !masking)
                            activity.render()
                        }
                    }, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { marginEnd = dp(6) })

                    if ((identity?.organizations?.size ?: 0) > 1) {
                        addView(mono("SWITCH CLINIC", 8f, accent).apply {
                            letterSpacing = 0.1f
                            gravity = Gravity.CENTER
                            setPadding(dp(9), dp(5), dp(9), dp(5))
                            background = Neu.drawable(activeNeuTokens, dp(8).toFloat(), NeuLevel.RAISED_SM)
                            isClickable = true
                            setOnClickListener {
                                haptic(this)
                                CueBridge.openSignIn(activity)
                            }
                        }, LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { marginEnd = dp(6) })
                    }

                    addView(mono("SIGN OUT", 8f, Neu.ACCENT).apply {
                        letterSpacing = 0.1f
                        gravity = Gravity.CENTER
                        setPadding(dp(9), dp(5), dp(9), dp(5))
                        background = Neu.drawable(activeNeuTokens, dp(8).toFloat(), NeuLevel.RAISED_SM)
                        isClickable = true
                        setOnClickListener {
                            haptic(this)
                            CueBridge.signOut(activity)
                            activity.render()
                        }
                    }, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    ))
                }, wrapRow().apply { topMargin = dp(11) })
            }
            card.layoutParams = stacked(activity, 8)

            val heading = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(4), dp(4), dp(4), dp(8))
                addView(mono("CUE · ACCOUNT", 8.5f, accent).apply { letterSpacing = 0.16f })
            }
            heading.layoutParams = stacked(activity, 0)
            return listOf(heading, card)
        }
    }

    // ── Summary strip ────────────────────────────────────────────────────────

    /** Count plus a proportional severity bar: the shape of the set before the set. */
    private fun summaryStrip(activity: MainActivity, summary: CueSummary): View {
        with(activity) {
            val view = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(13), dp(11), dp(13), dp(12))
                background = Neu.drawable(activeNeuTokens, dp(14).toFloat(), NeuLevel.PRESSED)

                addView(TextView(context).apply {
                    text = summary.headline
                    textSize = 13.5f
                    setTextColor(activeNeuTokens.ink)
                    includeFontPadding = false
                })

                if (summary.distribution.isNotEmpty()) {
                    val total = summary.distribution.sumOf { it.count }.coerceAtLeast(1)
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        summary.distribution.forEach { bucket ->
                            addView(View(context).apply {
                                background = GradientDrawable().apply {
                                    setColor(CueTone.forTone(bucket.tone))
                                    cornerRadius = dp(3).toFloat()
                                }
                            }, LinearLayout.LayoutParams(0, dp(5), bucket.count.toFloat() / total).apply {
                                marginEnd = dp(2)
                            })
                        }
                    }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(5)).apply {
                        topMargin = dp(9)
                    })

                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        summary.distribution.forEach { bucket ->
                            addView(mono(
                                "${bucket.count} ${bucket.label.uppercase()}",
                                9f, CueTone.forTone(bucket.tone),
                            ).apply {
                                letterSpacing = 0.1f
                                setPadding(0, 0, dp(10), 0)
                            })
                        }
                    }, wrapRow().apply { topMargin = dp(7) })
                }
            }
            view.layoutParams = stacked(activity, 9)
            return view
        }
    }

    // ── Record card ──────────────────────────────────────────────────────────

    private fun recordCard(activity: MainActivity, card: CueCard, detail: Boolean, blurPhi: Boolean): View {
        with(activity) {
            val spine = CueTone.forType(card.type)
            val blur = blurPhi && card.phi
            val radius = dp(15).toFloat()
            val tileSize = if (detail) dp(46) else dp(40)

            val head = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL

                addView(
                    leadingTile(activity, card, spine, detail, blur),
                    LinearLayout.LayoutParams(tileSize, tileSize).apply { marginEnd = dp(11) },
                )

                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(context).apply {
                        text = if (blur) mask(card.title) else card.title
                        textSize = if (detail) 18f else 16f
                        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                        setTextColor(activeNeuTokens.ink)
                        maxLines = if (detail) 2 else 1
                        ellipsize = TextUtils.TruncateAt.END
                        includeFontPadding = false
                    })
                    if (card.subtitle.isNotBlank()) {
                        addView(mono(if (blur) mask(card.subtitle) else card.subtitle, 10.5f, spine).apply {
                            letterSpacing = 0.08f
                            maxLines = if (detail) 2 else 1
                            ellipsize = TextUtils.TruncateAt.END
                            setPadding(0, dp(5), 0, 0)
                        })
                    }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

                addView(mono(CueTone.tagFor(card.type), 8.5f, activeNeuTokens.inkFaint).apply {
                    gravity = Gravity.CENTER
                    letterSpacing = 0.1f
                    setPadding(dp(7), 0, dp(7), 0)
                    background = Neu.drawable(activeNeuTokens, dp(9).toFloat(), NeuLevel.PRESSED_SM)
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(22)).apply {
                    marginStart = dp(8)
                })
            }

            val view = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                isClickable = true
                setPadding(dp(13), if (detail) dp(13) else dp(10), dp(12), if (detail) dp(13) else dp(10))
                background = SpineDrawable(
                    Neu.drawable(activeNeuTokens, radius, NeuLevel.RAISED), spine, dp(3).toFloat(),
                )

                addView(head, wrapRow())

                card.badgeText?.let { badge ->
                    val tone = CueTone.forTone(card.badgeTone)
                    addView(mono(badge.uppercase(), 9.5f, tone).apply {
                        letterSpacing = 0.1f
                        setPadding(dp(7), dp(3), dp(7), dp(3))
                        background = GradientDrawable().apply {
                            cornerRadius = dp(6).toFloat()
                            setColor(Color.TRANSPARENT)
                            setStroke(dp(1), adjustAlpha(tone, 0.65f))
                        }
                    }, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(9) })
                }

                card.meter?.let { addView(meterView(activity, it, detail), wrapRow().apply { topMargin = dp(10) }) }

                val rows = if (detail && card.detail.isNotEmpty()) card.detail else card.fields
                if (rows.isNotEmpty()) {
                    addView(fieldGrid(activity, rows, blur, detail), wrapRow().apply { topMargin = dp(10) })
                }
                if (card.actions.isNotEmpty()) {
                    addView(actionRow(activity, card), wrapRow().apply { topMargin = dp(10) })
                }

                setOnClickListener {
                    haptic(this)
                    // Masked cards spend their first tap on revealing themselves.
                    // Navigating straight into a record you cannot read yet is
                    // the wrong default, and it would leave the mask pointless.
                    if (blur) {
                        CueBridge.revealPhi()
                        activity.render()
                    } else {
                        val primary = card.actions.firstOrNull { it.primary } ?: card.actions.firstOrNull()
                        primary?.let { fire(activity, it) }
                    }
                }
            }
            view.layoutParams = stacked(activity, 8)
            return view
        }
    }

    /** Clinic logo when there is one, otherwise the record's initials on a raised plate. */
    private fun leadingTile(
        activity: MainActivity,
        card: CueCard,
        spine: Int,
        detail: Boolean,
        blur: Boolean,
    ): View {
        with(activity) {
            val plate = Neu.drawable(activeNeuTokens, dp(if (detail) 14 else 12).toFloat(), NeuLevel.RAISED_SM)
            val logo = card.imageUrl
            if (logo != null) {
                return ImageView(this).apply {
                    background = plate
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    setPadding(dp(6), dp(6), dp(6), dp(6))
                    CueImages.into(this, logo)
                }
            }
            return TextView(this).apply {
                text = if (blur) "••" else card.glyph
                gravity = Gravity.CENTER
                textSize = if (detail) 17f else 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(spine)
                background = plate
                includeFontPadding = false
            }
        }
    }

    private fun meterView(activity: MainActivity, meter: CueMeter, detail: Boolean): View {
        with(activity) {
            val tone = CueTone.forTone(meter.tone)
            val percent = meter.percent.coerceIn(0, 100)
            val height = dp(if (detail) 8 else 5)
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(
                        mono(meter.label.uppercase(), 9.5f, activeNeuTokens.inkFaint).apply { letterSpacing = 0.12f },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    )
                    addView(mono("${meter.used} / ${meter.total} · $percent%", 10.5f, activeNeuTokens.inkDim))
                }, wrapRow())
                addView(
                    MeterBar(context, percent, tone, height, activeNeuTokens.baseLo),
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height).apply {
                        topMargin = dp(5)
                    },
                )
            }
        }
    }

    /**
     * Two columns of label/value pairs. This is what makes the company card
     * readable: NPI, tax ID, phone, fax and address land in a scannable block
     * instead of a paragraph.
     */
    private fun fieldGrid(activity: MainActivity, rows: List<CueField>, blur: Boolean, detail: Boolean): View {
        with(activity) {
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(9), 0, 0)
                background = TopRuleDrawable(adjustAlpha(activeNeuTokens.inkFaint, 0.26f), dp(1).toFloat())

                rows.chunked(2).forEach { pair ->
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        pair.forEach { field ->
                            addView(LinearLayout(context).apply {
                                orientation = LinearLayout.VERTICAL
                                addView(mono(field.label.uppercase(), 9.5f, activeNeuTokens.inkFaint).apply {
                                    letterSpacing = 0.12f
                                })
                                addView(TextView(context).apply {
                                    text = if (blur) mask(field.value) else field.value
                                    textSize = if (detail) 15f else 13.5f
                                    setTextColor(activeNeuTokens.ink)
                                    maxLines = 2
                                    ellipsize = TextUtils.TruncateAt.END
                                    includeFontPadding = false
                                    setPadding(0, dp(2), 0, 0)
                                })
                            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                                marginEnd = dp(10)
                            })
                        }
                        // Keep an odd final row aligned to the left column.
                        if (pair.size == 1) addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
                    }, wrapRow().apply { topMargin = dp(7) })
                }
            }
        }
    }

    private fun actionRow(activity: MainActivity, card: CueCard): View {
        with(activity) {
            return LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                card.actions.take(3).forEach { action ->
                    // A write reads in the semantic color of what it does, not
                    // the record's spine — it is a commitment, not a link.
                    val color = when {
                        action.isWrite -> Neu.GREEN
                        action.primary -> CueTone.forType(card.type)
                        else -> activeNeuTokens.inkDim
                    }
                    addView(mono(action.label.uppercase(), 9.5f, color).apply {
                        letterSpacing = 0.1f
                        gravity = Gravity.CENTER
                        setPadding(dp(9), dp(5), dp(9), dp(5))
                        background = Neu.drawable(activeNeuTokens, dp(8).toFloat(), NeuLevel.RAISED_SM)
                        isClickable = true
                        setOnClickListener {
                            haptic(this)
                            fire(activity, action)
                        }
                    }, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { marginEnd = dp(6) })
                }
            }
        }
    }

    // ── Action dispatch ──────────────────────────────────────────────────────

    /**
     * Fire an action.
     *
     * Navigation order is deliberate: the native Cue app first (`cue://`), then
     * the same record on the web (`href`). Only if both are missing does this
     * fall back to the Cue home page — landing a user on a dashboard root when
     * they tapped a specific kiddo is a failure, not a fallback.
     *
     * Actions that WRITE never come through here; see [CueBridge.confirmAndRun].
     */
    private fun fire(activity: MainActivity, action: CueAction) {
        if (action.isWrite) {
            CueBridge.confirmAndRun(activity, action)
            return
        }

        val tel = action.tel
        val geo = action.geo
        when {
            tel != null -> {
                open(activity, Intent(Intent.ACTION_DIAL, Uri.parse("tel:$tel")))
                return
            }
            geo != null -> {
                open(activity, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(geo)}")))
                return
            }
        }

        // cue:// resolves only when the Cue app is installed on this device.
        action.deeplink?.let { link ->
            if (open(activity, Intent(Intent.ACTION_VIEW, Uri.parse(link)))) return
        }
        action.href?.let { href ->
            val url = if (href.startsWith("http")) href else BuildConfig.CUE_API_BASE_URL.trimEnd('/') + href
            if (open(activity, Intent(Intent.ACTION_VIEW, Uri.parse(url)))) return
        }
        if (BuildConfig.CUE_API_BASE_URL.isNotBlank()) {
            open(activity, Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.CUE_API_BASE_URL)))
        }
    }

    /** Start [intent], reporting whether anything on the device handled it. */
    private fun open(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    // ── Bits ─────────────────────────────────────────────────────────────────

    private fun wrapRow() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    /** Full-width params with a bottom gap, applied to every view this object returns. */
    private fun stacked(activity: MainActivity, bottomDp: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { bottomMargin = activity.dp(bottomDp) }

    /** Preserve shape so a masked card still reads as a card, not a redaction. */
    private fun mask(value: String): String =
        value.map { if (it.isWhitespace()) it else '•' }.joinToString("")

    /** Rounded neumorphic plate with a colored spine down the leading edge. */
    private class SpineDrawable(
        private val base: Drawable,
        spine: Int,
        private val width: Float,
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = spine }
        private val path = Path()

        override fun draw(canvas: Canvas) {
            base.bounds = bounds
            base.draw(canvas)
            val inset = bounds.height() * 0.10f
            path.reset()
            path.addRoundRect(
                RectF(bounds.left.toFloat(), bounds.top + inset, bounds.left + width, bounds.bottom - inset),
                floatArrayOf(0f, 0f, width, width, width, width, 0f, 0f),
                Path.Direction.CW,
            )
            canvas.drawPath(path, paint)
        }

        override fun setAlpha(alpha: Int) {
            base.alpha = alpha
            paint.alpha = alpha
        }

        override fun setColorFilter(filter: ColorFilter?) {
            base.colorFilter = filter
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        override fun getPadding(padding: Rect): Boolean = base.getPadding(padding)
    }

    /** A hairline along the top edge — the field block's separator. */
    private class TopRuleDrawable(color: Int, private val thickness: Float) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

        override fun draw(canvas: Canvas) {
            canvas.drawRect(
                bounds.left.toFloat(), bounds.top.toFloat(),
                bounds.right.toFloat(), bounds.top + thickness, paint,
            )
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    /** Recessed track with a filled bar — units consumed, at a glance. */
    private class MeterBar(
        context: Context,
        private val percent: Int,
        fill: Int,
        private val barHeight: Int,
        trackColor: Int,
    ) : View(context) {
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = trackColor }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val radius = barHeight / 2f
            canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), barHeight.toFloat()), radius, radius, trackPaint)
            val filled = width * (percent / 100f)
            if (filled > 0f) {
                canvas.drawRoundRect(
                    RectF(0f, 0f, filled.coerceAtLeast(radius * 2), barHeight.toFloat()),
                    radius, radius, fillPaint,
                )
            }
        }
    }
}

/**
 * Tiny in-memory cache for clinic logos, bounded by how many organizations one
 * launcher can see — in practice, one.
 */
internal object CueImages {
    private val cache = ConcurrentHashMap<String, Bitmap>()
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "cue-images").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    fun into(target: ImageView, url: String) {
        cache[url]?.let {
            target.setImageBitmap(it)
            return
        }
        target.tag = url
        worker.execute {
            val bitmap = runCatching {
                URL(url).openStream().use { BitmapFactory.decodeStream(it) }
            }.getOrNull() ?: return@execute
            cache[url] = bitmap
            // The view may have been recycled onto a different card by now.
            main.post { if (target.tag == url) target.setImageBitmap(bitmap) }
        }
    }
}
