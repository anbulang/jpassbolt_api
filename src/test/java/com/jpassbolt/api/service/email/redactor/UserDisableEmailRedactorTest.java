package com.jpassbolt.api.service.email.redactor;

import com.jpassbolt.api.model.Profile;
import com.jpassbolt.api.model.Role;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.ProfileRepository;
import com.jpassbolt.api.repository.RoleRepository;
import com.jpassbolt.api.repository.UserRepository;
import com.jpassbolt.api.service.AccountLocaleService;
import com.jpassbolt.api.service.EmailNotificationSettingsService;
import com.jpassbolt.api.service.email.EmailMessage;
import com.jpassbolt.api.service.email.EmailTemplateService;
import com.jpassbolt.api.service.email.MailService;
import com.jpassbolt.api.service.email.RecipientResolver;
import com.jpassbolt.api.service.email.event.UserDisabledEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Deterministic tests of the user-suspension notification pair (called directly
 * with a mocked {@link MailService}, bypassing the @Async proxy) — the ports of
 * PHP {@code UserDisableEmailRedactor} / {@code AdminDisableEmailRedactor}:
 * <ul>
 *   <li>{@link UserDisableEmailRedactor} mails every active admin that the user
 *       was suspended (the just-suspended admin drops out via the resolver's
 *       not-disabled filter, PHP {@code findNotDisabled} parity);</li>
 *   <li>{@link AdminDisableEmailRedactor} mails the suspended user themselves,
 *       but only when they are an admin, bypassing the resolver's disabled
 *       filter via the event snapshot (PHP "disabled = tomorrow" workaround).</li>
 * </ul>
 */
@SpringBootTest
@Transactional
class UserDisableEmailRedactorTest {

    @Autowired private EmailNotificationSettingsService settings;
    @Autowired private RecipientResolver recipientResolver;
    @Autowired private AccountLocaleService accountLocaleService;
    @Autowired private EmailTemplateService templates;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ProfileRepository profileRepository;

    private MailService mailService;
    private UserDisableEmailRedactor userRedactor;
    private AdminDisableEmailRedactor adminRedactor;

    private String adaId;    // administrator / actor
    private String bettyId;  // plain user
    private String carolId;  // second administrator

    @BeforeEach
    void setUp() {
        mailService = mock(MailService.class);
        NotificationDelivery delivery = new NotificationDelivery(mailService);
        userRedactor = new UserDisableEmailRedactor(settings, recipientResolver, templates, delivery);
        adminRedactor = new AdminDisableEmailRedactor(settings, recipientResolver,
                accountLocaleService, templates, delivery);

        String adminRoleId = saveRole(Role.ADMIN);
        String userRoleId = saveRole(Role.USER);
        adaId = saveUser("ada@passbolt.com", "Ada", "Lovelace", adminRoleId, null);
        bettyId = saveUser("betty@passbolt.com", "Betty", "Holberton", userRoleId, null);
        carolId = saveUser("carol@passbolt.com", "Carol", "Shaw", adminRoleId, null);
    }

    private String saveRole(String name) {
        Role role = new Role();
        role.setName(name);
        return roleRepository.save(role).getId();
    }

    private String saveUser(String email, String first, String last, String roleId,
            LocalDateTime disabled) {
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
    void userSuspensionMailsAllAdminsIncludingTheOperator() {
        userRedactor.onUserDisabled(new UserDisabledEvent(
                bettyId, "betty@passbolt.com", "Betty", "Holberton", false, adaId));

        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(mailService, times(2)).send(captor.capture());
        List<EmailMessage> sent = captor.getAllValues();
        // Both admins — the operator included (PHP does not exclude them).
        assertThat(sent).extracting(EmailMessage::recipient)
                .containsExactlyInAnyOrder("ada@passbolt.com", "carol@passbolt.com");
        assertThat(sent.get(0).subject()).isEqualTo("Betty Holberton has been suspended");
        assertThat(sent.get(0).html()).contains("betty@passbolt.com");
    }

    @Test
    void suspendedAdminDropsOutOfTheAdminBroadcast() {
        // Carol (admin) is suspended in the past → the resolver's not-disabled
        // filter removes her from the broadcast, leaving only Ada.
        User carol = userRepository.findById(carolId).orElseThrow();
        carol.setDisabled(LocalDateTime.now().minusMinutes(1));
        userRepository.save(carol);

        userRedactor.onUserDisabled(new UserDisabledEvent(
                carolId, "carol@passbolt.com", "Carol", "Shaw", true, adaId));

        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(mailService).send(captor.capture());
        assertThat(captor.getValue().recipient()).isEqualTo("ada@passbolt.com");
    }

    @Test
    void suspendedAdminGetsPersonalNoticeDespiteBeingDisabled() {
        // The recipient IS the disabled user: the snapshot bypasses the
        // resolver's filter (PHP "disabled = tomorrow" workaround).
        User carol = userRepository.findById(carolId).orElseThrow();
        carol.setDisabled(LocalDateTime.now().minusMinutes(1));
        userRepository.save(carol);

        adminRedactor.onUserDisabled(new UserDisabledEvent(
                carolId, "carol@passbolt.com", "Carol", "Shaw", true, adaId));

        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(mailService).send(captor.capture());
        EmailMessage msg = captor.getValue();
        assertThat(msg.recipient()).isEqualTo("carol@passbolt.com");
        assertThat(msg.subject()).isEqualTo("Your account has been suspended");
        // The operator's address is the contact point (mailto in PHP).
        assertThat(msg.html()).contains("ada@passbolt.com");
    }

    @Test
    void suspendedPlainUserGetsNoPersonalNotice() {
        adminRedactor.onUserDisabled(new UserDisabledEvent(
                bettyId, "betty@passbolt.com", "Betty", "Holberton", false, adaId));

        verify(mailService, never()).send(any());
    }
}
