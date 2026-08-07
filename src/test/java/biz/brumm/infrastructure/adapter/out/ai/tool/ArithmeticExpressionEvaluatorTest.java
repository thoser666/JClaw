package biz.brumm.infrastructure.adapter.out.ai.tool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArithmeticExpressionEvaluatorTest {

    @Test
    void evaluatesPrecedenceAndParentheses() {
        assertThat(ArithmeticExpressionEvaluator.evaluate("2 + 3 * 4")).isEqualTo(14.0);
        assertThat(ArithmeticExpressionEvaluator.evaluate("(2 + 3) * 4")).isEqualTo(20.0);
        assertThat(ArithmeticExpressionEvaluator.evaluate("(2 + (3 * 4)) / 2")).isEqualTo(7.0);
    }

    @Test
    void evaluatesUnaryMinusAndDecimals() {
        assertThat(ArithmeticExpressionEvaluator.evaluate("-5 * 2")).isEqualTo(-10.0);
        assertThat(ArithmeticExpressionEvaluator.evaluate("2 - -3")).isEqualTo(5.0);
        assertThat(ArithmeticExpressionEvaluator.evaluate("2.5 * 4")).isEqualTo(10.0);
        assertThat(ArithmeticExpressionEvaluator.evaluate("10 / 4")).isEqualTo(2.5);
    }

    @Test
    void toleratesWhitespace() {
        assertThat(ArithmeticExpressionEvaluator.evaluate("  2 + 2  ")).isEqualTo(4.0);
        assertThat(ArithmeticExpressionEvaluator.evaluate("2\t*\n3")).isEqualTo(6.0);
    }

    @Test
    void evaluatesDeeplyNestedExpressions() {
        assertThat(ArithmeticExpressionEvaluator.evaluate("((2 + 3) * (4 - 1))")).isEqualTo(15.0);
        assertThat(ArithmeticExpressionEvaluator.evaluate("100 / (2 * 5)")).isEqualTo(10.0);
        assertThat(ArithmeticExpressionEvaluator.evaluate("1 + 2 + 3 + 4 + 5 + 6 + 7 + 8 + 9 + 10")).isEqualTo(55.0);
    }

    @Test
    void rejectsInvalidInput() {
        assertThatThrownBy(() -> ArithmeticExpressionEvaluator.evaluate(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ArithmeticExpressionEvaluator.evaluate("1 +"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ArithmeticExpressionEvaluator.evaluate("(1 + 2"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ArithmeticExpressionEvaluator.evaluate("1 + a"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ArithmeticExpressionEvaluator.evaluate("1 2"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ArithmeticExpressionEvaluator.evaluate("10 / 0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null");
    }

    @Test
    void formatsWholeNumbersWithoutFraction() {
        assertThat(ArithmeticExpressionEvaluator.format(4.0)).isEqualTo("4");
        assertThat(ArithmeticExpressionEvaluator.format(2.5)).isEqualTo("2.5");
        assertThat(ArithmeticExpressionEvaluator.format(-10.0)).isEqualTo("-10");
    }

    @Test
    void formatsZeroAndNonIntegerValues() {
        assertThat(ArithmeticExpressionEvaluator.format(0.0)).isEqualTo("0");
        assertThat(ArithmeticExpressionEvaluator.format(0.25)).isEqualTo("0.25");
    }
}
