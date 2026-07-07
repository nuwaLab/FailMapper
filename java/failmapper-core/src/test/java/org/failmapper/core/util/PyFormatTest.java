package org.failmapper.core.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins {@link PyFormat#f2} to CPython {@code f"{x:.2f}"} (contract I12 + N7).
 *
 * <p>Every expected value below was produced by CPython 3.x:
 * {@code python3 -c "print(f'{83.125:.2f}')"} etc. Midpoint cases exercise both
 * true binary ties (ties-to-even) and decimal-looking "ties" that are not binary
 * ties (2.675 is really 2.67499999999999982...), where HALF_UP would diverge.
 */
class PyFormatTest {

    @ParameterizedTest(name = "f2({0}) == {1}")
    @CsvSource({
            // exact binary ties -> even neighbor
            "83.125, 83.12",
            "0.125, 0.12",
            "0.375, 0.38",
            "-0.125, -0.12",
            // decimal-looking midpoints that are NOT binary ties
            "83.135, 83.14",
            "2.675, 2.67",
            "2.665, 2.67",
            "1.005, 1.00",
            "0.005, 0.01",
            "0.015, 0.01",
            "0.025, 0.03",
            // plain values
            "100.0, 100.00",
            "0.0, 0.00",
            "45.5, 45.50",
            "62.375, 62.38",
            "1.0E16, 10000000000000000.00",
            // negatives rounding to zero keep their sign (CPython '-0.00')
            "-0.001, -0.00",
    })
    void matchesCpythonDotTwoF(double input, String expected) {
        assertThat(PyFormat.f2(input)).isEqualTo(expected);
    }

    @Test
    void negativeZeroKeepsSign() {
        assertThat(PyFormat.f2(-0.0d)).isEqualTo("-0.00");
    }

    @Test
    void nonFiniteMatchesPythonSpelling() {
        assertThat(PyFormat.f2(Double.NaN)).isEqualTo("nan");
        assertThat(PyFormat.f2(Double.POSITIVE_INFINITY)).isEqualTo("inf");
        assertThat(PyFormat.f2(Double.NEGATIVE_INFINITY)).isEqualTo("-inf");
    }

    @Test
    void localeIndependentDecimalPoint() {
        java.util.Locale saved = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY); // %.2f would emit ','
            assertThat(PyFormat.f2(83.125)).isEqualTo("83.12");
        } finally {
            java.util.Locale.setDefault(saved);
        }
    }
}
