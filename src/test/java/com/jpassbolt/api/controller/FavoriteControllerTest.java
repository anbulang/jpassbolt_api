package com.jpassbolt.api.controller;

import com.jpassbolt.api.model.Favorite;
import com.jpassbolt.api.model.Permission;
import com.jpassbolt.api.model.Resource;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.FavoriteRepository;
import com.jpassbolt.api.repository.PermissionRepository;
import com.jpassbolt.api.repository.ResourceRepository;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for FavoriteController (POST add / DELETE remove).
 *
 * Key semantics under test (ported from PHP Favorites controllers/services):
 * - missing / soft-deleted / no-READ-access resource → 404 (NOT 403, anti-enumeration)
 * - deleting a favorite that is missing or owned by someone else → 404 (NOT 403)
 * - duplicate favorite → 400 (application-layer uniqueness, no DB constraint)
 * - delete success body is literal JSON null (spec nullBody)
 * - both plural (/favorites, plugin) and singular (/favorite, OpenAPI) paths work
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = "test@example.com", roles = { "USER" })
class FavoriteControllerTest {

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private FavoriteRepository favoriteRepository;

        @Autowired
        private PermissionRepository permissionRepository;

        @Autowired
        private SecretRepository secretRepository;

        @Autowired
        private ResourceRepository resourceRepository;

        @Autowired
        private UserRepository userRepository;

        private User testUser;
        private User otherUser;

        @BeforeEach
        void setUp() {
                favoriteRepository.deleteAll();
                permissionRepository.deleteAll();
                secretRepository.deleteAll();
                resourceRepository.deleteAll();
                userRepository.deleteAll();

                testUser = new User();
                testUser.setUsername("test@example.com");
                testUser.setRoleId("user");
                testUser.setActive(true);
                testUser.setDeleted(false);
                userRepository.save(testUser);

                otherUser = new User();
                otherUser.setUsername("other@example.com");
                otherUser.setRoleId("user");
                otherUser.setActive(true);
                otherUser.setDeleted(false);
                userRepository.save(otherUser);
        }

        /**
         * Helper to create a resource with a permission of the given type for the
         * given user.
         */
        private Resource createResourceWithPermission(String name, User user, int permType) {
                Resource resource = new Resource();
                resource.setName(name);
                resource.setCreatedBy(user.getId());
                resource.setModifiedBy(user.getId());
                resource.setDeleted(false);
                resourceRepository.save(resource);

                Permission perm = new Permission();
                perm.setAco(Permission.RESOURCE_ACO);
                perm.setAcoForeignKey(resource.getId());
                perm.setAro(Permission.USER_ARO);
                perm.setAroForeignKey(user.getId());
                perm.setType(permType);
                permissionRepository.save(perm);

                return resource;
        }

        private Favorite createFavorite(String userId, String resourceId) {
                return favoriteRepository.save(
                                new Favorite(userId, resourceId, Favorite.FOREIGN_MODEL_RESOURCE));
        }

        // -------------------------------------------------------------------------
        // POST /favorites/resource/{foreignId}.json
        // -------------------------------------------------------------------------

