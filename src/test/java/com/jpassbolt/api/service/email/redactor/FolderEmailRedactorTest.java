package com.jpassbolt.api.service.email.redactor;

import com.jpassbolt.api.model.Permission;
import com.jpassbolt.api.model.Profile;
import com.jpassbolt.api.model.Role;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.PermissionRepository;
import com.jpassbolt.api.repository.ProfileRepository;
import com.jpassbolt.api.repository.RoleRepository;
import com.jpassbolt.api.repository.UserRepository;
import com.jpassbolt.api.service.EmailNotificationSettingsService;
import com.jpassbolt.api.service.FolderService;
import com.jpassbolt.api.service.email.EmailMessage;
import com.jpassbolt.api.service.email.EmailTemplateService;
import com.jpassbolt.api.service.email.MailService;
import com.jpassbolt.api.service.email.RecipientResolver;
import com.jpassbolt.api.service.email.event.FolderCreatedEvent;
import com.jpassbolt.api.service.email.event.FolderDeletedEvent;
import com.jpassbolt.api.service.email.event.FolderSharedEvent;
import com.jpassbolt.api.service.email.event.FolderUpdatedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
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
 * Deterministic test of the four folder notification redactors called directly
 * (not through the {@code @Async}/{@code AFTER_COMMIT} proxy), with a mocked
 * {@link MailService} so the rendered {@link EmailMessage}s are asserted
 * synchronously. Covers each master gate ({@code send.folder.create/update/delete
 * /share}), recipient resolution, the operator-vs-other wording of update/delete,
 * and the v4-named / v5-generic subject split. Seeds its own roles/users
 * (DataInitializer only runs under {@code local}); rolled back per test via
 * {@code @Transactional}.
 */
@SpringBootTest
@Transactional
class FolderEmailRedactorTest {

    @Autowired private EmailNotificationSettingsService settings;
    @Autowired private RecipientResolver recipientResolver;
    @Autowired private EmailTemplateService templates;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ProfileRepository profileRepository;
    @Autowired private PermissionRepository permissionRepository;

    private MailService mailService;
    private CreateFolderEmailRedactor createRedactor;
    private UpdateFolderEmailRedactor updateRedactor;
    private DeleteFolderEmailRedactor deleteRedactor;
    private ShareFolderEmailRedactor shareRedactor;

    private static final String FOLDER_ID = "11111111-1111-4111-8111-111111111111";

    private String userRoleId;
    private String actorId;
    private String aliceId;
    private String bobId;
    private String disabledId;

    @BeforeEach
    void setUp() {
        mailService = mock(MailService.class);
        NotificationDelivery delivery = new NotificationDelivery(mailService);
        createRedactor = new CreateFolderEmailRedactor(settings, recipientResolver, templates, delivery);
        updateRedactor = new UpdateFolderEmailRedactor(settings, recipientResolver, templates, delivery);
        deleteRedactor = new DeleteFolderEmailRedactor(settings, recipientResolver, templates, delivery);
        shareRedactor = new ShareFolderEmailRedactor(settings, recipientResolver, templates, delivery);

        Role userRole = new Role();
        userRole.setName("user");
        userRoleId = roleRepository.save(userRole).getId();

        actorId = saveUser("grace@passbolt.com", "Grace", "Hopper", null);
        aliceId = saveUser("alice@passbolt.com", "Alice", "Liddell", null);
        bobId = saveUser("bob@passbolt.com", "Bob", "Stone", null);
        disabledId = saveUser("disabled@passbolt.com", "Dis", "Abled", LocalDateTime.now().minusDays(1));
    }

