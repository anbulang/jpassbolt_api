package com.jpassbolt.api.service;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.jpassbolt.api.dto.SmtpSettingsDto;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end proof of the SMTP test-email path against an in-memory GreenMail SMTP
 * server with authentication: {@link SmtpSettingsService#sendTestEmail} must
 * actually deliver over SMTP AND the returned {@code debug} trace must NOT leak the
 * SMTP credentials (PHP {@code removeCredentials}). This is the only GreenMail test
 * for the SMTP feature — the rest is verified deterministically in-process.
 */
@SpringBootTest
class SmtpTestEmailE2ETest {

    private static final String SMTP_USER = "smtp-user";
    private static final String SMTP_PASSWORD = "s3cr3t-smtp-pw";

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP)
            .withConfiguration(GreenMailConfiguration.aConfig()
                    .withUser("smtp-user@localhost", SMTP_USER, SMTP_PASSWORD))
            .withPerMethodLifecycle(true);

    @Autowired private SmtpSettingsService smtpSettingsService;

    @Test
    @SuppressWarnings("unchecked")
    void sendsTestEmailOverSmtpAndMasksCredentialsInDebugTrace() throws Exception {
        SmtpSettingsDto.TestEmailRequest req = SmtpSettingsDto.TestEmailRequest.builder()
                .senderName("JPassbolt")
                .senderEmail("no-reply@passbolt.com")
                .host("127.0.0.1")
                .port(ServerSetupTest.SMTP.getPort()) // 3025
                .tls(false)
                .username(SMTP_USER)
                .password(SMTP_PASSWORD)
                .emailTestTo("admin@passbolt.com")
                .build();

        java.util.Map<String, Object> body = smtpSettingsService.sendTestEmail(req);

        // Delivered.
        assertThat(greenMail.waitForIncomingEmail(5000, 1)).isTrue();
        MimeMessage[] messages = greenMail.getReceivedMessages();
        assertThat(messages).hasSize(1);
        assertThat(messages[0].getSubject()).isEqualTo("JPassbolt test email");
        assertThat(messages[0].getAllRecipients()[0].toString()).isEqualTo("admin@passbolt.com");

        // The debug trace is returned and the credentials are masked out of it: the
        // raw password (and its Base64 AUTH form) must never appear in the response.
        List<String> debug = (List<String>) body.get("debug");
        assertThat(debug).isNotEmpty();
        String joined = String.join("\n", debug);
        assertThat(joined).contains("EHLO");             // the SMTP dialog was captured
        assertThat(joined).containsIgnoringCase("AUTH"); // credentials were genuinely exchanged
        // ...yet neither the raw password/username nor their Base64 AUTH forms leak,
        // so the masking is a non-vacuous proof (the secret WAS on the wire).
        java.util.Base64.Encoder b64 = java.util.Base64.getEncoder();
        assertThat(joined).doesNotContain(SMTP_PASSWORD);
        assertThat(joined).doesNotContain(b64.encodeToString(SMTP_PASSWORD.getBytes()));
        assertThat(joined).doesNotContain(b64.encodeToString(SMTP_USER.getBytes()));
    }
}
