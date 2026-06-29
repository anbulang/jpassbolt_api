package com.jpassbolt.api.service;

import com.jpassbolt.api.dto.SmtpSettingsDto;
import com.jpassbolt.api.model.OrganizationSetting;
import com.jpassbolt.api.repository.OrganizationSettingRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Direct tests of {@link SmtpSettingsService}: settings round-trip with
 * encryption-at-rest, validation, the db/env/undefined source resolution, the
 * runtime sender/From resolvers, and the test-email validation + graceful failure.
 *
 * <p>{@code @Transactional} so each test rolls back. The test profile defines no
 * {@code spring.mail.*}, so with no DB row the source is {@code "undefined"}.</p>
 */
@SpringBootTest
@Transactional
class SmtpSettingsServiceTest {

    @Autowired private SmtpSettingsService service;
    @Autowired private OrganizationSettingRepository organizationSettingRepository;
    @Autowired private GpgService gpgService;

    private final String adminId = UUID.randomUUID().toString();

    private SmtpSettingsDto.SettingsRequest fullRequest() {
        return SmtpSettingsDto.SettingsRequest.builder()
                .senderName("JPassbolt")
                .senderEmail("no-reply@passbolt.com")
                .host("smtp.passbolt.com")
                .tls(true)
                .port(587)
                .client("passbolt.com")
                .username("smtp-user")
                .password("s3cr3t-smtp-pw")
                .build();
    }

    // ---- settings round-trip + encryption at rest ----

    @Test
    void getReturnsUndefinedWhenNoRowAndNoEnv() {
        Map<String, Object> settings = service.get();
        assertThat(settings.get("source")).isEqualTo(SmtpSettingsService.SOURCE_UNDEFINED);
        assertThat(settings.get("host")).isNull();
        assertThat(service.isInDb()).isFalse();
        assertThat(service.currentSource()).isEqualTo(SmtpSettingsService.SOURCE_UNDEFINED);
    }

    @Test
    void saveRoundtripsAndReportsDbSource() {
        Map<String, Object> saved = service.save(fullRequest(), adminId);
        assertThat(saved.get("source")).isEqualTo(SmtpSettingsService.SOURCE_DB);
        assertThat(saved.get("host")).isEqualTo("smtp.passbolt.com");
        assertThat(saved.get("sender_email")).isEqualTo("no-reply@passbolt.com");
        assertThat(saved.get("tls")).isEqualTo(Boolean.TRUE);
        assertThat(saved.get("port")).isEqualTo(587);
        assertThat(saved.get("username")).isEqualTo("smtp-user");
        // password IS returned to the admin in cleartext (faithful to PHP).
        assertThat(saved.get("password")).isEqualTo("s3cr3t-smtp-pw");
        assertThat(saved.get("id")).isNotNull();
        assertThat(service.isInDb()).isTrue();
        assertThat(service.currentSource()).isEqualTo(SmtpSettingsService.SOURCE_DB);
    }

    @Test
    void valueIsGpgEncryptedAtRest() {
        service.save(fullRequest(), adminId);
        OrganizationSetting row = organizationSettingRepository.findByProperty("smtp").orElseThrow();
        // The stored column is an armored PGP message, NOT the plaintext password.
        assertThat(row.getValue()).startsWith("-----BEGIN PGP MESSAGE-----");
        assertThat(row.getValue()).doesNotContain("s3cr3t-smtp-pw");
        // ...and the server key can decrypt it back to the JSON payload.
        assertThat(gpgService.decrypt(row.getValue())).contains("s3cr3t-smtp-pw");
    }

    // ---- validation ----

    @Test
    void saveRejectsMissingRequiredFieldsWithFieldErrors() {
        SmtpSettingsService.SmtpSettingsValidationException ex = catchThrowableOfType(
                () -> service.save(SmtpSettingsDto.SettingsRequest.builder().build(), adminId),
                SmtpSettingsService.SmtpSettingsValidationException.class);
        assertThat(ex).isNotNull();
        Map<String, Object> errors = ex.getErrors();
        assertThat(errors).containsKeys("sender_name", "sender_email", "host", "port");
    }

    @Test
    void saveRejectsInvalidPort() {
        SmtpSettingsDto.SettingsRequest tooHigh = fullRequest();
        tooHigh.setPort(70000);
        assertThat(catchThrowableOfType(() -> service.save(tooHigh, adminId),
                SmtpSettingsService.SmtpSettingsValidationException.class)
                .getErrors()).containsKey("port");

        SmtpSettingsDto.SettingsRequest notNumeric = fullRequest();
        notNumeric.setPort("not-a-port");
        assertThat(catchThrowableOfType(() -> service.save(notNumeric, adminId),
                SmtpSettingsService.SmtpSettingsValidationException.class)
                .getErrors()).containsKey("port");
    }

    @Test
    void saveAcceptsNumericStringPort() {
        SmtpSettingsDto.SettingsRequest req = fullRequest();
        req.setPort("25");
        assertThat(service.save(req, adminId).get("port")).isEqualTo(25);
    }