    private String saveUser(String email, String first, String last, LocalDateTime disabled) {
        User u = new User();
        u.setUsername(email);
        u.setRoleId(userRoleId);
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

    private void grantFolderPermission(String userId, int type) {
        Permission perm = new Permission();
        perm.setAco(FolderService.FOLDER_ACO);
        perm.setAcoForeignKey(FOLDER_ID);
        perm.setAro(Permission.USER_ARO);
        perm.setAroForeignKey(userId);
        perm.setType(type);
        permissionRepository.save(perm);
    }

    // ------------------------------------------------------------------
    // create — gate default OFF, sole recipient is the creator
    // ------------------------------------------------------------------

    @Test
    void create_defaultGateOff_SendsNothing() {
        createRedactor.onFolderCreated(new FolderCreatedEvent(FOLDER_ID, "Secrets", false, actorId));
        verify(mailService, never()).send(any());
    }

    @Test
    void create_gateOn_EmailsOnlyCreator_NamedForV4() {
        settings.save(Map.of("send_folder_create", true), actorId);

        createRedactor.onFolderCreated(new FolderCreatedEvent(FOLDER_ID, "Secrets", false, actorId));

        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(mailService, times(1)).send(captor.capture());
        EmailMessage msg = captor.getValue();
        assertThat(msg.recipient()).isEqualTo("grace@passbolt.com");
        assertThat(msg.subject()).isEqualTo("您添加了文件夹 Secrets");
        assertThat(msg.html())
                .contains("Secrets")
                .contains("/app/folders/view/" + FOLDER_ID);
    }

    @Test
    void create_gateOn_GenericForV5() {
        settings.save(Map.of("send_folder_create", true), actorId);

        createRedactor.onFolderCreated(new FolderCreatedEvent(FOLDER_ID, null, true, actorId));

        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(mailService, times(1)).send(captor.capture());
        assertThat(captor.getValue().subject()).isEqualTo("您添加了一个新文件夹");
    }

    // ------------------------------------------------------------------
    // update — gate default ON, all users with access incl. operator
    // ------------------------------------------------------------------

    @Test
    void update_EmailsAllWithAccess_OperatorGetsSelfWording() {
        grantFolderPermission(actorId, Permission.OWNER);
        grantFolderPermission(aliceId, Permission.READ);

        updateRedactor.onFolderUpdated(new FolderUpdatedEvent(FOLDER_ID, "Secrets", false, actorId));

        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(mailService, times(2)).send(captor.capture());

        Map<String, String> subjectByRecipient = captor.getAllValues().stream()
                .collect(Collectors.toMap(EmailMessage::recipient, EmailMessage::subject));
        assertThat(subjectByRecipient).containsOnlyKeys("grace@passbolt.com", "alice@passbolt.com");
        assertThat(subjectByRecipient.get("grace@passbolt.com")).isEqualTo("您编辑了文件夹 Secrets");
        assertThat(subjectByRecipient.get("alice@passbolt.com")).isEqualTo("Grace 编辑了文件夹 Secrets");
    }

    @Test
    void update_gateOff_SendsNothing() {
        settings.save(Map.of("send_folder_update", false), actorId);
        grantFolderPermission(actorId, Permission.OWNER);

        updateRedactor.onFolderUpdated(new FolderUpdatedEvent(FOLDER_ID, "Secrets", false, actorId));

        verify(mailService, never()).send(any());
    }

    // ------------------------------------------------------------------
    // delete — gate default ON, recipients snapshotted on the event
    // ------------------------------------------------------------------

    @Test
    void delete_EmailsSnapshotRecipients_OperatorVsOtherWording() {
        Set<String> recipients = new LinkedHashSet<>(List.of(actorId, aliceId, disabledId));

        deleteRedactor.onFolderDeleted(new FolderDeletedEvent(FOLDER_ID, "Secrets", false, actorId, recipients));

        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        // the disabled user is dropped by the resolver → only actor + alice
        verify(mailService, times(2)).send(captor.capture());

        Map<String, String> subjectByRecipient = captor.getAllValues().stream()
                .collect(Collectors.toMap(EmailMessage::recipient, EmailMessage::subject));
        assertThat(subjectByRecipient).containsOnlyKeys("grace@passbolt.com", "alice@passbolt.com");
        assertThat(subjectByRecipient.get("grace@passbolt.com")).isEqualTo("您删除了文件夹 Secrets");
        assertThat(subjectByRecipient.get("alice@passbolt.com")).isEqualTo("Grace 删除了文件夹 Secrets");
        // the delete email carries no live deep link (the folder is gone)
        assertThat(captor.getAllValues().get(0).html()).doesNotContain("/app/folders/view/");
    }

    @Test
    void delete_gateOff_SendsNothing() {
        settings.save(Map.of("send_folder_delete", false), actorId);

        deleteRedactor.onFolderDeleted(new FolderDeletedEvent(
                FOLDER_ID, "Secrets", false, actorId, Set.of(aliceId)));

        verify(mailService, never()).send(any());
    }

    // ------------------------------------------------------------------
    // share — gate default ON, added users minus the actor
    // ------------------------------------------------------------------

    @Test
    void share_EmailsAddedUsers_ExcludingActorAndDisabled() {
        Set<String> added = new LinkedHashSet<>(List.of(actorId, aliceId, bobId, disabledId));

        shareRedactor.onFolderShared(new FolderSharedEvent(FOLDER_ID, "Secrets", false, actorId, added));

        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(mailService, times(2)).send(captor.capture());

        Set<String> recipients = captor.getAllValues().stream()
                .map(EmailMessage::recipient).collect(Collectors.toSet());
        assertThat(recipients).containsExactlyInAnyOrder("alice@passbolt.com", "bob@passbolt.com");
        assertThat(captor.getAllValues().get(0).subject()).isEqualTo("Grace 与您共享了文件夹 Secrets");
    }

    @Test
    void share_v5_UsesGenericSubject() {
        shareRedactor.onFolderShared(new FolderSharedEvent(
                FOLDER_ID, null, true, actorId, Set.of(aliceId)));

        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(mailService, times(1)).send(captor.capture());
        assertThat(captor.getValue().subject()).isEqualTo("Grace 与您共享了一个文件夹");
    }

    @Test
    void share_gateOff_SendsNothing() {
        settings.save(Map.of("send_folder_share", false), actorId);

        shareRedactor.onFolderShared(new FolderSharedEvent(
                FOLDER_ID, "Secrets", false, actorId, Set.of(aliceId)));

        verify(mailService, never()).send(any());
    }
}
