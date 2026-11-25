package cn.unitask.user;

import cn.unitask.common.Problem;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegistrationPolicyTest {
    @Test
    void localRegistrationIsOpenByDefault() {
        var policy = new RegistrationPolicy("", new MockEnvironment());
        assertThat(policy.requiresCode()).isFalse();
        policy.requireValidCode(null);
    }

    @Test
    void sharedRegistrationRejectsMissingOrWrongCode() {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("share");
        assertThatThrownBy(() -> new RegistrationPolicy("", environment)).isInstanceOf(IllegalStateException.class);
        var policy = new RegistrationPolicy("fixture-code-for-share", environment);
        assertThat(policy.requiresCode()).isTrue();
        assertThatThrownBy(() -> policy.requireValidCode(null))
                .isInstanceOfSatisfying(Problem.class, problem -> assertThat(problem.status()).isEqualTo(403));
        assertThatThrownBy(() -> policy.requireValidCode("wrong"))
                .isInstanceOfSatisfying(Problem.class, problem -> assertThat(problem.status()).isEqualTo(403));
        policy.requireValidCode(" fixture-code-for-share ");
    }
}
