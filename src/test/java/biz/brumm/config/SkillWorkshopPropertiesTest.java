package biz.brumm.config;

import biz.brumm.domain.model.ApprovalPolicy;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SkillWorkshopPropertiesTest {

    @Test
    void openClawDefaultsAreApplied() {
        SkillWorkshopProperties props = new SkillWorkshopProperties(null, 0, 0, null);

        assertThat(props.approvalPolicy()).isEqualTo(ApprovalPolicy.PENDING);
        assertThat(props.maxPending()).isEqualTo(50);
        assertThat(props.maxSkillBytes()).isEqualTo(40_000);
        assertThat(props.autonomous()).isEqualTo(new SkillWorkshopProperties.Autonomous(false));
        assertThat(props.autonomous().enabled()).isFalse();
    }

    @Test
    void customValuesArePreserved() {
        SkillWorkshopProperties props = new SkillWorkshopProperties(
                ApprovalPolicy.AUTO, 7, 1234, new SkillWorkshopProperties.Autonomous(true));

        assertThat(props.approvalPolicy()).isEqualTo(ApprovalPolicy.AUTO);
        assertThat(props.maxPending()).isEqualTo(7);
        assertThat(props.maxSkillBytes()).isEqualTo(1234);
        assertThat(props.autonomous().enabled()).isTrue();
    }

    @Test
    void nonPositiveLimitsFallBackToDefaultsIndependently() {
        SkillWorkshopProperties props = new SkillWorkshopProperties(null, -3, 0, new SkillWorkshopProperties.Autonomous(true));

        assertThat(props.approvalPolicy()).isEqualTo(ApprovalPolicy.PENDING);
        assertThat(props.maxPending()).isEqualTo(50);
        assertThat(props.maxSkillBytes()).isEqualTo(40_000);
        assertThat(props.autonomous().enabled()).isTrue();
    }
}