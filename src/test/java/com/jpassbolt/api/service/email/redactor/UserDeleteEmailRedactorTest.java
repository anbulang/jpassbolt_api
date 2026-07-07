package com.jpassbolt.api.service.email.redactor;

import com.jpassbolt.api.model.Group;
import com.jpassbolt.api.model.GroupUser;
import com.jpassbolt.api.model.Profile;
import com.jpassbolt.api.model.Role;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.GroupRepository;
import com.jpassbolt.api.repository.GroupUserRepository;
import com.jpassbolt.api.repository.ProfileRepository;
import com.jpassbolt.api.repository.RoleRepository;
import com.jpassbolt.api.repository.UserRepository;
import com.jpassbolt.api.service.EmailNotificationSettingsService;
import com.jpassbolt.api.service.email.EmailMessage;
import com.jpassbolt.api.service.email.EmailTemplateService;
import com.jpassbolt.api.service.email.MailService;
import com.jpassbolt.api.service.email.RecipientResolver;
import com.jpassbolt.api.service.email.event.UserDeletedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
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
 * Deterministic test of the two user-delete redactors (called directly, mocked
 * {@link MailService}). {@link UserDeleteEmailRedactor} notifies the managers of
 * the user's not-only-member groups (gate {@code send.group.user.delete});
 * {@link AdminDeleteEmailRedactor} notifies all admins when the deleted user was
 * an admin (always-on), with a self-variant for the actor and a dedup of the
 * group managers already covered by the first email. Async/SMTP wiring is proven
 * once by {@code ShareEmailNotificationE2ETest}.
 */
@SpringBootTest
@Transactional
class UserDeleteEmailRedactorTest {

    @Autowired private EmailNotificationSettingsService settings;
    @Autowired private RecipientResolver recipientResolver;
    @Autowired private EmailTemplateService templates;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ProfileRepository profileRepository;
    @Autowired private GroupRepository groupRepository;
    @Autowired private GroupUserRepository groupUserRepository;

    private MailService mailService;
    private UserDeleteEmailRedactor userDeleteRedactor;
    private AdminDeleteEmailRedactor adminDeleteRedactor;

    private String adaId;       // admin, the actor (deleter)
    private String bossId;      // admin, no group membership
    private String mgrAdminId;  // admin AND manager of group G
    private String victimId;    // non-admin member of group G (the deleted user in UserDelete cases)
    private String rogueId;     // admin (the deleted user in AdminDelete cases)
    private String groupGId;

