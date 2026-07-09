package com.jpassbolt.api.service.email;

import com.jpassbolt.api.model.AccountSetting;
import com.jpassbolt.api.model.Profile;
import com.jpassbolt.api.model.Role;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.AccountSettingRepository;
import com.jpassbolt.api.repository.ProfileRepository;
import com.jpassbolt.api.repository.RoleRepository;
import com.jpassbolt.api.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H2 integration test for {@link RecipientResolver}: dedupe, dropping
 * soft-deleted / disabled users, building the locale + name aggregate, the
 * locale fallback chain, and the new {@code findActiveAdmins} query. Seeds its
 * own roles/users (DataInitializer only runs under the {@code local} profile),
 * rolled back per test via {@code @Transactional}.
 */
@SpringBootTest
@Transactional
class RecipientResolverTest {

    @Autowired private RecipientResolver resolver;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ProfileRepository profileRepository;
    @Autowired private AccountSettingRepository accountSettingRepository;

    private String adminRoleId;
    private String userRoleId;

    @BeforeEach
    void seedRoles() {
        adminRoleId = saveRole("admin");
        userRoleId = saveRole("user");
    }

    private String saveRole(String name) {
        Role role = new Role();
        role.setName(name);
        return roleRepository.save(role).getId();
    }

    private User saveUser(String username, String roleId, boolean active, boolean deleted, LocalDateTime disabled) {
        User u = new User();
        u.setUsername(username);
        u.setRoleId(roleId);
        u.setActive(active);
        u.setDeleted(deleted);
        u.setDisabled(disabled);
        return userRepository.save(u);
    }

    private void saveProfile(String userId, String first, String last) {
        Profile p = new Profile();
        p.setUserId(userId);
        p.setFirstName(first);
        p.setLastName(last);
        profileRepository.save(p);
    }

    private void saveLocale(String userId, String code) {
        AccountSetting s = new AccountSetting();
        s.setUserId(userId);
        s.setProperty("locale");
        s.setPropertyId(UUID.randomUUID().toString());
        s.setValue(code);
        accountSettingRepository.save(s);
    }

    @Test
    void resolveUsers_dedupesAndDropsDeletedAndDisabled() {
        User active = saveUser("active@passbolt.com", userRoleId, true, false, null);
        User deleted = saveUser("deleted@passbolt.com", userRoleId, true, true, null);
        User disabled = saveUser("disabled@passbolt.com", userRoleId, true, false, LocalDateTime.now());

        Set<Recipient> recipients = resolver.resolveUsers(List.of(
                active.getId(), active.getId(), deleted.getId(), disabled.getId()));

        assertThat(recipients).extracting(Recipient::email)
                .containsExactly("active@passbolt.com"); // dedup + deleted/disabled removed
    }

    @Test
    void resolveUsers_keepsUserWithFutureDatedDisable() {
        // `disabled` is a timestamp: a future-dated value means the user is still
        // active now, so they must remain a valid recipient until that moment.
        User future = saveUser("future@passbolt.com", userRoleId, true, false, LocalDateTime.now().plusDays(1));

        assertThat(resolver.resolveUsers(List.of(future.getId())))
                .extracting(Recipient::email)
                .containsExactly("future@passbolt.com");
    }

    @Test
    void resolveUsers_keepsInactiveUsers() {
        // A not-yet-activated user (setup invite / recovery recipient) must NOT be dropped.
        User inactive = saveUser("pending@passbolt.com", userRoleId, false, false, null);

        assertThat(resolver.resolveUsers(List.of(inactive.getId())))
                .extracting(Recipient::email)
                .containsExactly("pending@passbolt.com");
    }

    @Test
    void resolveUsers_buildsLocaleAndNameFromProfileAndSetting() {
        User u = saveUser("zh@passbolt.com", userRoleId, true, false, null);
        saveProfile(u.getId(), "Xiao", "Ming");
        saveLocale(u.getId(), "zh-CN");

        Recipient r = resolver.resolveUsers(List.of(u.getId())).iterator().next();
        assertThat(r.email()).isEqualTo("zh@passbolt.com");
        assertThat(r.firstName()).isEqualTo("Xiao");
        assertThat(r.lastName()).isEqualTo("Ming");
        assertThat(r.fullName()).isEqualTo("Xiao Ming");
        assertThat(r.locale()).isEqualTo(new Locale("zh", "CN"));
    }

    @Test
    void resolveUsers_fallsBackToDefaultLocaleAndEmailNameWhenNothingSet() {
        User u = saveUser("nolocale@passbolt.com", userRoleId, true, false, null);

        Recipient r = resolver.resolveUsers(List.of(u.getId())).iterator().next();
        // No user/org locale seeded → product default zh-CN → Locale("zh","CN")
        assertThat(r.locale()).isEqualTo(new Locale("zh", "CN"));
        // No profile → display name falls back to the email
        assertThat(r.fullName()).isEqualTo("nolocale@passbolt.com");
    }

    @Test
    void resolveAllAdmins_returnsOnlyActiveNonDeletedAdmins() {
        saveUser("admin@passbolt.com", adminRoleId, true, false, null);
        saveUser("inactiveadmin@passbolt.com", adminRoleId, false, false, null);
        saveUser("deletedadmin@passbolt.com", adminRoleId, true, true, null);
        saveUser("regular@passbolt.com", userRoleId, true, false, null);

        Set<String> emails = resolver.resolveAllAdmins().stream()
                .map(Recipient::email).collect(Collectors.toSet());

        assertThat(emails).containsExactly("admin@passbolt.com");
    }
}
