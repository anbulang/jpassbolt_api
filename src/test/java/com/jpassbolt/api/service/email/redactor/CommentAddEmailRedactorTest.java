package com.jpassbolt.api.service.email.redactor;

import com.jpassbolt.api.model.Permission;
import com.jpassbolt.api.model.Profile;
import com.jpassbolt.api.model.Resource;
import com.jpassbolt.api.model.Role;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.PermissionRepository;
import com.jpassbolt.api.repository.ProfileRepository;
import com.jpassbolt.api.repository.ResourceRepository;
import com.jpassbolt.api.repository.RoleRepository;
import com.jpassbolt.api.repository.UserRepository;
import com.jpassbolt.api.service.EmailNotificationSettingsService;
import com.jpassbolt.api.service.email.EmailMessage;
import com.jpassbolt.api.service.email.EmailTemplateService;
import com.jpassbolt.api.service.email.MailService;
import com.jpassbolt.api.service.email.RecipientResolver;
import com.jpassbolt.api.service.email.event.ResourceCommentedEvent;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Deterministic test of {@link CommentAddEmailRedactor} (called directly, mocked
 * {@link MailService}). Covers the master gate ({@code send.comment.add}),
 * recipient resolution (everyone with access to the resource minus the commenter)
 * and the default-off {@code show_comment} body gate. Async/SMTP wiring is proven
 * once by {@code ShareEmailNotificationE2ETest}.
 */
@SpringBootTest
@Transactional
class CommentAddEmailRedactorTest {

    @Autowired private EmailNotificationSettingsService settings;
    @Autowired private RecipientResolver recipientResolver;
    @Autowired private EmailTemplateService templates;
    @Autowired private ResourceRepository resourceRepository;
    @Autowired private PermissionRepository permissionRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ProfileRepository profileRepository;

    private MailService mailService;
    private CommentAddEmailRedactor redactor;

    private String userRoleId;
    private String actorId;
    private String resourceId;

    @BeforeEach
    void setUp() {
        mailService = mock(MailService.class);
        redactor = new CommentAddEmailRedactor(settings, recipientResolver, templates,
                new NotificationDelivery(mailService), resourceRepository);

        Role role = new Role();
        role.setName("user");
        userRoleId = roleRepository.save(role).getId();

        actorId = saveUser("grace@passbolt.com", "Grace", "Hopper");
        String aliceId = saveUser("alice@passbolt.com", "Alice", "Liddell");
        String bobId = saveUser("bob@passbolt.com", "Bob", "Stone");

        Resource resource = new Resource();
        resource.setName("Prod DB");
        resource.setUsername("admin");
        resource.setUri("https://db.example.com");
        resource.setCreatedBy(actorId);
        resource.setModifiedBy(actorId);
        resource.setDeleted(false);
        resourceId = resourceRepository.save(resource).getId();

        grant(actorId, Permission.OWNER);
        grant(aliceId, Permission.READ);
        grant(bobId, Permission.READ);
    }

    private String saveUser(String email, String first, String last) {
        User u = new User();
        u.setUsername(email);
        u.setRoleId(userRoleId);
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

    private void grant(String userId, int type) {
        Permission perm = new Permission();
        perm.setAco(Permission.RESOURCE_ACO);
        perm.setAcoForeignKey(resourceId);
        perm.setAro(Permission.USER_ARO);
        perm.setAroForeignKey(userId);
        perm.setType(type);
        permissionRepository.save(perm);
    }

    private ResourceCommentedEvent event() {
        return new ResourceCommentedEvent("comment-1", resourceId, actorId, "Looks good to me");
    }

    @Test
    void notifiesAccessHoldersExceptCommenter() {
        redactor.onResourceCommented(event());

        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(mailService, times(2)).send(captor.capture());

        Set<String> recipients = captor.getAllValues().stream()
                .map(EmailMessage::recipient).collect(Collectors.toSet());
        assertThat(recipients).containsExactlyInAnyOrder("alice@passbolt.com", "bob@passbolt.com");

        EmailMessage any = captor.getAllValues().get(0);
        assertThat(any.subject()).isEqualTo("Grace Hopper 评论了 Prod DB");
        assertThat(any.html())
                .contains("Prod DB")
                .contains("/app/passwords/view/" + resourceId)
                .doesNotContain("Looks good to me"); // show_comment default OFF
    }

    @Test
    void masterGateOffSendsNothing() {
        settings.save(Map.of("send_comment_add", false), actorId);
        redactor.onResourceCommented(event());
        verify(mailService, never()).send(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void showCommentRevealsBody() {
        settings.save(Map.of("show_comment", true), actorId);
        redactor.onResourceCommented(event());

        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(mailService, times(2)).send(captor.capture());
        assertThat(captor.getAllValues().get(0).html()).contains("Looks good to me");
    }
}
