package biz.brumm.domain.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CronExpressionTest {

    @Test
    void everyMinute() {
        CronExpression expr = CronExpression.parse("* * * * *");
        assertThat(expr.matches(LocalDateTime.of(2026, 1, 1, 12, 30))).isTrue();
    }

    @Test
    void everyHour() {
        CronExpression expr = CronExpression.parse("0 * * * *");
        assertThat(expr.matches(LocalDateTime.of(2026, 1, 1, 0, 0))).isTrue();
        assertThat(expr.matches(LocalDateTime.of(2026, 1, 1, 12, 0))).isTrue();
        assertThat(expr.matches(LocalDateTime.of(2026, 1, 1, 12, 30))).isFalse();
    }

    @Test
    void specificMinuteAndHour() {
        CronExpression expr = CronExpression.parse("30 14 * * *");
        assertThat(expr.matches(LocalDateTime.of(2026, 1, 1, 14, 30))).isTrue();
        assertThat(expr.matches(LocalDateTime.of(2026, 1, 1, 14, 0))).isFalse();
    }

    @Test
    void stepExpression() {
        CronExpression expr = CronExpression.parse("*/15 * * * *");
        assertThat(expr.matches(LocalDateTime.of(2026, 1, 1, 10, 0))).isTrue();
        assertThat(expr.matches(LocalDateTime.of(2026, 1, 1, 10, 15))).isTrue();
        assertThat(expr.matches(LocalDateTime.of(2026, 1, 1, 10, 30))).isTrue();
        assertThat(expr.matches(LocalDateTime.of(2026, 1, 1, 10, 45))).isTrue();
        assertThat(expr.matches(LocalDateTime.of(2026, 1, 1, 10, 10))).isFalse();
    }

    @Test
    void rangeExpression() {
        CronExpression expr = CronExpression.parse("0 9-17 * * *");
        assertThat(expr.matches(LocalDateTime.of(2026, 1, 1, 9, 0))).isTrue();
        assertThat(expr.matches(LocalDateTime.of(2026, 1, 1, 17, 0))).isTrue();
        assertThat(expr.matches(LocalDateTime.of(2026, 1, 1, 8, 0))).isFalse();
        assertThat(expr.matches(LocalDateTime.of(2026, 1, 1, 18, 0))).isFalse();
    }

    @Test
    void listExpression() {
        CronExpression expr = CronExpression.parse("0 0 * * 1,3,5");
        assertThat(expr.matches(LocalDateTime.of(2026, 1, 5, 0, 0))).isTrue();  // Mo
        assertThat(expr.matches(LocalDateTime.of(2026, 1, 7, 0, 0))).isTrue();  // Mi
        assertThat(expr.matches(LocalDateTime.of(2026, 1, 9, 0, 0))).isTrue();  // Fr
        assertThat(expr.matches(LocalDateTime.of(2026, 1, 6, 0, 0))).isFalse(); // Di
    }

    @Test
    void everySixHours() {
        CronExpression expr = CronExpression.parse("0 */6 * * *");
        assertThat(expr.matches(LocalDateTime.of(2026, 1, 1, 0, 0))).isTrue();
        assertThat(expr.matches(LocalDateTime.of(2026, 1, 1, 6, 0))).isTrue();
        assertThat(expr.matches(LocalDateTime.of(2026, 1, 1, 12, 0))).isTrue();
        assertThat(expr.matches(LocalDateTime.of(2026, 1, 1, 18, 0))).isTrue();
        assertThat(expr.matches(LocalDateTime.of(2026, 1, 1, 3, 0))).isFalse();
    }

    @Test
    void invalidFieldCountThrows() {
        assertThatThrownBy(() -> CronExpression.parse("* * *"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("5 Felder");
    }

    @Test
    void emptyExpressionThrows() {
        assertThatThrownBy(() -> CronExpression.parse(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void isValidReturnsTrueForValidExpression() {
        assertThat(CronExpression.isValid("0 */6 * * *")).isTrue();
    }

    @Test
    void isValidReturnsFalseForInvalidExpression() {
        assertThat(CronExpression.isValid("* * *")).isFalse();
    }

    @Test
    void nextExecutionAfterFindsNextMinute() {
        CronExpression expr = CronExpression.parse("30 14 * * *");
        LocalDateTime now = LocalDateTime.of(2026, 1, 1, 14, 0);
        var next = expr.nextExecutionAfter(now.atZone(java.time.ZoneId.systemDefault()).toInstant());
        var expected = LocalDateTime.of(2026, 1, 1, 14, 30);
        assertThat(next).isEqualTo(expected.atZone(java.time.ZoneId.systemDefault()).toInstant());
    }

    @Test
    void nextExecutionAfterSkipsToNextDay() {
        CronExpression expr = CronExpression.parse("0 10 * * *");
        LocalDateTime now = LocalDateTime.of(2026, 1, 1, 10, 1);
        var next = expr.nextExecutionAfter(now.atZone(java.time.ZoneId.systemDefault()).toInstant());
        var expected = LocalDateTime.of(2026, 1, 2, 10, 0);
        assertThat(next).isEqualTo(expected.atZone(java.time.ZoneId.systemDefault()).toInstant());
    }
}
