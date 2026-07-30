package com.fran.teclas.cue

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.fran.teclas.Neu
import com.fran.teclas.NeuLevel
import com.fran.teclas.NeuTokens
import com.fran.teclas.TECLAS_THEME_MODE_PREF
import com.fran.teclas.TECLAS_THEME_MODE_SYSTEM
import com.fran.teclas.mono
import com.fran.teclas.resolveTeclasNeuTokens
import java.util.concurrent.Executors

/**
 * Connect the launcher to Cue.
 *
 * You prove who you are; the server decides what that means. Cue resolves the
 * organization from the staff row behind the authenticated user on every
 * request, so there is nothing to type here beyond credentials.
 *
 * The one exception is someone who holds staff rows at more than one clinic.
 * They get a picker — but it only ever selects among memberships Cue already
 * confirmed, and the server re-validates the choice on every request. Until it
 * is resolved, Cue flags the scope as a guess and this screen says so.
 *
 * Two layout rules this screen learned the hard way, both of which rendered it
 * as an empty rectangle:
 *
 *  - Every child is added with an EXPLICIT height. A bare `View` measured with
 *    WRAP_CONTENT does NOT collapse to its minimum — `View.getDefaultSize()`
 *    returns the full AT_MOST size — so a spacer added that way consumes the
 *    entire viewport and pushes the form off the bottom of the screen.
 *  - The root sits on a software layer. `NeuDrawable`'s shadows are drawn with
 *    a `BlurMaskFilter`, which a hardware-accelerated canvas ignores, flattening
 *    every surface. MainActivity does the same for the daily brief.
 *
 * FLAG_SECURE because the session this establishes leads to patient names.
 */
internal class CueSignInActivity : Activity() {

    private val worker = Executors.newSingleThreadExecutor()

    private lateinit var tokens: NeuTokens
    private var accent: Int = DEFAULT_ACCENT

    private var status: TextView? = null
    private var submit: TextView? = null
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        // The scroll host is here so the form stays reachable with the keyboard up (see render),
        // which only happens if the window actually resizes for it.
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        val prefs = getSharedPreferences(TECLAS_PREFS, MODE_PRIVATE)
        // Same resolver the launcher and IME use, so this screen follows the
        // user's theme — including "system" — instead of guessing dark. The go
        // key accent is theirs too, so this screen matches the launcher they set up.
        tokens = resolveTeclasNeuTokens(prefs.getString(TECLAS_THEME_MODE_PREF, TECLAS_THEME_MODE_SYSTEM))
        accent = prefs.getInt(GO_KEY_COLOR_PREF, DEFAULT_ACCENT)

        window.setBackgroundDrawable(ColorDrawable(tokens.base))
        showCurrentState()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun showCurrentState() {
        val identity = CueSession.identity(this)
        render(if (identity != null && CueSession.isSignedIn(this)) connectedView(identity) else signInView())
    }