    @BeforeEach
    void setUp() {
        mailService = mock(MailService.class);
        userDeleteRedactor = new UserDeleteEmailRedactor(settings, recipientResolver, templates,
                new NotificationDelivery(mailService), groupUserRepository);
        adminDeleteRedactor = new AdminDeleteEmailRedactor(settings, recipientResolver, templates,
                new NotificationDelivery(mailService), groupUserRepository);

        String adminRoleId = saveRole("admin");
        String userRoleId = saveRole("user");

        adaId = saveUser("ada@passbolt.com", "Ada", "Lovelace", adminRoleId);
        bossId = saveUser("boss@passbolt.com", "Big", "Boss", adminRoleId);
        mgrAdminId = saveUser("mgr@passbolt.com", "Mary", "Manager", adminRoleId);
        victimId = saveUser("victim@passbolt.com", "Vic", "Tim", userRoleId);
        rogueId = saveUser("rogue@passbolt.com", "Rogue", "One", adminRoleId);

        Group g = new Group();
        g.setName("Engineering");
        g.setDeleted(false);
        g.setCreatedBy(adaId);
        g.setModifiedBy(adaId);
        groupGId = groupRepository.save(g).getId();
        addMember(groupGId, mgrAdminId, true);
        addMember(groupGId, victimId, false);
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

    private void addMember(String groupId, String userId, boolean isAdmin) {
        GroupUser gu = new GroupUser();
        gu.setGroupId(groupId);
        gu.setUserId(userId);
        gu.setIsAdmin(isAdmin);
        groupUserRepository.save(gu);
    }

    private Set<String> recipientsOf(int count) {
        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(mailService, times(count)).send(captor.capture());
        return captor.getAllValues().stream().map(EmailMessage::recipient).collect(Collectors.toSet());
    }

    // --- UserDeleteEmailRedactor (group managers) ---

    @Test
    void notifiesGroupManagersOfNotOnlyMemberGroups() {
        userDeleteRedactor.onUserDeleted(new UserDeletedEvent(victimId, "victim@passbolt.com",
                "Vic", "Tim", false, adaId, List.of(groupGId)));

        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(mailService, times(1)).send(captor.capture());
        EmailMessage message = captor.getValue();
        assertThat(message.recipient()).isEqualTo("mgr@passbolt.com");
        assertThat(message.subject()).isEqualTo("Ada Lovelace deleted user Vic Tim");
        assertThat(message.html()).contains("Vic Tim").contains("/app/users");
    }

    @Test
    void groupManagerEmailGateOffSendsNothing() {
        settings.save(Map.of("send_group_user_delete", false), adaId);
        userDeleteRedactor.onUserDeleted(new UserDeletedEvent(victimId, "victim@passbolt.com",
                "Vic", "Tim", false, adaId, List.of(groupGId)));
        verify(mailService, never()).send(any());
    }

    @Test
    void noGroupsNotifiesNoGroupManagers() {
        userDeleteRedactor.onUserDeleted(new UserDeletedEvent(victimId, "victim@passbolt.com",
                "Vic", "Tim", false, adaId, List.of()));
        verify(mailService, never()).send(any());
    }

    // --- AdminDeleteEmailRedactor (admins) ---

    @Test
    void adminDeletionNotifiesAllAdminsIncludingActorSelfVariant() {
        adminDeleteRedactor.onUserDeleted(new UserDeletedEvent(rogueId, "rogue@passbolt.com",
                "Rogue", "One", true, adaId, List.of()));

        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        // ada (self) + boss + mgrAdmin; rogue (deleted) excluded.
        verify(mailService, times(3)).send(captor.capture());
        Map<String, EmailMessage> byRecipient = captor.getAllValues().stream()
                .collect(Collectors.toMap(EmailMessage::recipient, m -> m));
        assertThat(byRecipient.keySet())
                .containsExactlyInAnyOrder("ada@passbolt.com", "boss@passbolt.com", "mgr@passbolt.com");
        assertThat(byRecipient.get("ada@passbolt.com").subject()).isEqualTo("You deleted administrator Rogue One");
        assertThat(byRecipient.get("boss@passbolt.com").subject())
                .isEqualTo("Ada Lovelace deleted administrator Rogue One");
    }

    @Test
    void adminDeletionDedupsGroupManagersAlreadyEmailed() {
        // rogue (admin) belonged to group G whose manager (mgrAdmin) is also an
        // admin: mgrAdmin already gets the group-manager email, so the admin email
        // must skip them (PHP getAdministrators dedup).
        adminDeleteRedactor.onUserDeleted(new UserDeletedEvent(rogueId, "rogue@passbolt.com",
                "Rogue", "One", true, adaId, List.of(groupGId)));

        Set<String> recipients = recipientsOf(2);
        assertThat(recipients).containsExactlyInAnyOrder("ada@passbolt.com", "boss@passbolt.com");
        assertThat(recipients).doesNotContain("mgr@passbolt.com");
    }

    @Test
    void nonAdminDeletionSendsNoAdminEmail() {
        adminDeleteRedactor.onUserDeleted(new UserDeletedEvent(victimId, "victim@passbolt.com",
                "Vic", "Tim", false, adaId, List.of(groupGId)));
        verify(mailService, never()).send(any());
    }
}
