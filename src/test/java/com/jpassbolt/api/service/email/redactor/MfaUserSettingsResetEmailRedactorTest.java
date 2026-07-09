package com.jpassbolt.api.service.email.redactor;

import com.jpassbolt.api.model.Profile;
import com.jpassbolt.api.model.Role;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.ProfileRepository;
import com.jpassbolt.api.repository.RoleRepository;
import com.jpassbolt.api.repository.UserRepository;
import com.jpassbolt.api.service.email.EmailMessage;
import com.jpassbolt.api.service.email.EmailTemplateService;
import com.jpassbolt.api.service.email.MailService;
import com.jpassbolt.api.service.email.RecipientResolver;
import com.jpassbolt.api.service.email.event.MfaUserSettingsResetEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Deterministic tests of {@link MfaUserSettingsResetEmailRedactor} (called directly
 * with a mocked {@link MailService}, bypassing the @Async proxy). The redactor is
 * always-on (PHP {@code getNotificationSettingPath()=null}) and mails ONLY the
 * affected user, choosing the admin-reset vs self-reset variant by comparing the
 * event's target and actor ids:
 * <ul>
 *   <li>admin reset (actor != target) → admin subject + body naming the admin;</li>
 *   <li>self reset (actor == target) → self subject;</li>
 *   <li>a disabled target is dropped by {@link RecipientResolver} → no email
 *       (the PHP {@code !$user->isDisabled()} guard).</li>
 * </ul>
 */
@SpringBootTest
@Transactional
class MfaUserSettingsResetEmailRedactorTest {

    @Autowired private RecipientResolver recipientResolver;
    @Autowired private EmailTemplateService templates;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ProfileRepository profileRepository;

    private MailService mailService;
    private MfaUserSettingsResetEmailRedactor redactor;

    private String adaId;    // administrator / actor
    private String bettyId;  // the affected user

    @BeforeEach
    void setUp() {
        mailService = mock(MailService.class);
        NotificationDelivery delivery = new NotificationDelivery(mailService);
        redactor = new MfaUserSettingsResetEmailRedactor(recipientResolver, templates, delivery);

        String adminRoleId = saveRole(Role.ADMIN);
        String userRoleId = saveRole(Role.USER);
        adaId = saveUser("ada@passbolt.com", "Ada", "Lovelace", adminRoleId, null);
        bettyId = saveUser("betty@passbolt.com", "Betty", "Holberton", userRoleId, null);
    }

    private String saveRole(String name) {
        Role role = new Role();
        role.setName(name);
        return roleRepository.save(role).getId();
    }

    private String saveUser(String email, String first, String last, String roleId, LocalDateTime disabled) {
        User u = new User();
        u.setUsername(email);
        u.setRoleId(roleId);
        u.setActive(true);
        u.setDeleted(false);
        u.setDisabled(disabled);
        String id = userRepository.save(u).getId();
        Profile p = new Profile();
        p.setUserId(id);
        p.setFirstName(first);
        p.setLastName(last);
        profileRepository.save(p);
        return id;
    }

    @Test
    void adminResetMailsTargetWithAdminVariantNamingTheAdmin() {
        redactor.onMfaReset(new MfaUserSettingsResetEvent(bettyId, adaId));

        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(mailService, times(1)).send(captor.capture());
        EmailMessage msg = captor.getValue();
        // Only the affected user is notified — never the admin.
        assertThat(msg.recipient()).isEqualTo("betty@passbolt.com");
        assertThat(msg.subject())
                .isEqualTo("您的多重身份验证设置已被管理员重置。");
        assertThat(msg.html()).contains("Ada Lovelace");
    }

    @Test
    void selfResetMailsUserWithSelfVariant() {
        redactor.onMfaReset(new MfaUserSettingsResetEvent(bettyId, bettyId));

        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(mailService, times(1)).send(captor.capture());
        EmailMessage msg = captor.getValue();
        assertThat(msg.recipient()).isEqualTo("betty@passbolt.com");
        assertThat(msg.subject())
                .isEqualTo("您的多重身份验证设置已被您本人重置。");
        // The self body shows the self intro, never the admin-attribution intro
        // ("… for your account") nor the (absent) admin's name. NB: the shared notice
        // paragraph legitimately contains the word "administrator", so that is not a
        // discriminator — the admin-only phrase is.
        assertThat(msg.html())
                .contains("您的多重身份验证设置已被重置。")
                .doesNotContain("您账户的")
                .doesNotContain("Ada Lovelace");
    }

    @Test
    void disabledTargetGetsNoEmail() {
        // Disable Betty in the past → RecipientResolver drops her (PHP isDisabled guard).
        User betty = userRepository.findById(bettyId).orElseThrow();
        betty.setDisabled(LocalDateTime.now().minusDays(1));
        userRepository.save(betty);

        redactor.onMfaReset(new MfaUserSettingsResetEvent(bettyId, adaId));

        verify(mailService, never()).send(any());
    }
}
