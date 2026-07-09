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
import com.jpassbolt.api.service.email.event.RecoverCompletedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Deterministic test of the two account-recovery-complete redactors (called
 * directly, mocked {@link MailService}). The user redactor confirms to the
 * recovering user (incl. client IP + user agent) and does NOT exclude the actor;
 * the admin redactor notifies all active admins EXCEPT the recovering user. Both
 * gates are covered. Async/SMTP wiring is proven once by
 * {@code ShareEmailNotificationE2ETest}.
 */
@SpringBootTest
@Transactional
class RecoverCompleteEmailRedactorTest {

    @Autowired private EmailNotificationSettingsService settings;
    @Autowired private RecipientResolver recipientResolver;
    @Autowired private EmailTemplateService templates;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ProfileRepository profileRepository;

    private MailService mailService;
    private RecoverCompleteUserEmailRedactor userRedactor;
    private RecoverCompleteAdminEmailRedactor adminRedactor;

    private String vicId;   // recovering regular user
    private String bossId;  // admin
    private String adaId;   // admin

    @BeforeEach
    void setUp() {
        mailService = mock(MailService.class);
        NotificationDelivery delivery = new NotificationDelivery(mailService);
        userRedactor = new RecoverCompleteUserEmailRedactor(settings, recipientResolver, templates, delivery);
        adminRedactor = new RecoverCompleteAdminEmailRedactor(settings, recipientResolver, templates, delivery);

        String adminRoleId = saveRole("admin");
        String userRoleId = saveRole("user");
        vicId = saveUser("vic@passbolt.com", "Vic", "Tim", userRoleId);
        bossId = saveUser("boss@passbolt.com", "Big", "Boss", adminRoleId);
        adaId = saveUser("ada@passbolt.com", "Ada", "Lovelace", adminRoleId);
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

    private RecoverCompletedEvent vicRecovered() {
        return new RecoverCompletedEvent(vicId, "vic@passbolt.com", "Vic",
                "1.2.3.4", "Mozilla/5.0 TestAgent");
    }

    private Set<String> recipientsOf(int count) {
        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(mailService, times(count)).send(captor.capture());
        return captor.getAllValues().stream().map(EmailMessage::recipient).collect(Collectors.toSet());
    }

    @Test
    void userRedactorConfirmsToRecoveringUserWithContext() {
        userRedactor.onRecoverCompleted(vicRecovered());

        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(mailService, times(1)).send(captor.capture());
        EmailMessage message = captor.getValue();
        assertThat(message.recipient()).isEqualTo("vic@passbolt.com");
        assertThat(message.subject()).isEqualTo("您已完成账户恢复！");
        assertThat(message.html())
                .contains("1.2.3.4")
                .contains("Mozilla/5.0 TestAgent")
                .contains("/app/users/view/" + vicId);
    }

    @Test
    void userRedactorGateOffSendsNothing() {
        settings.save(Map.of("send_user_recoverComplete", false), adaId);
        userRedactor.onRecoverCompleted(vicRecovered());
        verify(mailService, never()).send(any());
    }

    @Test
    void adminRedactorNotifiesAllAdmins() {
        adminRedactor.onRecoverCompleted(vicRecovered());

        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(mailService, times(2)).send(captor.capture());
        Set<String> recipients = captor.getAllValues().stream()
                .map(EmailMessage::recipient).collect(Collectors.toSet());
        assertThat(recipients).containsExactlyInAnyOrder("boss@passbolt.com", "ada@passbolt.com");
        assertThat(captor.getAllValues().get(0).subject())
                .isEqualTo("Vic 完成了账户恢复");
    }

    @Test
    void adminRedactorExcludesRecoveringUserWhoIsAlsoAdmin() {
        // boss (an admin) recovers their own account -> excluded from the admin notice.
        adminRedactor.onRecoverCompleted(new RecoverCompletedEvent(bossId, "boss@passbolt.com",
                "Big", "1.2.3.4", "Mozilla/5.0 TestAgent"));
        Set<String> recipients = recipientsOf(1);
        assertThat(recipients).containsExactly("ada@passbolt.com");
        assertThat(recipients).doesNotContain("boss@passbolt.com");
    }

    @Test
    void adminRedactorGateOffSendsNothing() {
        settings.save(Map.of("send_admin_user_recover_complete", false), adaId);
        adminRedactor.onRecoverCompleted(vicRecovered());
        verify(mailService, never()).send(any());
    }
}
