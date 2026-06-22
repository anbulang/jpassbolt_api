package com.jpassbolt.api.service.email;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MailService}: it must actually send when enabled +
 * a JavaMailSender is present, and must stay silent (log only) when disabled —
 * the property that keeps the default/test profiles working with no SMTP.
 */
class MailServiceTest {

    @SuppressWarnings("unchecked")
    private MailService service(JavaMailSender sender, boolean enabled) {
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(sender);
        MailService s = new MailService(provider);
        ReflectionTestUtils.setField(s, "enabled", enabled);
        ReflectionTestUtils.setField(s, "from", "no-reply@test.local");
        ReflectionTestUtils.setField(s, "appBaseUrl", "http://localhost:5173/");
        return s;
    }

    @Test
    void sendsRecoverEmailWhenEnabled() {
        JavaMailSender sender = mock(JavaMailSender.class);
        when(sender.createMimeMessage()).thenReturn(new MimeMessage((Session) null));

        service(sender, true).sendRecoverEmail("ada@passbolt.com", "uid-1", "tok-1", "default");

        verify(sender).send(any(MimeMessage.class));
    }

    @Test
    void doesNotSendWhenDisabled() {
        JavaMailSender sender = mock(JavaMailSender.class);

        service(sender, false).sendRecoverEmail("ada@passbolt.com", "uid-1", "tok-1", "default");

        verify(sender, never()).send(any(MimeMessage.class));
        verify(sender, never()).createMimeMessage();
    }
}
