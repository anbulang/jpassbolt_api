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
import com.jpassbolt.api.service.email.EmailMessage;
import com.jpassbolt.api.service.email.EmailTemplateService;
import com.jpassbolt.api.service.email.MailService;
import com.jpassbolt.api.service.email.RecipientResolver;
import com.jpassbolt.api.service.email.event.ResourceCreatedEvent;
import com.jpassbolt.api.service.email.event.ResourceDeletedEvent;
import com.jpassbolt.api.service.email.event.ResourceUpdatedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
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
 * Deterministic test of the three resource CUD notification redactors called
 * directly (not through the {@code @Async}/{@code AFTER_COMMIT} proxy), with a
 * mocked {@link MailService} so the rendered {@link EmailMessage}s are asserted
 * synchronously. Covers each master gate ({@code send.password.create/update
 * /delete}), recipient resolution (create→creator; update→all-with-access incl.
 * operator; delete→all-with-access excl. deleter, disabled dropped), the
 * operator-vs-other wording of update, the v4-named / v5-generic split, and the
 * default-off content gates (incl. per-recipient own secret on update). Seeds its
 * own roles/users/permissions; rolled back per test via {@code @Transactional}.
 */
@SpringBootTest
@Transactional
class ResourceEmailRedactorTest {

    @Autowired private EmailNotificationSettingsService settings;
    @Autowired private RecipientResolver recipientResolver;
    @Autowired private EmailTemplateService templates;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ProfileRepository profileRepository;
    @Autowired private PermissionRepository permissionRepository;

    private MailService mailService;
    private ResourceCreateEmailRedactor createRedactor;
    private ResourceUpdateEmailRedactor updateRedactor;
    private ResourceDeleteEmailRedactor deleteRedactor;

    private static final String RESOURCE_ID = "22222222-2222-4222-8222-222222222222";

    private String userRoleId;
    private String actorId;
    private String aliceId;
    private String disabledId;

