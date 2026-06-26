package com.jpassbolt.api.service.email;

import com.jpassbolt.api.config.MailMessageConfig;
import com.jpassbolt.api.service.AccountLocaleService;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.MessageSource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MailService}: it must actually send when enabled +
 * a JavaMailSender is present, stay silent (log only) when disabled — the
 * property that keeps the default/test profiles working with no SMTP — and
 * localize subject/body per recipient locale (zh recipients get Chinese copy).
 */
class MailServiceTest {

    /** The real email-copy bundles, built exactly as the production config does. */
    private static final MessageSource MESSAGES = new MailMessageConfig().mailMessageSource();

    @SuppressWarnings("unchecked")
    private MailService service(JavaMailSender sender, boolean enabled, AccountLocaleService locale) {
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(sender);
        MailService s = new MailService(provider, MESSAGES, locale);
        ReflectionTestUtils.setField(s, "enabled", enabled);
        ReflectionTestUtils.setField(s, "from", "no-reply@test.local");
        ReflectionTestUtils.setField(s, "appBaseUrl", "http://localhost:5173/");
        return s;
    }

    /** AccountLocaleService stub resolving every user to the given Passbolt code. */
    private AccountLocaleService localeStub(String code) {
        AccountLocaleService locale = mock(AccountLocaleService.class);
        when(locale.getUserLocale(any())).thenReturn(code);
        // Delegate the code->Locale mapping to the real, simple implementation.
        AccountLocaleService real = new AccountLocaleService(null, null);
        when(locale.toJavaLocale(any())).thenAnswer(inv -> real.toJavaLocale(inv.getArgument(0)));
        return locale;
    }

    @Test
    void sendsRecoverEmailWhenEnabled() {
        JavaMailSender sender = mock(JavaMailSender.class);
        when(sender.createMimeMessage()).thenReturn(new MimeMessage((Session) null));

        service(sender, true, localeStub("en-UK"))
                .sendRecoverEmail("ada@passbolt.com", "uid-1", "tok-1", "default");

        verify(sender).send(any(MimeMessage.class));
    }

    @Test
    void doesNotSendWhenDisabled() {
        JavaMailSender sender = mock(JavaMailSender.class);

        service(sender, false, localeStub("en-UK"))
                .sendRecoverEmail("ada@passbolt.com", "uid-1", "tok-1", "default");

        verify(sender, never()).send(any(MimeMessage.class));
        verify(sender, never()).createMimeMessage();
    }

    @Test
    void usesEnglishSubjectForEnglishRecipient() throws Exception {
        JavaMailSender sender = mock(JavaMailSender.class);
        when(sender.createMimeMessage()).thenReturn(new MimeMessage((Session) null));

        service(sender, true, localeStub("en-UK"))
                .sendRecoverEmail("ada@passbolt.com", "uid-1", "tok-1", "default");

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(captor.capture());
        // Matches email.recover.subject in messages/email_en.properties.
        String expected = MESSAGES.getMessage("email.recover.subject",
                new Object[]{"ada@passbolt.com"}, Locale.ENGLISH);
        assertThat(captor.getValue().getSubject()).isEqualTo(expected);
    }

    @Test
    void usesChineseSubjectForChineseRecipient() throws Exception {
        JavaMailSender sender = mock(JavaMailSender.class);
        when(sender.createMimeMessage()).thenReturn(new MimeMessage((Session) null));

        // A zh-CN recipient (locale resolved from their account_settings).
        service(sender, true, localeStub("zh-CN"))
                .sendRecoverEmail("xiaoming@passbolt.com", "uid-zh", "tok-zh", "default");

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(captor.capture());
        String subject = captor.getValue().getSubject();
        // Matches email.recover.subject in messages/email_zh.properties (Chinese).
        String expectedZh = MESSAGES.getMessage("email.recover.subject",
                new Object[]{"xiaoming@passbolt.com"}, Locale.SIMPLIFIED_CHINESE);
        assertThat(subject)
                .isEqualTo(expectedZh)
                .contains("账户恢复")
                .isNotEqualTo(MESSAGES.getMessage("email.recover.subject",
                        new Object[]{"xiaoming@passbolt.com"}, Locale.ENGLISH));
    }

    @Test
    void localizesRecoverCompleteByRecipientLocale() throws Exception {
        JavaMailSender sender = mock(JavaMailSender.class);
        when(sender.createMimeMessage()).thenReturn(new MimeMessage((Session) null));

        // sendRecoverCompleteEmail now takes a userId so it can localize.
        service(sender, true, localeStub("zh-CN"))
                .sendRecoverCompleteEmail("xiaoming@passbolt.com", "uid-zh");

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(captor.capture());
        assertThat(captor.getValue().getSubject())
                .isEqualTo(MESSAGES.getMessage("email.complete.subject", null,
                        Locale.SIMPLIFIED_CHINESE));
    }
}
