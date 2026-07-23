package com.jpassbolt.api.service.email;

import com.jpassbolt.api.service.PublicBaseUrlResolver;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Renders localized email subjects (flat message lookup) and HTML bodies
 * (Thymeleaf templates under {@code resources/templates/email/}) in a recipient's
 * locale.
 *
 * <p>Subjects are plain {@code #{...}} message lookups (no markup), bodies are
 * full templates that compose the shared {@code _layout.html} shell. Both resolve
 * copy through the dedicated {@code mailMessageSource} bundles, so English and
 * Chinese recipients get their own language — the same per-recipient localization
 * {@link MailService} already does for recovery/setup mail.</p>
 *
 * <p>Two variables are injected into every template automatically:
 * {@code appName} and {@code appBaseUrl} (the trusted server domain from
 * {@link PublicBaseUrlResolver}, trailing slash stripped) so links resolve to
 * the browser-facing origin, never the API context path.</p>
 */
@Service
public class EmailTemplateService {

    private final SpringTemplateEngine emailTemplateEngine;
    private final MessageSource mailMessageSource;
    private final PublicBaseUrlResolver baseUrlResolver;
    private final String appName;

    /**
     * Explicit constructor (not Lombok {@code @RequiredArgsConstructor}) so the
     * {@link Qualifier} on {@code mailMessageSource} is honored — this codebase
     * ships no {@code lombok.config} copying {@code @Qualifier} onto generated
     * constructors, the same reason {@link MailService} uses an explicit one.
     */
    public EmailTemplateService(
            SpringTemplateEngine emailTemplateEngine,
            @Qualifier("mailMessageSource") MessageSource mailMessageSource,
            PublicBaseUrlResolver baseUrlResolver,
            @Value("${jpassbolt.app.name:JPassbolt}") String appName) {
        this.emailTemplateEngine = emailTemplateEngine;
        this.mailMessageSource = mailMessageSource;
        this.baseUrlResolver = baseUrlResolver;
        this.appName = appName;
    }

    /**
     * Localized subject line from the email bundles.
     *
     * @param key    a {@code messages/email} key (e.g. {@code email.resource.share.subject})
     * @param locale the recipient's locale
     * @param args   MessageFormat positional args ({@code {0}}, {@code {1}}, …)
     */
    public String subject(String key, Locale locale, Object... args) {
        return mailMessageSource.getMessage(key, args, locale);
    }

    /**
     * Render an HTML body template in the recipient's locale.
     *
     * @param templateName template path relative to {@code templates/email/}
     *                     without the {@code .html} suffix (e.g. {@code lu/resource_share})
     * @param locale       the recipient's locale (drives {@code #{...}} lookups)
     * @param vars         per-template variables (may be {@code null})
     * @return the rendered HTML
     */
    public String render(String templateName, Locale locale, Map<String, Object> vars) {
        Context context = new Context(locale);
        Map<String, Object> model = new HashMap<>();
        if (vars != null) {
            model.putAll(vars);
        }
        // Globals available to every template; do not override a caller-supplied value.
        model.putIfAbsent("appName", appName);
        model.putIfAbsent("appBaseUrl", baseUrlResolver.emailBaseUrl());
        context.setVariables(model);
        return emailTemplateEngine.process(templateName, context);
    }

    /**
     * Build an absolute deep link on the trusted server domain from a path (e.g.
     * {@code "/app/passwords/view/" + id}). Resolved through the SAME
     * {@link PublicBaseUrlResolver#emailBaseUrl()} that {@link MailService} uses,
     * so every email — recovery and every redactor notification — points at ONE
     * deterministic origin (official {@code App.fullBaseUrl} semantics). Redactors
     * run on the async mail executor with no request context, so request-derived
     * origins must never be part of this path.
     *
     * @param path an absolute path beginning with {@code /}
     * @return {@code emailBaseUrl() + path}; the resolver already trims trailing slashes
     */
    public String link(String path) {
        return baseUrlResolver.emailBaseUrl() + (path == null ? "" : path);
    }
}
