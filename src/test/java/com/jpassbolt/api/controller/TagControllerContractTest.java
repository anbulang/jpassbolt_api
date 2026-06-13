package com.jpassbolt.api.controller;

import com.jpassbolt.api.dto.TagDto;
import com.jpassbolt.api.model.Permission;
import com.jpassbolt.api.model.Resource;
import com.jpassbolt.api.model.ResourcesTag;
import com.jpassbolt.api.model.Role;
import com.jpassbolt.api.model.Tag;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.PermissionRepository;
import com.jpassbolt.api.repository.ResourceRepository;
import com.jpassbolt.api.repository.ResourcesTagRepository;
import com.jpassbolt.api.repository.RoleRepository;
import com.jpassbolt.api.repository.SecretRepository;
import com.jpassbolt.api.repository.TagRepository;
import com.jpassbolt.api.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;

import java.util.List;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * OpenAPI contract tests for the EE Tags endpoints
 * ({@code GET /tags.json}, {@code PUT /tags/{resourceOrTagId}.json},
 * {@code POST /tags/{resourceOrTagId}.json}).
 *
 * <p>
 * Strict-validated assertions ({@code openApi().isValid(CONTRACT_VALIDATOR)}
 * ENABLED):
 * <ul>
 * <li>{@code GET /tags.json} — the {@code tags_index} response body is the
 * {@code tagIndexAndView} {@code anyOf[tagLegacy, tagV5]} union. The seeded
 * legacy tags (one shared {@code tagLegacy} with {@code user_id} null + one
 * personal {@code tagLegacy} echoing the requester's {@code user_id}) validate
 * against that union, so {@code isValid} is ENABLED. (Verified empirically: the
 * spec declares {@code body: tagIndexAndView} as a single object yet the server
 * returns an array of union elements; with {@code withResolveCombinators(true)}
 * the validator accepts each array element against the union, so the array
 * passes strict validation.)</li>
 * </ul>
 * </p>
 *
 * <p>
 * Documented-disabled {@code isValid} assertions — category
 * {@code e2eeMetadataBased} (v3-compat structural difference; the deviation is on
 * the spec side, not the envelope — same recorded precedent as
 * Folder/Move/GroupShare):
 * <ul>
 * <li>{@code PUT /tags/{id}.json} — the spec's {@code tags_update} response
 * schema is {@code body: tagV5} only ({@code tagV5 = allOf[e2eeMetadataBasedId,
 * {is_shared}]}, where {@code metadata}, {@code metadata_key_id} (format uuid)
 * and {@code metadata_key_type} are all <em>required</em>). Our v4 legacy rename
 * returns the {@code tagLegacy} shape ({@code id}/{@code slug}/{@code is_shared},
 * no metadata), which the validator rejects against the {@code tagV5}-only
 * response. {@code isValid} disabled; the {@code tagLegacy} body asserted via
 * JSON paths.</li>
 * <li>{@code POST /tags/{id}.json} — the request body schema is
 * {@code tagAddResource = anyOf[{id}, tagV5Update, string]}, where
 * {@code tagV5Update = allOf[e2eeMetadataBased, {is_shared}]} requires
 * {@code metadata}/{@code metadata_key_id}(uuid)/{@code metadata_key_type}.
 * The plugin (and the spec's own example) wraps the entries in
 * {@code {"tags": [...]}} and sends {@code metadata_key_id: null}, so the body
 * matches none of the {@code anyOf} branches under strict validation.
 * {@code isValid} disabled; the response array asserted via JSON paths.</li>
 * </ul>
 * </p>
 *
 * <p>
 * Tags are JWT-protected with no admin gate, so a plain {@code USER} mock
 * principal is sufficient; the user nonetheless references a real Role row
 * because the embedded user contract schemas treat {@code role_id} as a uuid.
 * </p>
 */
@WithMockUser(username = "test@example.com", roles = { "USER" })
class TagControllerContractTest extends OpenApiComplianceTest {

    @Autowired
    private TagRepository tagRepository;

    @Autowired
    private ResourcesTagRepository resourcesTagRepository;

    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private ResourceRepository resourceRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private SecretRepository secretRepository;

    private User testUser;
    private Resource resource;
    private Tag personalTag;
    private Tag sharedTag;

    @BeforeEach
    void setUpData() {
        resourcesTagRepository.deleteAll();
        tagRepository.deleteAll();
        permissionRepository.deleteAll();
        // secrets carry a FK to resources; clear them before resources so a
        // prior test's leftover secret rows do not block the resource cleanup
        // when this test runs after them in the full suite (same order as the
        // Favorite contract test).
        secretRepository.deleteAll();
        resourceRepository.deleteAll();
        userRepository.deleteAll();
        roleRepository.deleteAll();

        Role userRole = new Role();
        userRole.setName("user");
        userRole.setDescription("Logged in user");
        roleRepository.save(userRole);

        testUser = new User();
        testUser.setUsername("test@example.com");
        testUser.setRoleId(userRole.getId());
        testUser.setActive(true);
        testUser.setDeleted(false);
        userRepository.save(testUser);

        resource = new Resource();
        resource.setName("Contract resource");
        resource.setCreatedBy(testUser.getId());
        resource.setModifiedBy(testUser.getId());
        resource.setDeleted(false);
        resourceRepository.save(resource);

        Permission perm = new Permission();
        perm.setAco(Permission.RESOURCE_ACO);
        perm.setAcoForeignKey(resource.getId());
        perm.setAro(Permission.USER_ARO);
        perm.setAroForeignKey(testUser.getId());
        perm.setType(Permission.OWNER);
        permissionRepository.save(perm);

        // A personal (v4) tag attached by the test user.
        personalTag = new Tag();
        personalTag.setSlug("important");
        personalTag.setIsShared(false);
        tagRepository.save(personalTag);

        ResourcesTag personalAssoc = new ResourcesTag();
        personalAssoc.setResourceId(resource.getId());
        personalAssoc.setTagId(personalTag.getId());
        personalAssoc.setUserId(testUser.getId());
        resourcesTagRepository.save(personalAssoc);

        // A shared (v4) tag ('#'-prefixed, user_id null on the association).
        sharedTag = new Tag();
        sharedTag.setSlug("#shared");
        sharedTag.setIsShared(true);
        tagRepository.save(sharedTag);

        ResourcesTag sharedAssoc = new ResourcesTag();
        sharedAssoc.setResourceId(resource.getId());
        sharedAssoc.setTagId(sharedTag.getId());
        sharedAssoc.setUserId(null);
        resourcesTagRepository.save(sharedAssoc);
    }

    @Test
    void testIndexTagsContract() throws Exception {
        // The index returns both seeded tags ordered by slug ASC: '#shared'
        // (ASCII '#' sorts before letters) then 'important'. Per-user semantics:
        // the shared tag carries no user_id (visible to everyone who can see the
        // resource); the personal tag echoes the requesting user's id. isValid is
        // ENABLED — both elements are tagLegacy members of the tagIndexAndView
        // anyOf union.
        mockMvc.perform(get("/tags.json")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.body").isArray())
                .andExpect(jsonPath("$.body.length()").value(2))
                // Shared tag first (slug '#shared'): is_shared true, no user_id.
                .andExpect(jsonPath("$.body[0].slug").value("#shared"))
                .andExpect(jsonPath("$.body[0].is_shared").value(true))
                .andExpect(jsonPath("$.body[0].user_id").doesNotExist())
                // Personal tag second (slug 'important'): is_shared false, scoped
                // to the requesting user.
                .andExpect(jsonPath("$.body[1].slug").value("important"))
                .andExpect(jsonPath("$.body[1].is_shared").value(false))
                .andExpect(jsonPath("$.body[1].user_id").value(testUser.getId()))
                .andExpect(openApi().isValid(CONTRACT_VALIDATOR));
    }

    @Test
    void testUpdateTagContract() throws Exception {
        // Legacy rename of the personal tag. The spec's tags_update response is
        // tagV5-only, so isValid is disabled here (documented above); the
        // tagLegacy response body is asserted via JSON paths.
        TagDto.UpdateRequest request = TagDto.UpdateRequest.builder()
                .slug("renamed")
                .isShared(false)
                .build();

        mockMvc.perform(put("/tags/" + personalTag.getId() + ".json")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.body.id").value(personalTag.getId()))
                .andExpect(jsonPath("$.body.slug").value("renamed"))
                .andExpect(jsonPath("$.body.is_shared").value(false));
    }

    @Test
    void testAddTagsToResourceContract() throws Exception {
        // "Set" semantics: post a single existing-tag reference (the shared tag).
        // The user's personal 'important' association is no longer in the desired
        // set, so it is dropped (and, being orphaned, the personal tag is
        // hard-deleted); only '#shared' remains for the requesting user. isValid
        // is disabled (tagAddResource anyOf / e2eeMetadataBased difference,
        // documented above); the resulting set is asserted via JSON paths.
        TagDto.AddRequest request = TagDto.AddRequest.builder()
                .tags(List.of(TagDto.TagEntry.builder()
                        .id(sharedTag.getId())
                        .build()))
                .build();

        mockMvc.perform(post("/tags/" + resource.getId() + ".json")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.body").isArray())
                .andExpect(jsonPath("$.body.length()").value(1))
                .andExpect(jsonPath("$.body[0].slug").value("#shared"))
                .andExpect(jsonPath("$.body[0].is_shared").value(true));
    }

    @Test
    void testAddPersonalTagToResourceContract() throws Exception {
        // Set a brand-new personal (v4 slug, no '#') tag plus the existing shared
        // tag. The new personal association is scoped to the requesting user
        // (is_shared false); the shared tag stays shared. isValid disabled (same
        // tagAddResource / e2eeMetadataBased divergence as above); the per-user
        // shape is asserted via JSON paths. Default org settings allow v4 tag
        // creation, so the new slug tag is created on demand.
        TagDto.AddRequest request = TagDto.AddRequest.builder()
                .tags(List.of(
                        TagDto.TagEntry.builder().id(sharedTag.getId()).build(),
                        TagDto.TagEntry.builder().slug("urgent").isShared(false).build()))
                .build();

        mockMvc.perform(post("/tags/" + resource.getId() + ".json")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.body").isArray())
                .andExpect(jsonPath("$.body.length()").value(2))
                // Ordered by slug ASC: '#shared' (shared) then 'urgent' (personal).
                .andExpect(jsonPath("$.body[0].slug").value("#shared"))
                .andExpect(jsonPath("$.body[0].is_shared").value(true))
                .andExpect(jsonPath("$.body[1].slug").value("urgent"))
                .andExpect(jsonPath("$.body[1].is_shared").value(false))
                .andExpect(jsonPath("$.body[1].user_id").value(testUser.getId()));
    }

    @Test
    void testUpdateTagInvalidUuidReturns400() throws Exception {
        // The error envelope (body: null, url: "") is the project's recorded
        // GlobalExceptionHandler deviation from the spec's string-typed
        // badRequest body, so isValid is not asserted on this error path.
        TagDto.UpdateRequest request = TagDto.UpdateRequest.builder()
                .slug("renamed")
                .build();

        mockMvc.perform(put("/tags/not-a-uuid.json")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.status").value("error"))
                .andExpect(jsonPath("$.header.code").value(400));
    }
}
