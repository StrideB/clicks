package com.fran.teclas.keyboard

/**
 * An offline calculator for the typing strip: `1420 split 4 ways minus 15%` → `301.75`.
 *
 * Deliberately NOT a model. A language model doing arithmetic is strictly worse than a parser —
 * it can be confidently wrong, and nothing in the output tells you which time it was. Money and
 * measurements are exactly where a plausible-looking wrong answer does damage, so the part that
 * has to be correct is deterministic, exact, instant, and tested. A model can still help by
 * rephrasing a sentence into an expression; evaluating it is this file's job.
 *
 * Percent follows calculator convention rather than literal substitution, because that is what
 * people mean:
 *
 *   `200 - 15%`     → 170      (15% *of 200*, not the number 0.15)
 *   `15% of 200`    → 30
 *   `200 * 15%`     → 30
 *
 * [eval] returns null for anything it cannot evaluate exactly. Null is the common case and the
 * safe one — the caller shows nothing rather than a number that might be invented.
 */
object MathEval {

    private const val MAX_INPUT = 200

    /** Evaluate [input], or null when it is not an arithmetic expression. */
    fun eval(input: String): Double? {
        if (input.length > MAX_INPUT) return null
        val cleaned = normalize(input) ?: return null
        val p = Parser(cleaned)
        val value = p.parseExpr()?.first ?: return null
        p.skipSpace()
        if (!p.atEnd()) return null              // trailing junk means this was never an expression
        return value.takeIf { it.isFinite() }
    }

    /**
     * Turn everyday phrasing into a bare expression, or null if nothing arithmetic is left.
     *
     * Words that are not operators are dropped as labels — "15% discount" is 15%, and the word
     * "discount" is what the number is *for*. That is what makes `1420 split 4 ways minus a 15%
     * discount` work, and it is safe because [eval] still rejects anything that does not parse.
     *
     * The one guard that matters: an expression with no operator left is not a calculation. Without
     * it, "meeting at 5" would strip down to "5" and confidently answer five.
     */
    internal fun normalize(input: String): String? {
        var s = input.lowercase().trim().removeSuffix("?").removeSuffix("=").trim()
        s = s.replace(Regex("""[$€£¥₹]"""), " ")
        // 1,234.50 -> 1234.50 (only between digits, so it never eats a list separator)
        s = s.replace(Regex("""(?<=\d),(?=\d{3}\b)"""), "")
        s = s.replace(Regex("""\bsplit\s+(\d+)\s+ways?\b"""), "/ $1")
        s = s.replace(Regex("""\bdivided\s+by\b"""), "/")
        s = s.replace(Regex("""\bmultiplied\s+by\b"""), "*")
        s = s.replace(Regex("""\b(plus|and)\b"""), "+")
        s = s.replace(Regex("""\b(minus|less)\b"""), "-")
        s = s.replace(Regex("""\b(times|x)\b"""), "*")
        s = s.replace(Regex("""\b(over|per)\b"""), "/")
        s = s.replace(Regex("""\bof\b"""), "*")
        s = s.replace(Regex("""\bpercent\b"""), "%")
        // Whatever words remain are labels, not maths.
        s = s.replace(Regex("""[a-z]+"""), " ").trim()
        if (s.isEmpty()) return null
        if (s.none { it in "+-*/%" }) return null
        return s
    }

    /**
     * Recursive descent. Each parse step returns the value plus whether it was a *bare* percent
     * ("15%" on its own), which is what lets `200 - 15%` subtract 15% of 200 while `200 - 15`
     * subtracts fifteen.
     */
    private class Parser(private val s: String) {
        var i = 0

        fun atEnd(): Boolean = i >= s.length
        fun skipSpace() { while (i < s.length && s[i] == ' ') i++ }
        fun peek(): Char? { skipSpace(); return if (i < s.length) s[i] else null }

        fun parseExpr(): Pair<Double, Boolean>? {
            var acc = parseTerm()?.first ?: return null
            while (true) {
                val c = peek() ?: break
                if (c != '+' && c != '-') break
                i++
                val (rhs, bare) = parseTerm() ?: return null
                // A bare percent is relative to what we have so far; a plain number is absolute.
                val delta = if (bare) acc * rhs else rhs
                acc = if (c == '+') acc + delta else acc - delta
            }
            return acc to false
        }

        fun parseTerm(): Pair<Double, Boolean>? {
            var (acc, bare) = parseUnary() ?: return null
            while (true) {
                val c = peek() ?: break
                if (c != '*' && c != '/') break
                i++
                val rhs = parseUnary()?.first ?: return null
                if (c == '/' && rhs == 0.0) return null   // refuse rather than return Infinity
                acc = if (c == '*') acc * rhs else acc / rhs
                bare = false
            }
            return acc to bare
        }

        fun parseUnary(): Pair<Double, Boolean>? = when (peek()) {
            '-' -> { i++; parseUnary()?.let { (v, p) -> -v to p } }
            '+' -> { i++; parseUnary() }
            else -> parsePrimary()
        }

        fun parsePrimary(): Pair<Double, Boolean>? {
            val c = peek() ?: return null
            var value: Double
            if (c == '(') {
                i++
                value = parseExpr()?.first ?: return null
                if (peek() != ')') return null
                i++
            } else {
                skipSpace()
                val start = i
                while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
                if (i == start) return null
                value = s.substring(start, i).toDoubleOrNull() ?: return null
            }
            var barePercent = false
            if (peek() == '%') { i++; value /= 100.0; barePercent = true }
            return value to barePercent
        }
    }
}
