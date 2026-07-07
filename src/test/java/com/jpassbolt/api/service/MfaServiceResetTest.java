package com.jpassbolt.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jpassbolt.api.config.SettingsProperties;
import com.jpassbolt.api.model.AccountSetting;
import com.jpassbolt.api.repository.AccountSettingRepository;
import com.jpassbolt.api.repository.AuthenticationTokenRepository;
import com.jpassbolt.api.repository.OrganizationSettingRepository;
import com.jpassbolt.api.service.email.event.MfaUserSettingsResetEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure-Mockito unit tests for {@link MfaService#resetUserMfaSettings}, pinning the
 * notification-suppression contract DIRECTLY rather than via the integration proxy
 * (the response message): the {@link MfaUserSettingsResetEvent} — and therefore the
 * reset email — must be published EXACTLY ONCE when a mfa {@code account_settings}
 * row existed, and NEVER when it did not (PHP dispatches the delete event only after
 * {@code MfaAccountSettings::get} found a row). The controller test only proves the
 * distinct response message; this test proves the publish-side branch itself, so a
 * future refactor that decouples message from publish cannot silently break it.
 *
 * <p>Using {@code any(MfaUserSettingsResetEvent.class)} (compile-time a subtype of
 * Object, not {@code ApplicationEvent}) deliberately binds to the
 * {@code publishEvent(Object)} overload the production code actually calls — a bare
 * {@code any()} would resolve to the more specific {@code publishEvent(ApplicationEvent)}
 * overload and verify the wrong method.</p>
 */
class MfaServiceResetTest {

    private final AccountSettingRepository accountSettings = mock(AccountSettingRepository.class);
    private final AuthenticationTokenRepository authTokens = mock(AuthenticationTokenRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    private final MfaService service = new MfaService(
            accountSettings,
            mock(OrganizationSettingRepository.class),
            authTokens,
            mock(TotpService.class),
            new ObjectMapper(),
            mock(SettingsProperties.class),
            events);

    @Test
    void resetPublishesEventExactlyOnceWhenSettingsExisted() {
        AccountSetting row = new AccountSetting();
        row.setUserId("betty");
        row.setProperty("mfa");
        when(accountSettings.findFirstByUserIdAndProperty("betty", "mfa"))
                .thenReturn(Optional.of(row));

        boolean existed = service.resetUserMfaSettings("betty", "ada");

        assertThat(existed).isTrue();
        verify(accountSettings).delete(row);
        ArgumentCaptor<MfaUserSettingsResetEvent> captor =
                ArgumentCaptor.forClass(MfaUserSettingsResetEvent.class);
        verify(events, times(1)).publishEvent(captor.capture());
        assertThat(captor.getValue().targetUserId()).isEqualTo("betty");
        assertThat(captor.getValue().actorId()).isEqualTo("ada");
    }

    @Test
    void resetPublishesNothingWhenNoSettingsExisted() {
        when(accountSettings.findFirstByUserIdAndProperty("betty", "mfa"))
                .thenReturn(Optional.empty());

        boolean existed = service.resetUserMfaSettings("betty", "ada");

        assertThat(existed).isFalse();
        verify(accountSettings, never()).delete(any());
        verify(events, never()).publishEvent(any(MfaUserSettingsResetEvent.class));
    }
}
