package com.jpassbolt.api.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the env-source fallback of {@link SmtpSettingsService}: with no DB row but
 * a static {@code spring.mail.*} config present, {@code get()} reports
 * {@code source="env"} and surfaces the configured host/port/username + From.
 *
 * <p>Separate {@code @TestPropertySource} context so the env properties are isolated
 * from the rest of the suite (which defines no {@code spring.mail.*}, hence the
 * {@code "undefined"} source there).</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.mail.host=smtp.env.test",
        "spring.mail.port=2525",
        "spring.mail.username=env-user",
        "jpassbolt.email.from=env-from@passbolt.com"
})
class SmtpSettingsEnvSourceTest {

    @Autowired private SmtpSettingsService service;

    @Test
    void reportsEnvSourceWhenNoDbRowButSpringMailConfigured() {
        // No DB row inserted → resolution falls through to the static env config.
        assertThat(service.currentSource()).isEqualTo(SmtpSettingsService.SOURCE_ENV);
        assertThat(service.isInDb()).isFalse();

        Map<String, Object> settings = service.get();
        assertThat(settings.get("source")).isEqualTo(SmtpSettingsService.SOURCE_ENV);
        assertThat(settings.get("host")).isEqualTo("smtp.env.test");
        assertThat(settings.get("port")).isEqualTo(2525);
        assertThat(settings.get("username")).isEqualTo("env-user");
        assertThat(settings.get("sender_email")).isEqualTo("env-from@passbolt.com");
    }
}
