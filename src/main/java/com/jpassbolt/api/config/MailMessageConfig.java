package com.jpassbolt.api.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

/**
 * Dedicated {@link MessageSource} for outbound transactional email copy.
 *
 * <p>Backed by {@code src/main/resources/messages/email_en.properties} and
 * {@code messages/email_zh.properties} (UTF-8). It is intentionally a separate,
 * explicitly-named bean ({@code mailMessageSource}) rather than the conventional
 * {@code messageSource} bean name, so registering it never collides with — nor
 * gets overridden by — a future application-wide {@code MessageSource} (e.g. one
 * auto-detected by Spring for validation/i18n).</p>
 *
 * <p>{@link com.jpassbolt.api.service.email.MailService} injects this bean to
 * render subject + body fragments in the recipient's locale (resolved via
 * {@link com.jpassbolt.api.service.AccountLocaleService}).</p>
 */
@Configuration
public class MailMessageConfig {

    /**
     * The email-copy message source. {@code basename = messages/email} matches
     * {@code messages/email_en.properties} / {@code messages/email_zh.properties}.
     * UTF-8 is set explicitly so native Chinese characters in the {@code _zh}
     * bundle render correctly (Java .properties default to ISO-8859-1).
     */
    @Bean
    public MessageSource mailMessageSource() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages/email");
        source.setDefaultEncoding("UTF-8");
        // Fall back to the base/default bundle (English) for any locale we do not
        // ship a translation for, instead of throwing.
        source.setUseCodeAsDefaultMessage(false);
        source.setFallbackToSystemLocale(false);
        return source;
    }

    /**
     * Dedicated Thymeleaf engine for rendering outbound email bodies
     * (templates under {@code resources/templates/email/}).
     *
     * <p>{@code #{...}} expressions resolve through {@link #mailMessageSource()}
     * (set via {@code setTemplateEngineMessageSource}) in the locale supplied on
     * the {@link org.thymeleaf.context.Context}, so each recipient gets copy in
     * their own language — the same bundles {@link MailService} uses.</p>
     *
     * <p>Because this is a REST + SPA backend with no server-side HTML views,
     * being the (only) {@code SpringTemplateEngine} bean is fine: it simply
     * suppresses Boot's unused auto web template engine. {@code setCheckExistence}
     * is enabled so a non-existent template name fails fast on our own
     * {@code render(...)} calls and lets any incidental web-view lookup fall
     * through gracefully rather than throwing.</p>
     */
    @Bean
    public SpringTemplateEngine emailTemplateEngine(
            @Qualifier("mailMessageSource") MessageSource mailMessageSource) {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/email/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(true);
        resolver.setCheckExistence(true);

        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        engine.setTemplateEngineMessageSource(mailMessageSource);
        return engine;
    }
}
