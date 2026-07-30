package com.fran.teclas.cue

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
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
 * Sign in to Cue.
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
 * FLAG_SECURE because the next screen after this one shows patient names.
 */
internal class CueSignInActivity : Activity() {

    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var tokens: NeuTokens
    private lateinit var status: TextView
    private lateinit var submit: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        // Same resolver the launcher and IME use, so this screen follows the
        // user's theme — including "system" — instead of guessing dark.
        tokens = resolveTeclasNeuTokens(
            getSharedPreferences("teclas", MODE_PRIVATE)
                .getString(TECLAS_THEME_MODE_PREF, TECLAS_THEME_MODE_SYSTEM),
        )

        val existing = CueSession.identity(this)
        setContentView(if (existing != null && CueSession.isSignedIn(this)) signedInView(existing) else signInView())
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    // ── Signed out ───────────────────────────────────────────────────────────

    private fun signInView(): View {
        val email = field("Work email", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val password = field("Password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)

        status = TextView(this).apply {
            textSize = 11.5f
            setTextColor(tokens.inkDim)
            gravity = Gravity.CENTER
            includeFontPadding = false
        }

        submit = button("Sign in", tokens.ink) {
            val address = email.text.toString().trim()
            val secret = password.text.toString()
            when {
                !CueSession.isConfigured -> report("Add your Cue endpoint to app/cue.properties first.")
                address.isBlank() || secret.isBlank() -> report("Enter your work email and password.")
                else -> attemptSignIn(address, secret)
            }
        }

        return page(
            heading("Connect to Cue"),
            caption("SIGN IN AS YOURSELF\nYOUR CLINIC COMES FROM YOUR STAFF RECORD"),
            spacer(18),
            email, spacer(9), password, spacer(14),
            submit, spacer(12), status,
            spacer(20),
            caption(
                if (CueSession.isConfigured) "NO CLINIC TO TYPE · NO ORG ID INVENTED\nSCOPE IS RESOLVED SERVER-SIDE, EVERY REQUEST"
                else "NOT CONFIGURED — SEE app/cue.properties.example",
            ),
        )
    }

    private fun attemptSignIn(email: String, password: String) {
        submit.isEnabled = false
        report("Signing in…")
        worker.execute {
            val failure = CueSession.signIn(this, email, password)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                submit.isEnabled = true
                if (failure != null) {
                    report(failure)
                } else {
                    // Let the search surface see the new session without waiting
                    // for the next cold start.
                    CueBridge.onSessionChanged(this)
                    val identity = CueSession.identity(this)
                    if (identity != null) setContentView(signedInView(identity)) else report("Signed in.")
                }
            }
        }
    }

    private fun report(message: String) {
        if (::status.isInitialized) status.text = message
    }

    // ── Signed in ────────────────────────────────────────────────────────────

    /**
     * The resolved identity, shown as proof rather than a success toast — the
     * organization on this screen was never typed by anyone.
     */
    private fun signedInView(identity: CueIdentity): View = page(
        caption("CONNECTED · GET /api/native/v1/me"),
        spacer(14),
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(14), dp(15), dp(14))
            background = Neu.drawable(tokens, dp(15).toFloat(), NeuLevel.RAISED)
            addView(TextView(context).apply {
                text = identity.displayName
                textSize = 17f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(tokens.ink)
                includeFontPadding = false
            })
            addView(mono(
                listOf(identity.role.uppercase(), identity.staffId.take(12)).filter { it.isNotBlank() }.joinToString(" · "),
                9f, Neu.PURPLE,
            ).apply { setPadding(0, dp(6), 0, 0); letterSpacing = 0.09f })
            addView(detailRow("ORGANIZATION", identity.organizationName))
            addView(detailRow("PERMISSIONS", "${identity.permissions.size} grants"))
        },
        spacer(16),
        caption(
            if (identity.ambiguous) "YOU WORK AT MORE THAN ONE CLINIC\nPICK ONE — THE DEFAULT IS A GUESS"
            else "YOU NEVER PICKED THIS CLINIC\nloadContext() READ IT OFF YOUR STAFF ROW",
        ),
        spacer(if (identity.organizations.size > 1) 12 else 20),
        organizationPicker(identity),
        spacer(if (identity.organizations.size > 1) 16 else 0),
        button("Start searching", tokens.ink) { finish() },
        spacer(9),
        button("Sign out", Neu.ACCENT) {
            CueBridge.signOut(this)
            setContentView(signInView())
        },
    )

    /**
     * Choose which clinic to act in.
     *
     * Only rendered when there is a real choice. When Cue flags the selection as
     * ambiguous this is the most important control on the screen: until it is
     * resolved, every record the launcher shows was scoped by a server-side
     * guess, and on a homescreen there is no chrome to tell you which clinic
     * you are looking at.
     */
    private fun organizationPicker(identity: CueIdentity): View {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (identity.organizations.size <= 1) return container

        container.addView(mono("CLINIC", 8f, tokens.inkFaint).apply {
            letterSpacing = 0.12f
            setPadding(0, 0, 0, dp(8))
        })
        identity.organizations.forEach { organization ->
            val chosen = organization.id == identity.organizationId
            container.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(13), dp(11), dp(13), dp(11))
                background = Neu.drawable(
                    tokens, dp(12).toFloat(), if (chosen) NeuLevel.PRESSED else NeuLevel.RAISED,
                )
                isClickable = true
                addView(TextView(context).apply {
                    text = organization.name
                    textSize = 13.5f
                    setTextColor(if (chosen) Neu.PURPLE else tokens.ink)
                    includeFontPadding = false
                })
                addView(mono(organization.role.uppercase(), 8f, tokens.inkFaint).apply {
                    letterSpacing = 0.1f
                    setPadding(0, dp(4), 0, 0)
                })
                setOnClickListener { switchOrganization(organization.id) }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(7) })
        }
        return container
    }

    private fun switchOrganization(organizationID: String) {
        report("Switching clinic…")
        worker.execute {
            val failure = CueSession.selectOrganization(this, organizationID)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                // Drop cards scoped to the previous clinic — but keep the
                // session; switching clinics is not signing out.
                CueBridge.clearCache()
                CueBridge.onSessionChanged(this)
                val identity = CueSession.identity(this)
                if (failure != null) report(failure)
                else if (identity != null) setContentView(signedInView(identity))
            }
        }
    }

    private fun detailRow(label: String, value: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(11), 0, 0)
        addView(mono(label, 8f, tokens.inkFaint).apply { letterSpacing = 0.12f })
        addView(TextView(context).apply {
            text = value
            textSize = 12.5f
            setTextColor(tokens.ink)
            includeFontPadding = false
            setPadding(0, dp(3), 0, 0)
        })
    }

    // ── Chrome ───────────────────────────────────────────────────────────────

    private fun page(vararg children: View): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(tokens.base)
        setPadding(dp(28), dp(28), dp(28), dp(28))
        children.forEach {
            addView(it, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }
    }

    private fun heading(value: String): View = TextView(this).apply {
        text = value
        textSize = 21f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setTextColor(tokens.ink)
        gravity = Gravity.CENTER
        includeFontPadding = false
    }

    private fun caption(value: String): View = mono(value, 8.5f, tokens.inkFaint).apply {
        gravity = Gravity.CENTER
        letterSpacing = 0.12f
        setLineSpacing(dp(4).toFloat(), 1f)
    }

    private fun field(hint: String, type: Int): EditText = EditText(this).apply {
        this.hint = hint
        inputType = type
        textSize = 14f
        setTextColor(tokens.ink)
        setHintTextColor(tokens.inkFaint)
        background = Neu.drawable(tokens, dp(13).toFloat(), NeuLevel.PRESSED)
        setPadding(dp(14), dp(13), dp(14), dp(13))
        includeFontPadding = false
    }

    private fun button(label: String, color: Int, onClick: () -> Unit): TextView =
        mono(label.uppercase(), 10f, color).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(14), 0, dp(14))
            background = Neu.drawable(tokens, dp(13).toFloat(), NeuLevel.RAISED)
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun spacer(height: Int): View = View(this).apply { minimumHeight = dp(height) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
