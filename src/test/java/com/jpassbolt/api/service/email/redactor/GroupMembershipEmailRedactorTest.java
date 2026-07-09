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
import com.jpassbolt.api.service.email.event.GroupCreatedEvent;
import com.jpassbolt.api.service.email.event.GroupMemberSnapshot;
import com.jpassbolt.api.service.email.event.GroupMembershipChangedEvent;
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
 * Deterministic test of the group redactors (called directly, mocked
 * {@link MailService}) over one {@link GroupMembershipChangedEvent} (plus the
 * create path). Covers per-bucket recipients (added / removed / role-updated), the
 * admin summary to the OTHER current managers (excluding the affected members and
 * the actor), and the gates. Async/SMTP wiring is proven once by
 * {@code ShareEmailNotificationE2ETest}.
 */
@SpringBootTest
@Transactional
class GroupMembershipEmailRedactorTest {

    @Autowired private EmailNotificationSettingsService settings;
    @Autowired private RecipientResolver recipientResolver;
    @Autowired private EmailTemplateService templates;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ProfileRepository profileRepository;
    @Autowired private GroupRepository groupRepository;
    @Autowired private GroupUserRepository groupUserRepository;

    private MailService mailService;
    private GroupUserAddEmailRedactor addRedactor;
    private GroupUserDeleteEmailRedactor deleteRedactor;
    private GroupUserUpdateEmailRedactor updateRedactor;
    private GroupUpdateAdminSummaryEmailRedactor summaryRedactor;

    private String adaId;    // operator / manager (actor)
    private String aliceId;  // added member (non-manager)
    private String bobId;    // removed member
    private String carolId;  // role-updated member (promoted to manager)
    private String daveId;   // other current manager, unaffected
    private String groupGId;

    @BeforeEach
    void setUp() {
        mailService = mock(MailService.class);
        NotificationDelivery delivery = new NotificationDelivery(mailService);
        addRedactor = new GroupUserAddEmailRedactor(settings, recipientResolver, templates, delivery);
        deleteRedactor = new GroupUserDeleteEmailRedactor(settings, recipientResolver, templates, delivery);
        updateRedactor = new GroupUserUpdateEmailRedactor(settings, recipientResolver, templates, delivery);
        summaryRedactor = new GroupUpdateAdminSummaryEmailRedactor(settings, recipientResolver, templates,
                delivery, groupUserRepository);

        String userRoleId = saveRole("user");
        adaId = saveUser("ada@passbolt.com", "Ada", "Lovelace", userRoleId);
        aliceId = saveUser("alice@passbolt.com", "Alice", "Added", userRoleId);
        bobId = saveUser("bob@passbolt.com", "Bob", "Removed", userRoleId);
        carolId = saveUser("carol@passbolt.com", "Carol", "Updated", userRoleId);
        daveId = saveUser("dave@passbolt.com", "Dave", "Manager", userRoleId);

        Group g = new Group();
        g.setName("Engineering");
        g.setDeleted(false);
        g.setCreatedBy(adaId);
        g.setModifiedBy(adaId);
        groupGId = groupRepository.save(g).getId();
        // Post-update membership state (bob already removed): the summary redactor
        // reads the CURRENT managers from here.
        addMember(adaId, true);
        addMember(daveId, true);
        addMember(carolId, true);   // promoted in this change
        addMember(aliceId, false);  // added in this change
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

    private void addMember(String userId, boolean isAdmin) {
        GroupUser gu = new GroupUser();
        gu.setGroupId(groupGId);
        gu.setUserId(userId);
        gu.setIsAdmin(isAdmin);
        groupUserRepository.save(gu);
    }

    private GroupMembershipChangedEvent updateEvent() {
        return new GroupMembershipChangedEvent(groupGId, "Engineering", adaId,
                List.of(new GroupMemberSnapshot(aliceId, false)),
                List.of(new GroupMemberSnapshot(bobId, false)),
                List.of(new GroupMemberSnapshot(carolId, true)));
    }

    private EmailMessage singleSent() {
        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(mailService, times(1)).send(captor.capture());
        return captor.getValue();
    }

    @Test
    void addRedactorNotifiesAddedMemberOnUpdate() {
        addRedactor.onGroupMembershipChanged(updateEvent());
        EmailMessage message = singleSent();
        assertThat(message.recipient()).isEqualTo("alice@passbolt.com");
        assertThat(message.subject()).isEqualTo("Ada Lovelace 把您加入了群组 Engineering");
        assertThat(message.html()).contains("/app/groups");
    }

    @Test
    void addRedactorNotifiesInitialMembersOnCreateExceptCreator() {
        addRedactor.onGroupCreated(new GroupCreatedEvent(groupGId, "Engineering", adaId,
                List.of(new GroupMemberSnapshot(aliceId, false), new GroupMemberSnapshot(adaId, true))));
        EmailMessage message = singleSent();
        assertThat(message.recipient()).isEqualTo("alice@passbolt.com"); // creator (ada) excluded
    }

    @Test
    void deleteRedactorNotifiesRemovedMember() {
        deleteRedactor.onGroupMembershipChanged(updateEvent());
        EmailMessage message = singleSent();
        assertThat(message.recipient()).isEqualTo("bob@passbolt.com");
        assertThat(message.subject()).isEqualTo("Ada Lovelace 把您移出了群组 Engineering");
    }

    @Test
    void deleteRedactorNotifiesActorWhoRemovedSelfOnUpdate() {
        // PHP parity: the update path does NOT exclude the operator, so a manager
        // who removes themselves still receives the "removed from group" notice.
        GroupMembershipChangedEvent selfRemoval = new GroupMembershipChangedEvent(groupGId,
                "Engineering", adaId, List.of(),
                List.of(new GroupMemberSnapshot(adaId, true)), List.of());
        deleteRedactor.onGroupMembershipChanged(selfRemoval);
        assertThat(singleSent().recipient()).isEqualTo("ada@passbolt.com");
    }

    @Test
    void updateRedactorNotifiesRoleChangedMemberWithPromotedCopy() {
        updateRedactor.onGroupMembershipChanged(updateEvent());
        EmailMessage message = singleSent();
        assertThat(message.recipient()).isEqualTo("carol@passbolt.com");
        assertThat(message.subject()).isEqualTo("Ada Lovelace 更新了您在群组 Engineering 中的成员身份");
        assertThat(message.html()).contains("群组管理员"); // promoted line (isManager=true), zh default
    }

    @Test
    void summaryRedactorNotifiesOtherManagersExcludingAffectedAndActor() {
        summaryRedactor.onGroupMembershipChanged(updateEvent());
        EmailMessage message = singleSent();
        // current managers = {ada, dave, carol}; minus affected {alice,bob,carol}
        // minus actor {ada} => {dave}.
        assertThat(message.recipient()).isEqualTo("dave@passbolt.com");
        assertThat(message.subject()).isEqualTo("Ada Lovelace 更新了群组 Engineering");
        assertThat(message.html())
                .contains("Alice Added")
                .contains("Bob Removed")
                .contains("Carol Updated");
    }

    @Test
    void gatesOffSuppressEachEmail() {
        settings.save(Map.of(
                "send_group_user_add", false,
                "send_group_user_delete", false,
                "send_group_user_update", false,
                "send_group_manager_update", false), adaId);
        GroupMembershipChangedEvent event = updateEvent();
        addRedactor.onGroupMembershipChanged(event);
        deleteRedactor.onGroupMembershipChanged(event);
        updateRedactor.onGroupMembershipChanged(event);
        summaryRedactor.onGroupMembershipChanged(event);
        verify(mailService, never()).send(any());
    }
}
