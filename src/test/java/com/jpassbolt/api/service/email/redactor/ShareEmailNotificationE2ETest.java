package com.jpassbolt.api.service.email.redactor;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.jpassbolt.api.model.Profile;
import com.jpassbolt.api.model.Role;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.ProfileRepository;
import com.jpassbolt.api.repository.RoleRepository;
import com.jpassbolt.api.repository.UserRepository;
import com.jpassbolt.api.service.email.event.ResourceSharedEvent;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end proof of the notification wiring for the whole phase: a domain event
 * published inside a committed transaction must fire its
 * {@code @TransactionalEventListener(AFTER_COMMIT)} redactor on the
 * {@code mailExecutor} async thread, render per recipient, and actually deliver
 * over SMTP. Uses an in-memory GreenMail SMTP server. This is intentionally the
 * ONLY GreenMail test — every other redactor is verified deterministically
 * in-process (e.g. {@link ShareEmailRedactorTest}) since the async/commit/SMTP
 * path is identical and proven here once.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "jpassbolt.email.enabled=true",
        "jpassbolt.email.from=no-reply@jpassbolt.test",
        "spring.mail.host=127.0.0.1",
        "spring.mail.port=3025"
})
class ShareEmailNotificationE2ETest {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP)
            .withConfiguration(GreenMailConfiguration.aConfig().withDisabledAuthentication())
            .withPerMethodLifecycle(true);

    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ProfileRepository profileRepository;

    private final List<String> createdUserIds = new ArrayList<>();
    private String roleId;
    private String actorId;
    private String aliceId;
    private String bobId;

    @BeforeEach
    void seed() {
        Role role = new Role();
        role.setName("user");
        roleId = roleRepository.save(role).getId();
        actorId = saveUser("grace-e2e@passbolt.com", "Grace", "Hopper");
        aliceId = saveUser("alice-e2e@passbolt.com", "Alice", "Liddell");
        bobId = saveUser("bob-e2e@passbolt.com", "Bob", "Stone");
    }

    @AfterEach
    void cleanup() {
        // Committed rows (non-transactional test): remove what we created so the
        // shared Spring context is not polluted for other test classes.
        createdUserIds.forEach(id -> {
            profileRepository.findByUserId(id).ifPresent(profileRepository::delete);
            userRepository.deleteById(id);
        });
        roleRepository.deleteById(roleId);
    }

    private String saveUser(String email, String first, String last) {
        User u = new User();
        u.setUsername(email);
        u.setRoleId(roleId);
        u.setActive(true);
        u.setDeleted(false);
        String id = userRepository.save(u).getId();
        createdUserIds.add(id);
        Profile p = new Profile();
        p.setUserId(id);
        p.setFirstName(first);
        p.setLastName(last);
        profileRepository.save(p);
        return id;
    }

    @Test
    void shareEventDeliversOneEmailPerAddedUserOverSmtp() throws Exception {
        ResourceSharedEvent event = new ResourceSharedEvent(
                "res-e2e", "Shared AWS root", null, null, null, false, actorId,
                new LinkedHashSet<>(List.of(actorId, aliceId, bobId)), Map.of());

        // Publish inside a real, committed transaction so the AFTER_COMMIT
        // listener actually fires (a rolled-back tx would send nothing).
        new TransactionTemplate(txManager).executeWithoutResult(s -> eventPublisher.publishEvent(event));

        // The actor is excluded → exactly two emails (alice + bob), delivered async.
        assertThat(greenMail.waitForIncomingEmail(5000, 2)).isTrue();

        MimeMessage[] messages = greenMail.getReceivedMessages();
        Set<String> recipients = Stream.of(messages)
                .flatMap(m -> {
                    try {
                        return Stream.of(m.getAllRecipients());
                    } catch (Exception e) {
                        return Stream.empty();
                    }
                })
                .map(Object::toString)
                .collect(Collectors.toSet());
        assertThat(recipients).containsExactlyInAnyOrder("alice-e2e@passbolt.com", "bob-e2e@passbolt.com");
        assertThat(messages[0].getSubject()).isEqualTo("Grace Hopper shared a password with you");
    }
}
