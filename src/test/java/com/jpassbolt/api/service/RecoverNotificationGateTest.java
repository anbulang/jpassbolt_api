package com.jpassbolt.api.service;

import com.jpassbolt.api.dto.RecoverDto;
import com.jpassbolt.api.model.AuthenticationToken;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.AuthenticationTokenRepository;
import com.jpassbolt.api.repository.OrganizationSettingRepository;
import com.jpassbolt.api.repository.UserRepository;
import com.jpassbolt.api.service.email.MailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The account-recovery email is the only remaining direct (non-redactor) send,
 * so its org toggle {@code send.user.recover} must be honored in
 * {@link RecoverService}. When off, the recover token is still issued (the flow
 * is not broken) but no notification email is dispatched.
 */
@SpringBootTest
class RecoverNotificationGateTest {

    @Autowired
    private RecoverService recoverService;

    @MockBean
    private MailService mailService;

    @Autowired
    private EmailNotificationSettingsService emailNotificationSettingsService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuthenticationTokenRepository authenticationTokenRepository;

    @Autowired
    private OrganizationSettingRepository organizationSettingRepository;

    private User activeUser;

    @BeforeEach
    void setUp() {
        authenticationTokenRepository.deleteAll();
        // Reset the notification settings to defaults for isolation.
        organizationSettingRepository
                .findByProperty(EmailNotificationSettingsService.ORG_SETTING_PROPERTY)
                .ifPresent(organizationSettingRepository::delete);
        userRepository.findByUsername("gate@example.com").ifPresent(userRepository::delete);

        activeUser = new User();
        activeUser.setUsername("gate@example.com");
        activeUser.setRoleId("user");
        activeUser.setActive(true);
        activeUser.setDeleted(false);
        userRepository.save(activeUser);
    }

    private RecoverDto.RecoverRequest request() {
        RecoverDto.RecoverRequest r = new RecoverDto.RecoverRequest();
        r.setUsername("gate@example.com");
        return r;
    }

    @Test
    void sendsRecoverEmailWhenSettingEnabled() {
        // send_user_recover defaults to true -> the email is dispatched.
        AuthenticationToken token = recoverService.recover(request());

        assertThat(token).isNotNull();
        verify(mailService, times(1))
                .sendRecoverEmail(eq("gate@example.com"), anyString(), anyString(), any());
    }

    @Test
    void suppressesRecoverEmailWhenSettingDisabled() {
        emailNotificationSettingsService.save(Map.of("send_user_recover", false), activeUser.getId());
        reset(mailService);

        AuthenticationToken token = recoverService.recover(request());

        // Token still issued (recovery not broken), but no email sent.
        assertThat(token).isNotNull();
        verify(mailService, never())
                .sendRecoverEmail(anyString(), anyString(), anyString(), any());
    }
}