    @Test
    void saveRejectsInvalidSenderEmail() {
        SmtpSettingsDto.SettingsRequest req = fullRequest();
        req.setSenderEmail("not-an-email");
        assertThat(catchThrowableOfType(() -> service.save(req, adminId),
                SmtpSettingsService.SmtpSettingsValidationException.class)
                .getErrors()).containsKey("sender_email");
    }

    @Test
    void saveRejectsInvalidClientButAcceptsIpAndDomain() {
        SmtpSettingsDto.SettingsRequest bad = fullRequest();
        bad.setClient("has space");
        assertThat(catchThrowableOfType(() -> service.save(bad, adminId),
                SmtpSettingsService.SmtpSettingsValidationException.class)
                .getErrors()).containsKey("client");

        SmtpSettingsDto.SettingsRequest ip = fullRequest();
        ip.setClient("10.0.0.1");
        assertThatCode(() -> service.save(ip, adminId)).doesNotThrowAnyException();

        SmtpSettingsDto.SettingsRequest domain = fullRequest();
        domain.setClient("mail.passbolt.com");
        assertThatCode(() -> service.save(domain, adminId)).doesNotThrowAnyException();
    }

    @Test
    void tlsIsNormalizedToTrueOrNull() {
        SmtpSettingsDto.SettingsRequest on = fullRequest();
        on.setTls("true");
        assertThat(service.save(on, adminId).get("tls")).isEqualTo(Boolean.TRUE);

        SmtpSettingsDto.SettingsRequest off = fullRequest();
        off.setTls(false);
        assertThat(service.save(off, adminId).get("tls")).isNull();
    }

    @Test
    void emptyClientIsStoredAsNull() {
        SmtpSettingsDto.SettingsRequest req = fullRequest();
        req.setClient("");
        assertThat(service.save(req, adminId).get("client")).isNull();
    }

    // ---- runtime resolvers (PHP SmtpTransportBeforeSendEventListener) ----

    @Test
    void getDbSettingsDecryptsStoredFields() {
        service.save(fullRequest(), adminId);
        Optional<Map<String, Object>> db = service.getDbSettings();
        assertThat(db).isPresent();
        assertThat(db.get().get("host")).isEqualTo("smtp.passbolt.com");
        assertThat(db.get().get("password")).isEqualTo("s3cr3t-smtp-pw");
    }

    @Test
    void activeDbMailSenderBuildsFromDbRow() {
        service.save(fullRequest(), adminId);
        Optional<org.springframework.mail.javamail.JavaMailSender> sender = service.activeDbMailSender();
        assertThat(sender).isPresent();
        JavaMailSenderImpl impl = (JavaMailSenderImpl) sender.get();
        assertThat(impl.getHost()).isEqualTo("smtp.passbolt.com");
        assertThat(impl.getPort()).isEqualTo(587);
        assertThat(impl.getUsername()).isEqualTo("smtp-user");
    }

    @Test
    void activeDbFromFormatsNameAndEmail() {
        service.save(fullRequest(), adminId);
        assertThat(service.activeDbFrom()).contains("JPassbolt <no-reply@passbolt.com>");
    }

    @Test
    void runtimeResolversEmptyWhenNoRow() {
        assertThat(service.activeDbMailSender()).isEmpty();
        assertThat(service.activeDbFrom()).isEmpty();
        assertThat(service.getDbSettings()).isEmpty();
    }

    // ---- test email ----

    @Test
    void sendTestEmailRejectsMissingRecipient() {
        SmtpSettingsDto.TestEmailRequest req = SmtpSettingsDto.TestEmailRequest.builder()
                .senderEmail("no-reply@passbolt.com")
                .host("127.0.0.1")
                .port(2525)
                .build(); // no email_test_to
        SmtpSettingsService.SmtpSettingsValidationException ex = catchThrowableOfType(
                () -> service.sendTestEmail(req),
                SmtpSettingsService.SmtpSettingsValidationException.class);
        assertThat(ex.getErrors()).containsKey("email_test_to");
    }

    @Test
    void sendTestEmailFailsGracefullyWithDebugBodyWhenHostUnreachable() {
        // Port 9 (discard) on localhost is reliably closed → connection refused fast.
        SmtpSettingsDto.TestEmailRequest req = SmtpSettingsDto.TestEmailRequest.builder()
                .senderName("JPassbolt")
                .senderEmail("no-reply@passbolt.com")
                .host("127.0.0.1")
                .port(9)
                .tls(false)
                .emailTestTo("admin@passbolt.com")
                .build();
        SmtpSettingsService.SmtpTestEmailException ex = catchThrowableOfType(
                () -> service.sendTestEmail(req),
                SmtpSettingsService.SmtpTestEmailException.class);
        assertThat(ex).isNotNull();
        // The failure still carries a (possibly empty) debug trace body, never null.
        assertThat(ex.getBody()).containsKey("debug");
        assertThat((List<?>) ex.getBody().get("debug")).isNotNull();
    }
}