        @Test
        void testAddFavoriteSuccess() throws Exception {
                Resource resource = createResourceWithPermission("Readable", testUser, Permission.READ);

                mockMvc.perform(post("/favorites/resource/" + resource.getId() + ".json"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.header.status").value("success"))
                                .andExpect(jsonPath("$.header.message")
                                                .value("The resource was marked as favorite."))
                                .andExpect(jsonPath("$.body.id").isNotEmpty())
                                .andExpect(jsonPath("$.body.user_id").value(testUser.getId()))
                                .andExpect(jsonPath("$.body.foreign_key").value(resource.getId()))
                                // capitalized "Resource" in the body, unlike the lowercase path segment
                                .andExpect(jsonPath("$.body.foreign_model").value("Resource"))
                                .andExpect(jsonPath("$.body.created").isNotEmpty())
                                .andExpect(jsonPath("$.body.modified").isNotEmpty());

                assertThat(favoriteRepository.existsByUserIdAndForeignKey(
                                testUser.getId(), resource.getId())).isTrue();
                assertThat(favoriteRepository.count()).isEqualTo(1);
        }

        @Test
        void testAddFavoriteSingularPath() throws Exception {
                Resource resource = createResourceWithPermission("Singular", testUser, Permission.READ);

                // The OpenAPI spec registers the singular /favorite/... path
                mockMvc.perform(post("/favorite/resource/" + resource.getId() + ".json"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.header.status").value("success"))
                                .andExpect(jsonPath("$.body.foreign_model").value("Resource"));
        }

        @Test
        void testAddFavoriteWithUpdateAndOwnerPermission() throws Exception {
                // userHasAccess is ">= READ" semantics: UPDATE and OWNER both qualify
                Resource updatable = createResourceWithPermission("Updatable", testUser, Permission.UPDATE);
                Resource owned = createResourceWithPermission("Owned", testUser, Permission.OWNER);

                mockMvc.perform(post("/favorites/resource/" + updatable.getId() + ".json"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.header.status").value("success"));

                mockMvc.perform(post("/favorites/resource/" + owned.getId() + ".json"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.header.status").value("success"));
        }

        @Test
        void testAddFavoriteInvalidUuid() throws Exception {
                mockMvc.perform(post("/favorites/resource/not-a-uuid.json"))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.header.status").value("error"))
                                .andExpect(jsonPath("$.header.message", containsString("valid UUID")));
        }

        @Test
        void testAddFavoriteInvalidForeignModel() throws Exception {
                // PHP only routes /favorites/resource/{id}; any other model never matches → 404
                mockMvc.perform(post("/favorites/user/" + UUID.randomUUID() + ".json"))
                                .andExpect(status().isNotFound())
                                .andExpect(jsonPath("$.header.status").value("error"));
        }

        @Test
        void testAddFavoriteResourceNotFound() throws Exception {
                mockMvc.perform(post("/favorites/resource/" + UUID.randomUUID() + ".json"))
                                .andExpect(status().isNotFound())
                                .andExpect(jsonPath("$.header.status").value("error"))
                                .andExpect(jsonPath("$.header.message")
                                                .value("The resource does not exist."));
        }

        @Test
        void testAddFavoriteSoftDeletedResource() throws Exception {
                Resource resource = createResourceWithPermission("Soft deleted", testUser, Permission.OWNER);
                resource.setDeleted(true);
                resourceRepository.save(resource);

                mockMvc.perform(post("/favorites/resource/" + resource.getId() + ".json"))
                                .andExpect(status().isNotFound())
                                .andExpect(jsonPath("$.header.message")
                                                .value("The resource does not exist."));
        }

        @Test
        void testAddFavoriteNoPermission() throws Exception {
                // Resource fully owned by otherUser; testUser has NO permission at all.
                Resource resource = createResourceWithPermission("Foreign", otherUser, Permission.OWNER);

                // Must be 404, NOT 403: PHP has_resource_access failures map to
                // NotFoundException to prevent resource-existence enumeration.
                // (Deliberately different from ResourceController's 403 guards.)
                mockMvc.perform(post("/favorites/resource/" + resource.getId() + ".json"))
                                .andExpect(status().isNotFound())
                                .andExpect(jsonPath("$.header.status").value("error"))
                                .andExpect(jsonPath("$.header.message")
                                                .value("The resource does not exist."));

                assertThat(favoriteRepository.count()).isZero();
        }

        @Test
        void testAddFavoriteDuplicate() throws Exception {
                Resource resource = createResourceWithPermission("Twice", testUser, Permission.READ);

                mockMvc.perform(post("/favorites/resource/" + resource.getId() + ".json"))
                                .andExpect(status().isOk());

                mockMvc.perform(post("/favorites/resource/" + resource.getId() + ".json"))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.header.status").value("error"))
                                .andExpect(jsonPath("$.header.message",
                                                containsString("already marked as favorite")));

                // Application-layer uniqueness: still exactly one row
                assertThat(favoriteRepository.count()).isEqualTo(1);
        }

        // -------------------------------------------------------------------------
        // DELETE /favorites/{favoriteId}.json
        // -------------------------------------------------------------------------

        @Test
        void testDeleteFavoriteSuccess() throws Exception {
                Resource resource = createResourceWithPermission("Starred", testUser, Permission.READ);
                Favorite favorite = createFavorite(testUser.getId(), resource.getId());

                mockMvc.perform(delete("/favorites/" + favorite.getId() + ".json"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.header.status").value("success"))
                                .andExpect(jsonPath("$.header.message").value("The favorite was deleted."))
                                // spec nullBody: body must be literal JSON null, not {}
                                .andExpect(jsonPath("$.body").value(nullValue()))
                                .andExpect(content().string(containsString("\"body\":null")));

                // Hard delete: the row is physically gone
                assertThat(favoriteRepository.findById(favorite.getId())).isEmpty();
        }

        @Test
        void testDeleteFavoriteSingularPath() throws Exception {
                Resource resource = createResourceWithPermission("Starred singular", testUser, Permission.READ);
                Favorite favorite = createFavorite(testUser.getId(), resource.getId());

                mockMvc.perform(delete("/favorite/" + favorite.getId() + ".json"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.header.status").value("success"));
        }

        @Test
        void testDeleteFavoriteInvalidUuid() throws Exception {
                mockMvc.perform(delete("/favorites/abc.json"))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.header.status").value("error"))
                                .andExpect(jsonPath("$.header.message").value("The favorite id is not valid."));
        }

        @Test
        void testDeleteFavoriteNotFound() throws Exception {
                mockMvc.perform(delete("/favorites/" + UUID.randomUUID() + ".json"))
                                .andExpect(status().isNotFound())
                                .andExpect(jsonPath("$.header.status").value("error"))
                                .andExpect(jsonPath("$.header.message").value("The favorite does not exist."));
        }

        @Test
        void testDeleteOtherUsersFavorite() throws Exception {
                Resource resource = createResourceWithPermission("Other's star", otherUser, Permission.OWNER);
                Favorite favorite = createFavorite(otherUser.getId(), resource.getId());

                // Must be 404, NOT 403: PHP is_owner rule failure maps to NotFoundException
                // so the existence of someone else's favorite is never leaked.
                mockMvc.perform(delete("/favorites/" + favorite.getId() + ".json"))
                                .andExpect(status().isNotFound())
                                .andExpect(jsonPath("$.header.status").value("error"))
                                .andExpect(jsonPath("$.header.message").value("The favorite does not exist."));

                // The row still exists
                assertThat(favoriteRepository.findById(favorite.getId())).isPresent();
        }

        // -------------------------------------------------------------------------
        // Authentication
        // -------------------------------------------------------------------------

        @Test
        @WithAnonymousUser
        void testUnauthenticated() throws Exception {
                // The OpenAPI contract specifies 401, which SecurityConfig now returns
                // via its authenticationEntryPoint (HttpStatusEntryPoint UNAUTHORIZED).
                // Both the index and per-id endpoint return 401 here.
                mockMvc.perform(post("/favorites/resource/" + UUID.randomUUID() + ".json"))
                                .andExpect(status().isUnauthorized());

                mockMvc.perform(delete("/favorites/" + UUID.randomUUID() + ".json"))
                                .andExpect(status().isUnauthorized());
        }
}
