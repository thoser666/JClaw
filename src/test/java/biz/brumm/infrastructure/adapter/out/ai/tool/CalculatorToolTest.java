package biz.brumm.infrastructure.adapter.out.ai.tool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CalculatorToolTest {

    private final CalculatorTool tool = new CalculatorTool();

    @Test
    void calculatesBasicArithmetic() {
        assertThat(tool.calculate("2 + 3 * 4")).isEqualTo("14");
        assertThat(tool.calculate("(12 + 4) * 2 / 8")).isEqualTo("4");
        assertThat(tool.calculate("10 - 2.5")).isEqualTo("7.5");
        assertThat(tool.calculate("-3 + 5")).isEqualTo("2");
    }

    @Test
    void returnsErrorForDivisionByZero() {
        assertThat(tool.calculate("10 / 0")).startsWith("Fehler");
    }

    @Test
    void returnsErrorForInvalidInput() {
        assertThat(tool.calculate("")).startsWith("Fehler");
        assertThat(tool.calculate("abc")).startsWith("Fehler");
        assertThat(tool.calculate("1 +")).startsWith("Fehler");
        assertThat(tool.calculate(null)).startsWith("Fehler");
    }
}
