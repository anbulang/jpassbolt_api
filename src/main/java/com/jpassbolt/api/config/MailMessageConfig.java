package com.jpassbolt.api.config;

import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ResourceBundleMessageSource;

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
}
