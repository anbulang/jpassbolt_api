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
import com.jpassbolt.api.service.email.event.SelfRegistrationSettingsChangedEvent;
import com.jpassbolt.api.service.email.event.UserRegisteredEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Deterministic tests of the two self-registration redactors (called directly with
 * a mocked {@link MailService}, bypassing the @Async proxy):
 * <ul>
 *   <li>{@link SelfRegistrationAdminEmailRedactor} — fires only for a genuine
 *       self-registration ({@code selfRegistration=true}) and only when the gate
 *       {@code send.admin.user.register.complete} is on, notifying all admins;</li>
 *   <li>{@link SelfRegistrationSettingsAdminEmailRedactor} — always-on (no gate),
 *       notifies all admins with a "you edited" self-variant for the actor and an
 *       Enabled/Disabled status body.</li>
 * </ul>
 */
@SpringBootTest
@Transactional
class SelfRegistrationEmailRedactorTest {

    @Autowired private EmailNotificationSettingsService settings;
    @Autowired private RecipientResolver recipientResolver;
    @Autowired private EmailTemplateService templates;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ProfileRepository profileRepository;

    private MailService mailService;
    private SelfRegistrationAdminEmailRedactor adminRedactor;
    private SelfRegistrationSettingsAdminEmailRedactor settingsRedactor;

    private String adaId;    // admin / actor
    private String bettyId;  // other admin

    @BeforeEach
    void setUp() {
        mailService = mock(MailService.class);
        NotificationDelivery delivery = new NotificationDelivery(mailService);
        adminRedactor = new SelfRegistrationAdminEmailRedactor(
                settings, recipientResolver, templates, delivery);
        settingsRedactor = new SelfRegistrationSettingsAdminEmailRedactor(
                recipientResolver, templates, delivery);

        String adminRoleId = saveRole(Role.ADMIN);
        adaId = saveUser("ada@passbolt.com", "Ada", "Lovelace", adminRoleId);
        bettyId = saveUser("betty@passbolt.com", "Betty", "Holberton", adminRoleId);
    }

    private String saveRole(String name) {
        Role role = new Role();
        role.setName(name);
        return roleRepository.save(role).getId();
    }

    private String saveUser(String email, String first, String last, String roleId) {
        User u = new User();
        u.setUsername(email);
        u.setRoleId(roleId);
        u.setActive(true);
        u.setDeleted(false);
        String id = userRepository.save(u).getId();
        Profile p = new Profile();
        p.setUserId(id);
        p.setFirstName(first);
        p.setLastName(last);
        profileRepository.save(p);
        return id;
    }

    private UserRegisteredEvent selfRegisterEvent() {
        return new UserRegisteredEvent("new-user-id", "newbie@passbolt.com", "New", "Bie",
                "tok-123", null, false, true);
    }

    // ---- admin "new self-registration" notice ----

    @Test
    void adminNoticeNotifiesAllAdminsOnSelfRegister() {
        adminRedactor.onUserRegistered(selfRegisterEvent());

        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(mailService, times(2)).send(captor.capture());
        assertThat(captor.getAllValues()).extracting(EmailMessage::recipient)
                .containsExactlyInAnyOrder("ada@passbolt.com", "betty@passbolt.com");
        assertThat(captor.getAllValues().get(0).subject())
                .isEqualTo("New just created an account on JPassbolt!");
    }

    @Test
    void adminNoticeSkippedForAdminInviteOrRecoverRestart() {
        // selfRegistration=false -> not a guest self-registration
        UserRegisteredEvent invite = new UserRegisteredEvent("uid", "newbie@passbolt.com",
                "New", "Bie", "tok", adaId, false, false);
        adminRedactor.onUserRegistered(invite);
        verify(mailService, never()).send(any());
    }

    @Test
    void adminNoticeSuppressedWhenGateOff() {
        settings.save(Map.of("send_admin_user_register_complete", false), adaId);
        adminRedactor.onUserRegistered(selfRegisterEvent());
        verify(mailService, never()).send(any());
    }

    // ---- settings-changed admin notice (always-on) ----

    @Test
    void settingsChangedNotifiesAdminsWithSelfVariantAndDomains() {
        settingsRedactor.onSettingsChanged(new SelfRegistrationSettingsChangedEvent(
                "email_domains", List.of("passbolt.com"), adaId));

        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(mailService, times(2)).send(captor.capture());

        EmailMessage toActor = captor.getAllValues().stream()
                .filter(m -> m.recipient().equals("ada@passbolt.com")).findFirst().orElseThrow();
        EmailMessage toOther = captor.getAllValues().stream()
                .filter(m -> m.recipient().equals("betty@passbolt.com")).findFirst().orElseThrow();

        assertThat(toActor.subject()).isEqualTo("You edited the self registration settings.");
        assertThat(toOther.subject()).isEqualTo("Ada Lovelace edited the self registration settings.");
        assertThat(toOther.html()).contains("Enabled").contains("passbolt.com");
    }

    @Test
    void settingsChangedShowsDisabledStatusWhenProviderNull() {
        settingsRedactor.onSettingsChanged(new SelfRegistrationSettingsChangedEvent(
                null, List.of(), bettyId));

        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(mailService, times(2)).send(captor.capture());
        assertThat(captor.getAllValues().get(0).html()).contains("Disabled");
    }

    @Test
    void settingsChangedAlwaysSendsEvenWhenAllNotificationGatesOff() {
        // The settings redactor has a null gate (PHP getNotificationSettingPath()=null):
        // it cannot be disabled by the notification settings.
        settings.save(Map.of(
                "send_admin_user_register_complete", false,
                "send_user_create", false), adaId);
        settingsRedactor.onSettingsChanged(new SelfRegistrationSettingsChangedEvent(
                "email_domains", List.of("passbolt.com"), adaId));
        verify(mailService, times(2)).send(any());
    }
}