    @BeforeEach
    void setUp() {
        mailService = mock(MailService.class);
        NotificationDelivery delivery = new NotificationDelivery(mailService);
        createRedactor = new ResourceCreateEmailRedactor(settings, recipientResolver, templates, delivery);
        updateRedactor = new ResourceUpdateEmailRedactor(settings, recipientResolver, templates, delivery);
        deleteRedactor = new ResourceDeleteEmailRedactor(settings, recipientResolver, templates, delivery);

        Role userRole = new Role();
        userRole.setName("user");
        userRoleId = roleRepository.save(userRole).getId();

        actorId = saveUser("grace@passbolt.com", "Grace", "Hopper", null);
        aliceId = saveUser("alice@passbolt.com", "Alice", "Liddell", null);
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

    private void grantResourcePermission(String userId, int type) {
        Permission perm = new Permission();
        perm.setAco(Permission.RESOURCE_ACO);
        perm.setAcoForeignKey(RESOURCE_ID);
        perm.setAro(Permission.USER_ARO);
        perm.setAroForeignKey(userId);
        perm.setType(type);
        permissionRepository.save(perm);
    }

    private ResourceCreatedEvent created(String name, boolean isV5, String creatorSecret) {
        return new ResourceCreatedEvent(RESOURCE_ID, name, "db-admin",
                "https://secret.example.com", "prod db", isV5, actorId, creatorSecret);
    }

    // ------------------------------------------------------------------
    // create — gate default OFF, sole recipient is the creator
    // ------------------------------------------------------------------

    @Test
    void create_defaultGateOff_SendsNothing() {
        createRedactor.onResourceCreated(created("AWS root", false, null));
        verify(mailService, never()).send(any());
    }

    @Test
    void create_gateOn_EmailsOnlyCreator_NamedForV4() {
        settings.save(Map.of("send_password_create", true), actorId);

        createRedactor.onResourceCreated(created("AWS root", false, null));

        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(mailService, times(1)).send(captor.capture());
        EmailMessage msg = captor.getValue();
        assertThat(msg.recipient()).isEqualTo("grace@passbolt.com");
        assertThat(msg.subject()).isEqualTo("您添加了密码 AWS root");
        assertThat(msg.html())
                .contains("AWS root")
                .contains("/app/passwords/view/" + RESOURCE_ID)
                .doesNotContain("db-admin")                          // show_username default off
                .doesNotContain("https://secret.example.com");       // show_uri default off
    }

    @Test
    void create_gateOn_GenericForV5() {
        settings.save(Map.of("send_password_create", true), actorId);
        createRedactor.onResourceCreated(created(null, true, null));

        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(mailService, times(1)).send(captor.capture());
        assertThat(captor.getValue().subject()).isEqualTo("您添加了一个新密码");
    }

    @Test
    void create_gateOn_ContentGatesRevealCreatorSecret() {
        settings.save(Map.of("send_password_create", true,
                "show_username", true, "show_uri", true, "show_secret", true), actorId);

        createRedactor.onResourceCreated(created("AWS root", false, "-----BEGIN PGP MESSAGE-----grace"));

        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(mailService, times(1)).send(captor.capture());
        assertThat(captor.getValue().html())
                .contains("db-admin")                                // show_username on
                .contains("https://secret.example.com")              // show_uri on
                .contains("-----BEGIN PGP MESSAGE-----grace");       // creator's own secret, show_secret on
    }

    // ------------------------------------------------------------------
    // update — gate default ON, all users with access incl. operator
    // ------------------------------------------------------------------

    @Test
    void update_EmailsAllWithAccess_OperatorSelfWording_OwnSecretOnly() {
        settings.save(Map.of("show_secret", true), actorId);
        grantResourcePermission(actorId, Permission.OWNER);
        grantResourcePermission(aliceId, Permission.READ);

        updateRedactor.onResourceUpdated(new ResourceUpdatedEvent(
                RESOURCE_ID, "AWS root", "db-admin", "https://secret.example.com", "prod db", false, actorId,
                Map.of(actorId, "-----BEGIN PGP MESSAGE-----grace",
                        aliceId, "-----BEGIN PGP MESSAGE-----alice")));

        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(mailService, times(2)).send(captor.capture());

        Map<String, EmailMessage> byRecipient = captor.getAllValues().stream()
                .collect(Collectors.toMap(EmailMessage::recipient, m -> m));
        assertThat(byRecipient).containsOnlyKeys("grace@passbolt.com", "alice@passbolt.com");
        assertThat(byRecipient.get("grace@passbolt.com").subject()).isEqualTo("您编辑了资源 AWS root");
        assertThat(byRecipient.get("alice@passbolt.com").subject()).isEqualTo("Grace 编辑了资源 AWS root");
        // each recipient sees only their OWN ciphertext, never the other's
        assertThat(byRecipient.get("alice@passbolt.com").html())
                .contains("-----BEGIN PGP MESSAGE-----alice")
                .doesNotContain("-----BEGIN PGP MESSAGE-----grace");
    }

    @Test
    void update_gateOff_SendsNothing() {
        settings.save(Map.of("send_password_update", false), actorId);
        grantResourcePermission(actorId, Permission.OWNER);

        updateRedactor.onResourceUpdated(new ResourceUpdatedEvent(
                RESOURCE_ID, "AWS root", null, null, null, false, actorId, Map.of()));

        verify(mailService, never()).send(any());
    }

    // ------------------------------------------------------------------
    // delete — gate default ON, all-with-access EXCLUDING deleter
    // ------------------------------------------------------------------

    @Test
    void delete_EmailsOthersExcludingDeleterAndDisabled() {
        grantResourcePermission(actorId, Permission.OWNER);
        grantResourcePermission(aliceId, Permission.READ);
        grantResourcePermission(disabledId, Permission.READ);

        deleteRedactor.onResourceDeleted(new ResourceDeletedEvent(
                RESOURCE_ID, "AWS root", "db-admin", "https://secret.example.com", "prod db", false, actorId));

        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        // actor (deleter) excluded, disabled dropped → only alice
        verify(mailService, times(1)).send(captor.capture());
        EmailMessage msg = captor.getValue();
        assertThat(msg.recipient()).isEqualTo("alice@passbolt.com");
        assertThat(msg.subject()).isEqualTo("Grace 删除了密码 AWS root");
        assertThat(msg.html())
                .doesNotContain("/app/passwords/view/")              // no live link
                .doesNotContain("db-admin");                         // show_username default off
    }

    @Test
    void delete_gateOff_SendsNothing() {
        settings.save(Map.of("send_password_delete", false), actorId);
        grantResourcePermission(aliceId, Permission.READ);

        deleteRedactor.onResourceDeleted(new ResourceDeletedEvent(
                RESOURCE_ID, "AWS root", null, null, null, false, actorId));

        verify(mailService, never()).send(any());
    }

    @Test
    void delete_OnlyDeleterHasAccess_SendsNothing() {
        grantResourcePermission(actorId, Permission.OWNER);

        deleteRedactor.onResourceDeleted(new ResourceDeletedEvent(
                RESOURCE_ID, "AWS root", null, null, null, false, actorId));

        verify(mailService, never()).send(any());
    }
}
