package biz.brumm.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CronJobTest {

    @Test
    void validCronJobCreation() {
        Instant now = Instant.now();
        CronJob job = new CronJob("1", "Test Job", "0 */6 * * *", "Test prompt", "ctx-1", true, null, now, now);

        assertThat(job.id()).isEqualTo("1");
        assertThat(job.name()).isEqualTo("Test Job");
        assertThat(job.cronExpression()).isEqualTo("0 */6 * * *");
        assertThat(job.prompt()).isEqualTo("Test prompt");
        assertThat(job.contextId()).isEqualTo("ctx-1");
        assertThat(job.enabled()).isTrue();
        assertThat(job.lastRunAt()).isNull();
    }

    @Test
    void invalidCronJobWithBlankIdThrows() {
        assertThatThrownBy(() -> new CronJob("", "name", "0 * * * *", "prompt", null, true, null, null, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ID");
    }

    @Test
    void invalidCronJobWithBlankPromptThrows() {
        assertThatThrownBy(() -> new CronJob("1", "name", "0 * * * *", "", null, true, null, null, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Prompt");
    }

    @Test
    void withNextRunReturnsNewInstance() {
        CronJob job = new CronJob("1", "Test", "0 * * * *", "prompt", null, true, null, null, Instant.now());
        Instant nextRun = Instant.now().plusSeconds(3600);
        CronJob updated = job.withNextRun(nextRun);

        assertThat(updated).isNotSameAs(job);
        assertThat(updated.nextRunAt()).isEqualTo(nextRun);
        assertThat(job.nextRunAt()).isNull();
    }

    @Test
    void withLastRunReturnsNewInstance() {
        CronJob job = new CronJob("1", "Test", "0 * * * *", "prompt", null, true, null, null, Instant.now());
        Instant lastRun = Instant.now();
        Instant nextRun = Instant.now().plusSeconds(3600);
        CronJob updated = job.withLastRun(lastRun, nextRun);

        assertThat(updated.lastRunAt()).isEqualTo(lastRun);
        assertThat(updated.nextRunAt()).isEqualTo(nextRun);
    }

    @Test
    void withEnabledReturnsNewInstance() {
        CronJob job = new CronJob("1", "Test", "0 * * * *", "prompt", null, true, null, null, Instant.now());
        CronJob disabled = job.withEnabled(false);

        assertThat(disabled.enabled()).isFalse();
        assertThat(job.enabled()).isTrue();
    }
}
