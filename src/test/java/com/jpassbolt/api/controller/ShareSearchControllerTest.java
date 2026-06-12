package com.jpassbolt.api.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jpassbolt.api.model.GpgKey;
import com.jpassbolt.api.model.Group;
import com.jpassbolt.api.model.GroupUser;
import com.jpassbolt.api.model.Profile;
import com.jpassbolt.api.model.Role;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.GpgKeyRepository;
import com.jpassbolt.api.repository.GroupRepository;
import com.jpassbolt.api.repository.GroupUserRepository;
import com.jpassbolt.api.repository.PermissionRepository;
import com.jpassbolt.api.repository.ProfileRepository;
import com.jpassbolt.api.repository.ResourceRepository;
import com.jpassbolt.api.repository.RoleRepository;
import com.jpassbolt.api.repository.SecretRepository;
import com.jpassbolt.api.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for GET /share/search-aros.json (ShareController).
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = "test@example.com", roles = { "USER" })
class ShareSearchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private SecretRepository secretRepository;

    @Autowired
    private ResourceRepository resourceRepository;

    @Autowired
    private GpgKeyRepository gpgKeyRepository;

    @Autowired
    private ProfileRepository profileRepository;

    @Autowired
    private GroupUserRepository groupUserRepository;

    @Autowired
    private GroupRepository groupRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private Role userRole;
    private Role guestRole;
    private User testUser;
    private User adaUser;

    @BeforeEach
    void setUp() {
        // Clear in reverse FK-dependency order
        permissionRepository.deleteAll();
        secretRepository.deleteAll();
        resourceRepository.deleteAll();
        gpgKeyRepository.deleteAll();
        profileRepository.deleteAll();
        groupUserRepository.deleteAll();
        groupRepository.deleteAll();
        userRepository.deleteAll();
        roleRepository.deleteAll();

        userRole = createRole("user", "Logged in user");
        guestRole = createRole("guest", "Non logged in user");

        // Current authenticated user (must match @WithMockUser username)
        testUser = createUserWithProfile("test@example.com", "Test", "Tester",
                true, false, userRole.getId());

        // The searchable reference user, with a gpgkey
        adaUser = createUserWithProfile("ada@passbolt.com", "Ada", "Lovelace",
                true, false, userRole.getId());
        createGpgKeyFor(adaUser);
    }

    // ------------------------------------------------------------------
    // Positive cases
    // ------------------------------------------------------------------

    @Test
    void testSearchByUsername_CaseInsensitive() throws Exception {
        mockMvc.perform(get("/share/search-aros.json").param("filter[search]", "AdA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.body.length()").value(1))
                .andExpect(jsonPath("$.body[0].username").value("ada@passbolt.com"));
    }

    @Test
    void testSearchByFirstName_CaseInsensitive() throws Exception {
        createUserWithProfile("carol@example.com", "Zelda", "Williams",
                true, false, userRole.getId());

        mockMvc.perform(get("/share/search-aros.json").param("filter[search]", "zELd"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.length()").value(1))
                .andExpect(jsonPath("$.body[0].username").value("carol@example.com"));
    }

    @Test
    void testSearchByLastName_CaseInsensitive() throws Exception {
        createUserWithProfile("carol@example.com", "Zelda", "Williams",
                true, false, userRole.getId());

        mockMvc.perform(get("/share/search-aros.json").param("filter[search]", "willIAMS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.length()").value(1))
                .andExpect(jsonPath("$.body[0].username").value("carol@example.com"));
    }

    @Test
    void testEmptySearchReturnsAll() throws Exception {
        // test@example.com + ada@passbolt.com
        mockMvc.perform(get("/share/search-aros.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.body.length()").value(2));
    }

    @Test
    void testExcludesInactiveDeletedAndGuestUsers() throws Exception {
        createUserWithProfile("inactive@example.com", "In", "Active",
                false, false, userRole.getId());
        createUserWithProfile("softdeleted@example.com", "Soft", "Deleted",
                true, true, userRole.getId());
        createUserWithProfile("ghost@example.com", "Guest", "Account",
                true, false, guestRole.getId());

        MvcResult result = mockMvc.perform(get("/share/search-aros.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.length()").value(2))
                .andReturn();

        List<String> usernames = extractStrings(result, "username");
        assertThat(usernames)
                .containsExactlyInAnyOrder("ada@passbolt.com", "test@example.com")
                .doesNotContain("inactive@example.com", "softdeleted@example.com",
                        "ghost@example.com");
    }

    @Test
    void testDefaultContainsIncludeProfileGpgkeyRole() throws Exception {
        // No contain parameter at all → everything defaults to on
        mockMvc.perform(get("/share/search-aros.json").param("filter[search]", "ada"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body[0].profile.first_name").value("Ada"))
                .andExpect(jsonPath("$.body[0].gpgkey.armored_key").exists())
                .andExpect(jsonPath("$.body[0].role.name").value("user"))
                .andExpect(jsonPath("$.body[0].groups_users").isArray())
                .andExpect(jsonPath("$.body[0].last_logged_in").value(org.hamcrest.CoreMatchers.nullValue()));
    }

    @Test
    void testExplicitContainDisablesOthers() throws Exception {
        // contain[gpgkey]=1 present → only gpgkey is rendered, role and
        // groups_users disappear; profile is NOT whitelisted and stays.
        mockMvc.perform(get("/share/search-aros.json")
                .param("filter[search]", "ada")
                .param("contain[gpgkey]", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body[0].gpgkey.armored_key").exists())
                .andExpect(jsonPath("$.body[0].profile.first_name").value("Ada"))
                .andExpect(jsonPath("$.body[0].role").doesNotExist())
                .andExpect(jsonPath("$.body[0].groups_users").doesNotExist());
    }

    @Test
    void testSortedByUsernameAsc() throws Exception {
        createUserWithProfile("bob@example.com", "Bob", "Builder",
                true, false, userRole.getId());
        createUserWithProfile("alice@example.com", "Alice", "Wonder",
                true, false, userRole.getId());

        mockMvc.perform(get("/share/search-aros.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.length()").value(4))
                .andExpect(jsonPath("$.body[0].username").value("ada@passbolt.com"))
                .andExpect(jsonPath("$.body[1].username").value("alice@example.com"))
                .andExpect(jsonPath("$.body[2].username").value("bob@example.com"))
                .andExpect(jsonPath("$.body[3].username").value("test@example.com"));
    }

    @Test
    void testLimit25() throws Exception {
        // 30 extra users + test + ada = 32 candidates, users capped at 25
        for (int i = 1; i <= 30; i++) {
            createUserWithProfile(String.format("user%02d@example.com", i),
                    "User", "Number" + i, true, false, userRole.getId());
        }

        mockMvc.perform(get("/share/search-aros.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.length()").value(25));
    }

    @Test
    void testSearchMatchesGroupByName() throws Exception {
        Group group = new Group();
        group.setName("Accounting");
        group.setDeleted(false);
        group.setCreatedBy(testUser.getId());
        group.setModifiedBy(testUser.getId());
        groupRepository.save(group);

        GroupUser membership = new GroupUser();
        membership.setGroupId(group.getId());
        membership.setUserId(adaUser.getId());
        membership.setIsAdmin(true);
        groupUserRepository.save(membership);

        mockMvc.perform(get("/share/search-aros.json").param("filter[search]", "accounting"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.length()").value(1))
                .andExpect(jsonPath("$.body[0].name").value("Accounting"))
                .andExpect(jsonPath("$.body[0].user_count").value(1));
    }

    // ------------------------------------------------------------------
    // Negative cases
    // ------------------------------------------------------------------

    @Test
    @WithAnonymousUser
    void testUnauthenticated() throws Exception {
        // OpenAPI contract says 401, but SecurityConfig has no
        // authenticationEntryPoint so unauthenticated requests get 403 —
        // existing project-wide behaviour, asserted as-is.
        mockMvc.perform(get("/share/search-aros.json"))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Role createRole(String name, String description) {
        Role role = new Role();
        role.setName(name);
        role.setDescription(description);
        return roleRepository.save(role);
    }

    private User createUserWithProfile(String username, String firstName, String lastName,
            boolean active, boolean deleted, String roleId) {
        User user = new User();
        user.setUsername(username);
        user.setRoleId(roleId);
        user.setActive(active);
        user.setDeleted(deleted);
        user = userRepository.save(user);

        Profile profile = new Profile();
        profile.setUserId(user.getId());
        profile.setFirstName(firstName);
        profile.setLastName(lastName);
        profileRepository.save(profile);

        return user;
    }

    private GpgKey createGpgKeyFor(User user) {
        GpgKey key = new GpgKey();
        key.setUserId(user.getId());
        key.setArmoredKey("-----BEGIN PGP PUBLIC KEY BLOCK-----\ntest\n-----END PGP PUBLIC KEY BLOCK-----");
        key.setUid(user.getUsername());
        key.setKeyId("0123456789ABCDEF");
        key.setFingerprint("0123456789ABCDEF0123456789ABCDEF01234567");
        key.setType("RSA");
        key.setBits(4096);
        key.setDeleted(false);
        return gpgKeyRepository.save(key);
    }

    private List<String> extractStrings(MvcResult result, String field) throws Exception {
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString()).get("body");
        List<String> values = new ArrayList<>();
        for (JsonNode node : body) {
            if (node.hasNonNull(field)) {
                values.add(node.get(field).asText());
            }
        }
        return values;
    }
}
