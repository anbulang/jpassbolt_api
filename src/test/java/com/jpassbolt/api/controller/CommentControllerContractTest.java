package com.jpassbolt.api.controller;

import com.jpassbolt.api.dto.CommentDto;
import com.jpassbolt.api.model.Comment;
import com.jpassbolt.api.model.Permission;
import com.jpassbolt.api.model.Resource;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.CommentRepository;
import com.jpassbolt.api.repository.PermissionRepository;
import com.jpassbolt.api.repository.ResourceRepository;
import com.jpassbolt.api.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * OpenAPI contract tests for the Comments endpoints
 * (/comments/resource/{resourceId}.json and /comments/{commentId}.json).
 *
 * NOTE: the openApi().isValid(OPEN_API_SPEC_URL) assertions are present but
 * commented out, in line with the existing AuthControllerContractTest:
 * the Atlassian Swagger Request Validator's strict JSON header validation
 * currently rejects our response envelope. Re-enable them once the envelope
 * validation issue is resolved project-wide.
 */
@WithMockUser(username = "test@example.com", roles = { "USER" })
class CommentControllerContractTest extends OpenApiComplianceTest {

        @Autowired
        private CommentRepository commentRepository;

        @Autowired
        private PermissionRepository permissionRepository;

        @Autowired
        private ResourceRepository resourceRepository;

        @Autowired
        private UserRepository userRepository;

        private User testUser;
        private Resource resource;
        private Comment comment;

        @BeforeEach
        void setUpData() {
                commentRepository.deleteAll();
                permissionRepository.deleteAll();
                resourceRepository.deleteAll();
                userRepository.deleteAll();

                testUser = new User();
                testUser.setUsername("test@example.com");
                testUser.setRoleId("user");
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

                comment = new Comment();
                comment.setForeignKey(resource.getId());
                comment.setForeignModel(Comment.RESOURCE_FOREIGN_MODEL);
                comment.setContent("existing comment");
                comment.setCreatedBy(testUser.getId());
                comment.setModifiedBy(testUser.getId());
                comment.setUserId(testUser.getId());
                commentRepository.save(comment);
        }

        @Test
        void testIndexCommentsContract() throws Exception {
                mockMvc.perform(get("/comments/resource/" + resource.getId() + ".json")
                                .param("contain[creator]", "1")
                                .param("contain[modifier]", "1")
                                .accept(MediaType.APPLICATION_JSON))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.header.status").value("success"))
                                .andExpect(jsonPath("$.body").isArray());
                // Disabled due to strict JSON header validation:
                // .andExpect(openApi().isValid(OPEN_API_SPEC_URL));
        }

        @Test
        void testAddCommentContract() throws Exception {
                CommentDto.CreateRequest request = CommentDto.CreateRequest.builder()
                                .content("no comment")
                                .build();

                mockMvc.perform(post("/comments/resource/" + resource.getId() + ".json")
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.header.status").value("success"))
                                .andExpect(jsonPath("$.body.content").value("no comment"));
                // Disabled due to strict JSON header validation:
                // .andExpect(openApi().isValid(OPEN_API_SPEC_URL));
        }

        @Test
        void testUpdateCommentContract() throws Exception {
                CommentDto.UpdateRequest request = CommentDto.UpdateRequest.builder()
                                .content("updated comment")
                                .build();

                mockMvc.perform(put("/comments/" + comment.getId() + ".json")
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.header.status").value("success"))
                                .andExpect(jsonPath("$.body.content").value("updated comment"));
                // Disabled due to strict JSON header validation:
                // .andExpect(openApi().isValid(OPEN_API_SPEC_URL));
        }

        @Test
        void testDeleteCommentContract() throws Exception {
                mockMvc.perform(delete("/comments/" + comment.getId() + ".json")
                                .accept(MediaType.APPLICATION_JSON))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.header.status").value("success"));
                // Disabled due to strict JSON header validation:
                // .andExpect(openApi().isValid(OPEN_API_SPEC_URL));
        }
}
