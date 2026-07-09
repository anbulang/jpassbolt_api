package com.jpassbolt.api.service.email.redactor;

import com.jpassbolt.api.model.Profile;
import com.jpassbolt.api.model.Role;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.ProfileRepository;
import com.jpassbolt.api.repository.RoleRepository;
import com.jpassbolt.api.repository.UserRepository;
import com.jpassbolt.api.service.EmailNotificationSettingsService;
import com.jpassbolt.api.service.email.EmailMessage;
import com.jpassbolt.api.service.email.EmailTemplateService;
import com.jpassbolt.api.service.email.MailService;
import com.jpassbolt.api.service.email.RecipientResolver;
import com.jpassbolt.api.service.email.event.UserRegisteredEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Deterministic test of {@link UserRegisterEmailRedactor} (called directly, mocked
 * {@link MailService}). Covers the master gate ({@code send.user.create}), the
 * single-recipient (invited user) resolution incl. the inactive invitee that
 * {@link RecipientResolver} deliberately keeps, the admin-attribution line vs the
 * nameless recover-restart variant ({@code adminId == null}), and the
 * {@code disabled} skip. Async/SMTP wiring is proven once by
 * {@code ShareEmailNotificationE2ETest}.
 */
@SpringBootTest
@Transactional
class UserRegisterEmailRedactorTest {

    @Autowired private EmailNotificationSettingsService settings;
    @Autowired private RecipientResolver recipientResolver;
    @Autowired private EmailTemplateService templates;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ProfileRepository profileRepository;

    private MailService mailService;
    private UserRegisterEmailRedactor redactor;

    private String adminId;
    private String invitedId;

    @BeforeEach
    void setUp() {
        mailService = mock(MailService.class);
        redactor = new UserRegisterEmailRedactor(settings, recipientResolver, templates,
                new NotificationDelivery(mailService));

        Role role = new Role();
        role.setName("user");
        String userRoleId = roleRepository.save(role).getId();

        adminId = saveUser("ada@passbolt.com", "Ada", "Lovelace", userRoleId, true);
        // The invitee is INACTIVE (a setup invite goes precisely to a not-yet-active user).
        invitedId = saveUser("newbie@passbolt.com", "New", "Bie", userRoleId, false);
    }

    private String saveUser(String email, String first, String last, String roleId, boolean active) {
        User u = new User();
        u.setUsername(email);
        u.setRoleId(roleId);
        u.setActive(active);
        u.setDeleted(false);
        String id = userRepository.save(u).getId();
        Profile p = new Profile();
        p.setUserId(id);
        p.setFirstName(first);
        p.setLastName(last);
        profileRepository.save(p);
        return id;
    }

    private UserRegisteredEvent adminInvite() {
        return new UserRegisteredEvent(invitedId, "newbie@passbolt.com", "New", "Bie",
                "tok-12345", adminId, false, false);
    }

    @Test
    void invitedUserGetsSetupLinkWithAdminAttribution() {
        redactor.onUserRegistered(adminInvite());

        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(mailService, times(1)).send(captor.capture());

        EmailMessage message = captor.getValue();
        assertThat(message.recipient()).isEqualTo("newbie@passbolt.com");
        assertThat(message.subject()).isEqualTo("欢迎使用 JPassbolt，New！");
        assertThat(message.html())
                .contains("/setup/" + invitedId + "/tok-12345")
                .contains("Ada Lovelace"); // admin attribution line present
    }

    @Test
    void masterGateOffSendsNothing() {
        settings.save(Map.of("send_user_create", false), adminId);
        redactor.onUserRegistered(adminInvite());
        verify(mailService, never()).send(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void recoverRestartNullAdminUsesNamelessIntro() {
        UserRegisteredEvent selfRestart = new UserRegisteredEvent(invitedId, "newbie@passbolt.com",
                "New", "Bie", "tok-99999", null, false, false);
        redactor.onUserRegistered(selfRestart);

        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(mailService, times(1)).send(captor.capture());

        EmailMessage message = captor.getValue();
        assertThat(message.html())
                .contains("/setup/" + invitedId + "/tok-99999")
                .doesNotContain("Ada Lovelace"); // no admin attribution on the self-driven branch
    }

    @Test
    void disabledInviteeSkipped() {
        UserRegisteredEvent disabled = new UserRegisteredEvent(invitedId, "newbie@passbolt.com",
                "New", "Bie", "tok-00000", adminId, true, false);
        redactor.onUserRegistered(disabled);
        verify(mailService, never()).send(org.mockito.ArgumentMatchers.any());
    }
}
