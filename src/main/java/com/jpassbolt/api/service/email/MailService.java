package com.jpassbolt.api.service.email;

import com.jpassbolt.api.service.AccountLocaleService;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * Outbound transactional email (account recovery / setup invite / completion).
 *
 * <p>Copy is localized <em>per recipient</em>: the language is the recipient's
 * own account locale (resolved via {@link AccountLocaleService}, falling back to
 * the organization / default locale), looked up from the {@code messages/email}
 * resource bundles via the dedicated {@code mailMessageSource} bean
 * ({@link com.jpassbolt.api.config.MailMessageConfig}). Chinese recipients get
 * the {@code _zh} bundle; everyone else falls back to English.</p>
 *
 * <p>Designed to be safe in every profile:
 * <ul>
 *   <li>If {@code jpassbolt.email.enabled=false} OR no {@link JavaMailSender} bean
 *       is configured (i.e. {@code spring.mail.host} is unset, as in the default
 *       and test profiles), this service does NOT send — it logs the link, exactly
 *       like the prior stub. So adding {@code spring-boot-starter-mail} and this
 *       service changes nothing for tests / the default profile.</li>
 *   <li>The {@code local} profile sets {@code spring.mail.host} (MailHog) and
 *       {@code jpassbolt.email.enabled=true}, so links are delivered as real email
 *       (viewable at MailHog's UI). Switching to a production SMTP provider is a
 *       config change only — this code is unchanged.</li>
 * </ul>
 *
 * <p>Send failures are swallowed (logged) so a flaky/absent SMTP never turns a
 * successful recover/setup request into a 5xx — the auth token is already
 * persisted; only delivery is best-effort. Zero-knowledge note: emails carry only
 * a one-time link (userId + opaque token); never key material or secrets.</p>
 */
@Slf4j
@Service
public class MailService {

    /** Optional: absent in default/test profiles (no spring.mail.host) → we log instead. */
    private final ObjectProvider<JavaMailSender> mailSenderProvider;

    /** Email copy bundles (messages/email_*.properties), UTF-8, dedicated bean. */
    private final MessageSource messageSource;

    /** Resolves the recipient's locale from their account/org setting. */
    private final AccountLocaleService accountLocaleService;

    /**
     * Explicit constructor (not Lombok {@code @RequiredArgsConstructor}) so the
     * {@link Qualifier} on the parameter is honored: without it, Spring would
     * inject by type and could pick Boot's auto-configured {@code messageSource}
     * (which has no email bundles) instead of {@code mailMessageSource}.
     */
    public MailService(ObjectProvider<JavaMailSender> mailSenderProvider,
                       @Qualifier("mailMessageSource") MessageSource messageSource,
                       AccountLocaleService accountLocaleService) {
        this.mailSenderProvider = mailSenderProvider;
        this.messageSource = messageSource;
        this.accountLocaleService = accountLocaleService;
    }

    @Value("${jpassbolt.email.enabled:false}")
    private boolean enabled;

    @Value("${jpassbolt.email.from:no-reply@jpassbolt.local}")
    private String from;

    /** CLIENT base URL (the SPA), not the API. Recovery/setup links must open the SPA. */
    @Value("${jpassbolt.app.base-url:http://localhost:5173}")
    private String appBaseUrl;

    /** Account recovery: link opens the SPA's /recover/{userId}/{token} flow. */
    public void sendRecoverEmail(String toEmail, String userId, String token, String recoveryCase) {
        Locale locale = localeFor(userId);
        String link = clientUrl("/recover/" + userId + "/" + token);
        String subject = msg("email.recover.subject", locale, toEmail);
        String html = wrap(locale, msg("email.recover.title", locale),
                "<p>" + msg("email.recover.intro", locale) + "</p>"
                + "<p>" + msg("email.recover.instruction", locale) + "</p>"
                + button(locale, link, msg("email.recover.cta", locale))
                + "<p style=\"color:#888;font-size:12px\">" + msg("email.recover.ignore", locale) + "</p>");
        send(toEmail, subject, html, "recover link: " + link);
    }

    /** Setup invite (a not-yet-active user restarting setup via a register token). */
    public void sendSetupInviteEmail(String toEmail, String userId, String token) {
        Locale locale = localeFor(userId);
        String link = clientUrl("/setup/" + userId + "/" + token);
        String subject = msg("email.invite.subject", locale);
        String html = wrap(locale, msg("email.invite.title", locale),
                "<p>" + msg("email.invite.intro", locale) + "</p>"
                + button(locale, link, msg("email.invite.cta", locale)));
        send(toEmail, subject, html, "setup link: " + link);
    }

    /** Confirmation after a successful recovery. */
    public void sendRecoverCompleteEmail(String toEmail, String userId) {
        Locale locale = localeFor(userId);
        String subject = msg("email.complete.subject", locale);
        String html = wrap(locale, msg("email.complete.title", locale),
                "<p>" + msg("email.complete.intro", locale) + "</p>"
                + "<p style=\"color:#888;font-size:12px\">" + msg("email.complete.warning", locale) + "</p>");
        send(toEmail, subject, html, "recovery completed for " + toEmail);
    }

    /** Recipient locale from their account/org setting, mapped to a {@link Locale}. */
    private Locale localeFor(String userId) {
        return accountLocaleService.toJavaLocale(accountLocaleService.getUserLocale(userId));
    }

    private String msg(String key, Locale locale, Object... args) {
        return messageSource.getMessage(key, args, locale);
    }

    private String clientUrl(String path) {
        return appBaseUrl.replaceAll("/+$", "") + path;
    }

    private void send(String to, String subject, String html, String logFallback) {
        JavaMailSender sender = mailSenderProvider.getIfAvailable();
        if (!enabled || sender == null) {
            // Stand-in when email is off / unconfigured: log the link (dev/test).
            log.info("[email disabled] to={} subject=\"{}\" — {}", to, subject, logFallback);
            return;
        }
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(html, true);
            sender.send(message);
            log.info("Sent \"{}\" email to {}", subject, to);
        } catch (Exception e) {
            // Best-effort: never fail the request because delivery failed.
            log.warn("Failed to send \"{}\" email to {}: {}", subject, to, e.getMessage());
        }
    }

    // ---- minimal inline HTML scaffold (no template engine dependency) ----
    // Text comes from the messages/email bundles; only structure lives here.

    private String button(Locale locale, String link, String label) {
        return "<p style=\"margin:24px 0\"><a href=\"" + link + "\" "
                + "style=\"background:#2a6df4;color:#fff;text-decoration:none;padding:12px 20px;"
                + "border-radius:8px;font-weight:600;display:inline-block\">" + label + "</a></p>"
                + "<p style=\"color:#888;font-size:12px;word-break:break-all\">"
                + msg("email.button.fallback", locale, link) + "</p>";
    }

    private String wrap(Locale locale, String title, String body) {
        return "<div style=\"font-family:system-ui,Arial,sans-serif;max-width:520px;margin:0 auto;"
                + "padding:24px;color:#1a1a1a\"><h2 style=\"margin:0 0 12px\">" + title + "</h2>"
                + body + "<hr style=\"border:none;border-top:1px solid #eee;margin:24px 0\">"
                + "<p style=\"color:#aaa;font-size:11px\">" + msg("email.footer", locale) + "</p></div>";
    }
}
