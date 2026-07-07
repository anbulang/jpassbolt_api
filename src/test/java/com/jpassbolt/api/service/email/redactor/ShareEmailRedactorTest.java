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
import com.jpassbolt.api.service.email.event.ResourceSharedEvent;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Deterministic test of {@link ShareEmailRedactor} called directly (not through
 * the {@code @Async}/{@code AFTER_COMMIT} proxy), with a mocked {@link MailService}
 * so the rendered {@link EmailMessage}s can be asserted synchronously. Covers the
 * master gate ({@code send.password.share}), recipient resolution (added users
 * minus the actor, minus disabled/deleted) and the default-off content gates.
 * The full publish→commit→async→SMTP wiring is proven separately by the GreenMail
 * e2e test. Seeds its own roles/users (DataInitializer only runs under
 * {@code local}); rolled back per test via {@code @Transactional}.
 */
@SpringBootTest
@Transactional
class ShareEmailRedactorTest {

    @Autowired private EmailNotificationSettingsService settings;
    @Autowired private RecipientResolver recipientResolver;
    @Autowired private EmailTemplateService templates;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ProfileRepository profileRepository;

    private MailService mailService;
    private ShareEmailRedactor redactor;

    private String userRoleId;
    private String actorId;
    private String aliceId;
    private String bobId;
    private String disabledId;

    @BeforeEach
    void setUp() {
        mailService = mock(MailService.class);
        redactor = new ShareEmailRedactor(settings, recipientResolver, templates,
                new NotificationDelivery(mailService));

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

    private ResourceSharedEvent event(String name, String uri) {
        return new ResourceSharedEvent(
                "res-1", name, "db-admin", uri, "prod database",
                false, actorId,
                new LinkedHashSet<>(List.of(actorId, aliceId, bobId, disabledId)),
                Map.of(aliceId, "-----BEGIN PGP MESSAGE-----alice", bobId, "-----BEGIN PGP MESSAGE-----bob"));
    }

    @Test
    void emailsAddedUsersExcludingActorAndDisabled() {
        redactor.onResourceShared(event("AWS root", "https://secret.example.com/login"));

        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(mailService, org.mockito.Mockito.times(2)).send(captor.capture());

        Set<String> recipients = captor.getAllValues().stream()
                .map(EmailMessage::recipient).collect(Collectors.toSet());
        // actor (sharer) and the disabled user get no email
        assertThat(recipients).containsExactlyInAnyOrder("alice@passbolt.com", "bob@passbolt.com");

        EmailMessage any = captor.getAllValues().get(0);
        assertThat(any.subject()).isEqualTo("Grace Hopper shared a password with you");
        assertThat(any.html())
                .contains("AWS root")                                 // resource name (v4)
                .contains("/app/passwords/view/res-1")                // SPA deep link
                .doesNotContain("https://secret.example.com/login")   // show_uri default OFF
                .doesNotContain("db-admin")                           // show_username default OFF
                .doesNotContain("BEGIN PGP MESSAGE");                 // show_secret default OFF
    }

    @Test
    void masterGateOffSendsNothing() {
        settings.save(Map.of("send_password_share", false), actorId);

        redactor.onResourceShared(event("AWS root", "https://secret.example.com/login"));

        verify(mailService, never()).send(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void contentGatesRevealFieldsWhenEnabled() {
        settings.save(Map.of("show_uri", true, "show_username", true, "show_secret", true), actorId);

        redactor.onResourceShared(event("AWS root", "https://secret.example.com/login"));

        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(mailService, org.mockito.Mockito.times(2)).send(captor.capture());

        EmailMessage alice = captor.getAllValues().stream()
                .filter(m -> m.recipient().equals("alice@passbolt.com")).findFirst().orElseThrow();
        assertThat(alice.html())
                .contains("https://secret.example.com/login")          // show_uri ON
                .contains("db-admin")                                  // show_username ON
                .contains("-----BEGIN PGP MESSAGE-----alice");         // her own ciphertext, show_secret ON
        // each recipient sees only their own secret, never another user's
        assertThat(alice.html()).doesNotContain("-----BEGIN PGP MESSAGE-----bob");
    }

    @Test
    void v5ResourceUsesNamelessIntro() {
        ResourceSharedEvent v5 = new ResourceSharedEvent(
                "res-9", null, null, null, null, true, actorId,
                new LinkedHashSet<>(List.of(aliceId)), Map.of());

        redactor.onResourceShared(v5);

        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(mailService).send(captor.capture());
        assertThat(captor.getValue().html())
                .contains("Grace Hopper shared a password with you")   // nameless intro
                .doesNotContain("shared the password");                 // not the named-intro variant
    }
}
