package cn.unitask.user;

import cn.unitask.common.Problem;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

@Component
final class RegistrationPolicy {
    private final byte[] code;

    RegistrationPolicy(@Value("${unitask.auth.registration-code:}") String configured, Environment environment) {
        String normalized = configured.strip();
        if (environment.acceptsProfiles(Profiles.of("share")) &&
                (normalized.length() < 16 || normalized.length() > 128)) {
            throw new IllegalStateException("The share profile requires UNITASK_REGISTRATION_CODE (16–128 characters)");
        }
        this.code = normalized.getBytes(StandardCharsets.UTF_8);
    }

    boolean requiresCode() {
        return code.length != 0;
    }

    void requireValidCode(String supplied) {
        if (!requiresCode()) return;
        if (supplied == null || supplied.length() > 128 ||
                !MessageDigest.isEqual(code, supplied.strip().getBytes(StandardCharsets.UTF_8))) {
            throw new Problem(403, "REGISTRATION_CODE_INVALID", "请输入组织者提供的正确注册口令");
        }
    }
}
