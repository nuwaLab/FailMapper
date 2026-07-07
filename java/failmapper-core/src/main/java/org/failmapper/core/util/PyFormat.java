package org.failmapper.core.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Python-compatible numeric string formatting (contract I12, N7).
 *
 * <p>Every prompt-embedded {@code f"{x:.2f}"} site in the Python baseline
 * (fa_mcts.py:2846, fa_mcts.py:2912, fa_mcts.py:4014,
 * enhanced_mcts_test_generator.py:3020/3067/3072, verify_bug_with_llm.py:660)
 * MUST render through {@link #f2(double)} — never {@code String.format("%.2f", x)},
 * which rounds HALF_UP and emits a locale-dependent decimal separator (',' in many
 * locales), diverging from CPython byte-for-byte (contract N7 + I12).
 *
 * <p>CPython {@code format(x, '.2f')} formats the EXACT binary value of the double
 * with round-half-to-even. {@code new BigDecimal(double)} is that exact binary
 * expansion, so {@code setScale(2, HALF_EVEN).toPlainString()} reproduces it,
 * locale-independently. Reference midpoint table (asserted in PyFormatTest):
 *
 * <pre>
 *  input       CPython '.2f'   why
 *  83.125   -> "83.12"         exactly representable; ties-to-even -> 2 (even)
 *  83.135   -> "83.14"         binary value is 83.13500000000000512... -> rounds up
 *  0.125    -> "0.12"          exact tie -> 2 (even)
 *  0.375    -> "0.38"          exact tie -> 8 (even)
 *  2.675    -> "2.67"          binary value is 2.67499999999999982... -> rounds down
 *  2.665    -> "2.67"          binary value is 2.66500000000000004... -> rounds up
 *  1.005    -> "1.00"          binary value is 1.00499999999999989... -> rounds down
 *  -0.125   -> "-0.12"         sign preserved, magnitude ties-to-even
 *  -0.001   -> "-0.00"         negative rounding to zero keeps the sign
 *  -0.0     -> "-0.00"         negative zero keeps the sign
 *  100.0    -> "100.00"
 * </pre>
 *
 * <p>Non-finite values follow CPython: {@code inf}, {@code -inf}, {@code nan}
 * (never Java's "Infinity"/"NaN").
 */
public final class PyFormat {

    private PyFormat() {
    }

    /** Python {@code f"{x:.2f}"}: exact binary double, HALF_EVEN, '.' separator. */
    public static String f2(double x) {
        return fixed(x, 2);
    }

    /** Python {@code format(x, '.' + digits + 'f')} for any non-negative digit count. */
    public static String fixed(double x, int digits) {
        if (Double.isNaN(x)) {
            return "nan";
        }
        if (Double.isInfinite(x)) {
            return x > 0 ? "inf" : "-inf";
        }
        String s = new BigDecimal(x).setScale(digits, RoundingMode.HALF_EVEN).toPlainString();
        // BigDecimal has no negative zero: Python renders f"{-0.001:.2f}" as "-0.00".
        boolean negative = x < 0 || (x == 0.0d && Double.doubleToRawLongBits(x) != 0L);
        if (negative && !s.startsWith("-")) {
            return "-" + s;
        }
        return s;
    }
}
