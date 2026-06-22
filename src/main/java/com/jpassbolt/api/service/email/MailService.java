package com.jpassbolt.api.service.email;

import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * Outbound transactional email (account recovery / setup invite / completion).
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
@RequiredArgsConstructor
public class MailService {

    /** Optional: absent in default/test profiles (no spring.mail.host) → we log instead. */
    private final ObjectProvider<JavaMailSender> mailSenderProvider;

    @Value("${jpassbolt.email.enabled:false}")
    private boolean enabled;

    @Value("${jpassbolt.email.from:no-reply@jpassbolt.local}")
    private String from;

    /** CLIENT base URL (the SPA), not the API. Recovery/setup links must open the SPA. */
    @Value("${jpassbolt.app.base-url:http://localhost:5173}")
    private String appBaseUrl;

    /** Account recovery: link opens the SPA's /recover/{userId}/{token} flow. */
    public void sendRecoverEmail(String toEmail, String userId, String token, String recoveryCase) {
        String link = clientUrl("/recover/" + userId + "/" + token);
        send(toEmail, "Your account recovery, " + toEmail,
                recoverHtml(link), "recover link: " + link);
    }

    /** Setup invite (a not-yet-active user restarting setup via a register token). */
    public void sendSetupInviteEmail(String toEmail, String userId, String token) {
        String link = clientUrl("/setup/" + userId + "/" + token);
        send(toEmail, "Finish setting up your JPassbolt account",
                inviteHtml(link), "setup link: " + link);
    }

    /** Confirmation after a successful recovery. */
    public void sendRecoverCompleteEmail(String toEmail) {
        send(toEmail, "Your JPassbolt account recovery is complete",
                completeHtml(), "recovery completed for " + toEmail);
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

    // ---- minimal inline HTML templates (no template engine dependency) ----

    private String recoverHtml(String link) {
        return wrap("Account recovery",
                "<p>You requested to recover access to your JPassbolt vault.</p>"
                + "<p>Make sure you have your <strong>private key backup</strong> at hand, then continue:</p>"
                + button(link, "Recover my account")
                + "<p style=\"color:#888;font-size:12px\">If you didn't request this, you can ignore this email. "
                + "The link expires and can be used once.</p>");
    }

    private String inviteHtml(String link) {
        return wrap("Welcome to JPassbolt",
                "<p>An account was created for you. Finish setting it up to start using your vault:</p>"
                + button(link, "Set up my account"));
    }

    private String completeHtml() {
        return wrap("Recovery complete",
                "<p>Your account recovery completed successfully. You now have access to your vault again.</p>"
                + "<p style=\"color:#888;font-size:12px\">If this wasn't you, contact your administrator immediately.</p>");
    }

    private String button(String link, String label) {
        return "<p style=\"margin:24px 0\"><a href=\"" + link + "\" "
                + "style=\"background:#2a6df4;color:#fff;text-decoration:none;padding:12px 20px;"
                + "border-radius:8px;font-weight:600;display:inline-block\">" + label + "</a></p>"
                + "<p style=\"color:#888;font-size:12px;word-break:break-all\">Or open this link: " + link + "</p>";
    }

    private String wrap(String title, String body) {
        return "<div style=\"font-family:system-ui,Arial,sans-serif;max-width:520px;margin:0 auto;"
                + "padding:24px;color:#1a1a1a\"><h2 style=\"margin:0 0 12px\">" + title + "</h2>"
                + body + "<hr style=\"border:none;border-top:1px solid #eee;margin:24px 0\">"
                + "<p style=\"color:#aaa;font-size:11px\">JPassbolt — end-to-end encrypted password manager</p></div>";
    }
}