    /**
     * Mount a page. The scroll host keeps the form reachable when the keyboard
     * is up or the display is short, and centers it when there is room to spare.
     */
    private fun render(page: View) {
        val host = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            setBackgroundColor(tokens.base)
            addView(page, ViewGroup.LayoutParams(MATCH, WRAP))
            // NeuDrawable's shadows are BlurMaskFilter-based; a hardware canvas
            // drops them silently and every surface goes flat.
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        }
        setContentView(host)
    }

    // ── Signed out ───────────────────────────────────────────────────────────

    private fun signInView(): View {
        val email = field(
            "WORK EMAIL", "you@yourclinic.com",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
        )
        val password = field(
            "PASSWORD", "Your Cue password",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
        ).apply {
            transformationMethod = PasswordTransformationMethod.getInstance()
            imeOptions = EditorInfo.IME_ACTION_GO
        }

        status = statusLine()
        val connect = primaryButton("Connect") {
            attemptSignIn(email.text.toString().trim(), password.text.toString())
        }
        submit = connect
        password.setOnEditorActionListener { _, _, _ ->
            attemptSignIn(email.text.toString().trim(), password.text.toString())
            true
        }

        return page(
            mark("◈"),
            gap(22),
            title("Connect to Cue"),
            gap(9),
            lede("Sign in as yourself.\nYour clinic comes from your staff record."),
            gap(28),
            fieldBlock(email),
            gap(12),
            fieldBlock(password),
            gap(20),
            connect,
            gap(12),
            status!!,
            gap(30),
            footnote(
                if (CueSession.isConfigured) "NO CLINIC TO TYPE · NO ORG ID INVENTED\nSCOPE IS RESOLVED SERVER-SIDE, EVERY REQUEST"
                else "THIS BUILD HAS NO CUE ENDPOINT\nSET cue.api.base.url IN app/cue.properties",
            ),
        )
    }

    private fun attemptSignIn(email: String, password: String) {
        if (busy) return
        if (!CueSession.isConfigured) return report("This build has no Cue endpoint configured.", error = true)
        if (email.isBlank() || password.isBlank()) return report("Enter your work email and password.", error = true)

        busy = true
        submit?.alpha = 0.55f
        report("Connecting…")
        worker.execute {
            val failure = CueSession.signIn(this, email, password)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                busy = false
                submit?.alpha = 1f
                if (failure != null) {
                    report(failure, error = true)
                    return@runOnUiThread
                }
                // Let the search surface see the session without a cold start.
                CueBridge.onSessionChanged(this)
                val identity = CueSession.identity(this)
                if (identity != null) render(connectedView(identity)) else report("Connected.")
            }
        }
    }

    private fun report(message: String, error: Boolean = false) {
        status?.apply {
            text = message
            setTextColor(if (error) Neu.ACCENT else tokens.inkDim)
            visibility = View.VISIBLE
        }
    }

    // ── Connected ────────────────────────────────────────────────────────────

    /**
     * The resolved identity, shown as proof rather than a success toast — the
     * clinic named here was never typed by anyone.
     */
    private fun connectedView(identity: CueIdentity): View {
        val children = mutableListOf(
            mark("✓"),
            gap(22),
            title(identity.organizationName),
            gap(9),
            lede(
                if (identity.ambiguous) "Connected as ${identity.displayName}.\nYou work at more than one clinic — pick one."
                else "Connected as ${identity.displayName}.",
            ),
            gap(26),
            identityCard(identity),
        )

        if (identity.organizations.size > 1) {
            children += gap(24)
            children += sectionLabel("CLINIC")
            children += gap(11)
            identity.organizations.forEach { organization ->
                children += clinicRow(organization, organization.id == identity.organizationId)
                children += gap(8)
            }
        }

        status = statusLine()
        children += gap(24)
        children += primaryButton("Start searching") { finish() }
        children += gap(10)
        children += ghostButton("Sign out", Neu.ACCENT) {
            CueBridge.signOut(this)
            render(signInView())
        }
        children += gap(12)
        children += status!!
        children += gap(22)
        children += footnote("YOU NEVER PICKED THIS CLINIC\nCUE READ IT OFF YOUR STAFF ROW")

        return page(*children.toTypedArray())
    }

    private fun identityCard(identity: CueIdentity): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(17), dp(16), dp(17), dp(16))
        background = Neu.drawable(tokens, dp(16).toFloat(), NeuLevel.RAISED)
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        addView(TextView(context).apply {
            text = identity.displayName
            textSize = 17f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(tokens.ink)
            includeFontPadding = false
        }, row())
        addView(mono(identity.role.uppercase(), 9f, accent).apply {
            letterSpacing = 0.1f
            setPadding(0, dp(6), 0, 0)
        }, row())
        addView(detail("STAFF ID", identity.staffId.ifBlank { "—" }), row())
        addView(detail("PERMISSIONS", "${identity.permissions.size} grants"), row())
    }

    private fun detail(label: String, value: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(13), 0, 0)
        addView(mono(label, 8f, tokens.inkFaint).apply { letterSpacing = 0.12f }, row())
        addView(TextView(context).apply {
            text = value
            textSize = 13f
            setTextColor(tokens.ink)
            includeFontPadding = false
            setPadding(0, dp(3), 0, 0)
        }, row())
    }

    /**
     * One clinic you could act in. Only shown when there is a real choice —
     * until an ambiguous scope is resolved, every record the launcher shows was
     * bounded by a server-side default, and a homescreen has no chrome to say so.
     */
    private fun clinicRow(organization: CueOrganization, chosen: Boolean): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = Neu.drawable(tokens, dp(13).toFloat(), if (chosen) NeuLevel.PRESSED else NeuLevel.RAISED)
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = organization.name
                textSize = 14f
                setTextColor(if (chosen) accent else tokens.ink)
                includeFontPadding = false
            }, row())
            addView(mono(organization.role.uppercase(), 8f, tokens.inkFaint).apply {
                letterSpacing = 0.1f
                setPadding(0, dp(4), 0, 0)
            }, row())
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(mono(if (chosen) "ACTIVE" else "SWITCH", 8f, if (chosen) accent else tokens.inkFaint).apply {
            letterSpacing = 0.12f
        }, LinearLayout.LayoutParams(WRAP, WRAP))
        if (!chosen) {
            isClickable = true
            setOnClickListener { switchOrganization(organization) }
        }
    }

    private fun switchOrganization(organization: CueOrganization) {
        if (busy) return
        busy = true
        report("Switching to ${organization.name}…")
        worker.execute {
            val failure = CueSession.selectOrganization(this, organization.id)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                busy = false
                // Drop cards scoped to the previous clinic. The session stays —
                // switching clinics is not signing out.
                CueBridge.clearCache()
                CueBridge.onSessionChanged(this)
                val identity = CueSession.identity(this)
                when {
                    failure != null -> report(failure, error = true)
                    identity != null -> render(connectedView(identity))
                }
            }
        }
    }

    // ── Building blocks ──────────────────────────────────────────────────────

    /**
     * A page. Every child arrives with its own LayoutParams, so the column can
     * never be blown apart by a WRAP_CONTENT child that measures to the full
     * viewport — the bug that once rendered this screen as an empty rectangle.
     */
    private fun page(vararg children: View): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(30), dp(40), dp(30), dp(40))
        children.forEach { child ->
            addView(child, child.layoutParams ?: LinearLayout.LayoutParams(MATCH, WRAP))
        }
    }

    /** Fixed vertical space. Explicit height — never WRAP_CONTENT. */
    private fun gap(height: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(height))
    }

    private fun mark(glyph: String): View = TextView(this).apply {
        text = glyph
        gravity = Gravity.CENTER
        textSize = 25f
        setTextColor(accent)
        includeFontPadding = false
        background = Neu.drawable(tokens, dp(20).toFloat(), NeuLevel.RAISED)
        layoutParams = LinearLayout.LayoutParams(dp(64), dp(64)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        }
    }

    private fun title(value: String): View = TextView(this).apply {
        text = value
        textSize = 23f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setTextColor(tokens.ink)
        gravity = Gravity.CENTER
        includeFontPadding = false
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
    }

    private fun lede(value: String): View = TextView(this).apply {
        text = value
        textSize = 13.5f
        setTextColor(tokens.inkDim)
        gravity = Gravity.CENTER
        includeFontPadding = false
        setLineSpacing(dp(5).toFloat(), 1f)
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
    }

    private fun footnote(value: String): View = mono(value, 8.5f, tokens.inkFaint).apply {
        gravity = Gravity.CENTER
        letterSpacing = 0.12f
        setLineSpacing(dp(5).toFloat(), 1f)
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
    }

    private fun sectionLabel(value: String): View = mono(value, 8.5f, tokens.inkFaint).apply {
        letterSpacing = 0.14f
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
    }

    private fun statusLine(): TextView = TextView(this).apply {
        textSize = 12.5f
        setTextColor(tokens.inkDim)
        gravity = Gravity.CENTER
        includeFontPadding = false
        visibility = View.GONE
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
    }

    /** Label above the well, so a field reads as a record rather than a form box. */
    private fun fieldBlock(input: EditText): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        addView(mono(input.tag as String, 8f, tokens.inkFaint).apply {
            letterSpacing = 0.13f
            setPadding(dp(2), 0, 0, dp(8))
        }, row())
        addView(input, LinearLayout.LayoutParams(MATCH, dp(54)))
    }

    private fun field(label: String, hint: String, type: Int): EditText = EditText(this).apply {
        tag = label
        this.hint = hint
        inputType = type
        textSize = 15f
        setTextColor(tokens.ink)
        setHintTextColor(tokens.inkFaint)
        background = Neu.drawable(tokens, dp(14).toFloat(), NeuLevel.PRESSED)
        setPadding(dp(16), 0, dp(16), 0)
        gravity = Gravity.CENTER_VERTICAL
        includeFontPadding = false
        isSingleLine = true
    }

    private fun primaryButton(label: String, onClick: () -> Unit): TextView =
        mono(label.uppercase(), 10.5f, contrastOn(accent)).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.16f
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(accent)
            }
            isClickable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(54))
        }

    private fun ghostButton(label: String, color: Int, onClick: () -> Unit): TextView =
        mono(label.uppercase(), 9.5f, color).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.14f
            background = Neu.drawable(tokens, dp(14).toFloat(), NeuLevel.RAISED)
            isClickable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(50))
        }

    private fun row() = LinearLayout.LayoutParams(MATCH, WRAP)

    /** Readable label color on an arbitrary accent — the go key is user-chosen. */
    private fun contrastOn(color: Int): Int {
        val luminance = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255.0
        return if (luminance > 0.6) DARK_ON_ACCENT else Color.WHITE
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val TECLAS_PREFS = "teclas"
        const val GO_KEY_COLOR_PREF = "go_key_color"
        const val DARK_ON_ACCENT = 0xFF160D28.toInt()
        val DEFAULT_ACCENT = Color.parseColor("#C9A7FF")
    }
}
