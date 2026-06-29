package com.jpassbolt.api.service;

import com.jpassbolt.api.dto.SelfRegistrationDto;
import com.jpassbolt.api.exception.PassboltApiException;
import com.jpassbolt.api.model.Role;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.RoleRepository;
import com.jpassbolt.api.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Direct tests of {@link SelfRegistrationService}: settings round-trip + validation,
 * the disabled/open state, and the full guest gate (email format, disabled, domain
 * allow-list, duplicate email, case-insensitivity, soft-deleted reuse).
 *
 * <p>{@code @Transactional} so each test rolls back; the AFTER_COMMIT settings-changed
 * email listener therefore never fires, keeping these unit-level tests free of mail
 * side effects.</p>
 */
@SpringBootTest
@Transactional
class SelfRegistrationServiceTest {

    @Autowired private SelfRegistrationService service;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;

    private String adminId;

    @BeforeEach
    void setUp() {
        Role role = new Role();
        role.setName(Role.ADMIN);
        String roleId = roleRepository.save(role).getId();

        User admin = new User();
        admin.setUsername("admin@passbolt.com");
        admin.setRoleId(roleId);
        admin.setActive(true);
        admin.setDeleted(false);
        adminId = userRepository.save(admin).getId();
    }

    private SelfRegistrationDto.SettingsRequest request(String provider, List<String> domains) {
        return SelfRegistrationDto.SettingsRequest.builder()
                .provider(provider)
                .data(SelfRegistrationDto.DataPayload.builder().allowedDomains(domains).build())
                .build();
    }

    private void openWith(String... domains) {
        service.save(request(SelfRegistrationService.PROVIDER_EMAIL_DOMAINS, List.of(domains)), adminId);
    }

    private User saveUser(String username, boolean deleted) {
        User u = new User();
        u.setUsername(username);
        u.setRoleId(roleRepository.findByName(Role.ADMIN).orElseThrow().getId());
        u.setActive(true);
        u.setDeleted(deleted);
        return userRepository.save(u);
    }

    // ---- settings ----

    @Test
    void getReturnsDisabledDefaultWhenNoRow() {
        Map<String, Object> settings = service.get();
        assertThat(settings.get("provider")).isNull();
        assertThat(settings.get("data")).isNull();
        assertThat(service.isOpen()).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void saveThenGetRoundtrips() {
        openWith("passbolt.com", "example.org");
        Map<String, Object> settings = service.get();
        assertThat(settings.get("provider")).isEqualTo("email_domains");
        assertThat(settings.get("id")).isNotNull();
        Map<String, Object> data = (Map<String, Object>) settings.get("data");
        assertThat((List<String>) data.get("allowed_domains"))
                .containsExactly("passbolt.com", "example.org");
        assertThat(service.isOpen()).isTrue();
    }

    @Test
    void saveRejectsUnknownProvider() {
        PassboltApiException ex = catchThrowableOfType(
                () -> service.save(request("ldap", List.of("passbolt.com")), adminId),
                PassboltApiException.class);
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void saveRejectsEmptyDomainList() {
        PassboltApiException ex = catchThrowableOfType(
                () -> service.save(request("email_domains", List.of()), adminId),
                PassboltApiException.class);
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void saveRejectsInvalidDomains() {
        // leading @ and a domain with no dot are both invalid bare domains
        assertThat(catchThrowableOfType(
                () -> service.save(request("email_domains", List.of("@passbolt.com")), adminId),
                PassboltApiException.class).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(catchThrowableOfType(
                () -> service.save(request("email_domains", List.of("localdomain")), adminId),
                PassboltApiException.class).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void deleteRequiresMatchingUuidAndDisables() {
        openWith("passbolt.com");
        String id = (String) service.get().get("id");

        // wrong id -> 404
        assertThat(catchThrowableOfType(
                () -> service.delete("00000000-0000-0000-0000-000000000000", adminId),
                PassboltApiException.class).getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        // non-uuid -> 400
        assertThat(catchThrowableOfType(
                () -> service.delete("not-a-uuid", adminId),
                PassboltApiException.class).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);

        // matching id -> disabled
        Map<String, Object> after = service.delete(id, adminId);
        assertThat(after.get("provider")).isNull();
        assertThat(service.isOpen()).isFalse();
    }

    // ---- guest gate ----

    @Test
    void gateAllowsAllowlistedNewEmail() {
        openWith("passbolt.com");
        assertThatCode(() -> service.canGuestSelfRegister("new.user@passbolt.com"))
                .doesNotThrowAnyException();
    }

    @Test
    void gateRejectsInvalidEmailWith400() {
        openWith("passbolt.com");
        assertThat(catchThrowableOfType(
                () -> service.canGuestSelfRegister("not-an-email"),
                PassboltApiException.class).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void gateRejectsWhenDisabledWith403() {
        // no settings row at all
        assertThat(catchThrowableOfType(
                () -> service.canGuestSelfRegister("new@passbolt.com"),
                PassboltApiException.class).getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void gateRejectsDisallowedDomainWith422() {
        openWith("passbolt.com");
        assertThat(catchThrowableOfType(
                () -> service.canGuestSelfRegister("new@evil.com"),
                PassboltApiException.class).getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void gateRejectsAlreadyRegisteredEmailWith403() {
        openWith("passbolt.com");
        saveUser("taken@passbolt.com", false);
        assertThat(catchThrowableOfType(
                () -> service.canGuestSelfRegister("taken@passbolt.com"),
                PassboltApiException.class).getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void gateIsCaseInsensitiveOnDomain() {
        openWith("Passbolt.com");
        assertThatCode(() -> service.canGuestSelfRegister("New@PASSBOLT.COM"))
                .doesNotThrowAnyException();
    }

    @Test
    void gateRejectsSubdomainNotExplicitlyAllowed() {
        openWith("passbolt.com");
        assertThat(catchThrowableOfType(
                () -> service.canGuestSelfRegister("x@mail.passbolt.com"),
                PassboltApiException.class).getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void gateTreatsSoftDeletedEmailAsAvailable() {
        openWith("passbolt.com");
        saveUser("gone@passbolt.com", true); // soft-deleted -> email is free again
        assertThatCode(() -> service.canGuestSelfRegister("gone@passbolt.com"))
                .doesNotThrowAnyException();
    }
}
