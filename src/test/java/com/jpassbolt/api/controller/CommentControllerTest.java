package com.jpassbolt.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jpassbolt.api.dto.CommentDto;
import com.jpassbolt.api.model.Comment;
import com.jpassbolt.api.model.Permission;
import com.jpassbolt.api.model.Resource;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.CommentRepository;
import com.jpassbolt.api.repository.PermissionRepository;
import com.jpassbolt.api.repository.ResourceRepository;
import com.jpassbolt.api.repository.SecretRepository;
import com.jpassbolt.api.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for CommentController:
 * threaded read, add (incl. nested replies), creator-only update/delete,
 * permission enforcement, and validation failures.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = "test@example.com", roles = { "USER" })
class CommentControllerTest {

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private CommentRepository commentRepository;

        @Autowired
        private PermissionRepository permissionRepository;

        @Autowired
        private SecretRepository secretRepository;

        @Autowired
        private ResourceRepository resourceRepository;

        @Autowired
        private UserRepository userRepository;

        @Autowired
        private ObjectMapper objectMapper;

        private User testUser;
        private User otherUser;

        @BeforeEach
        void setUp() {
                commentRepository.deleteAll();
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
         * Helper to create a resource, optionally granting the test user a
         * permission of the given type (null = no permission at all).
         */
        private Resource createResource(String name, Integer permTypeForTestUser) {
                Resource resource = new Resource();
                resource.setName(name);
                resource.setCreatedBy(otherUser.getId());
                resource.setModifiedBy(otherUser.getId());
                resource.setDeleted(false);
                resourceRepository.save(resource);

                if (permTypeForTestUser != null) {
                        Permission perm = new Permission();
                        perm.setAco(Permission.RESOURCE_ACO);
                        perm.setAcoForeignKey(resource.getId());
                        perm.setAro(Permission.USER_ARO);
                        perm.setAroForeignKey(testUser.getId());
                        perm.setType(permTypeForTestUser);
                        permissionRepository.save(perm);
                }

                return resource;
        }

        /**
         * Helper to create a comment authored by the given user.
         */
        private Comment createComment(Resource resource, User author, String content, String parentId) {
                Comment comment = new Comment();
                comment.setParentId(parentId);
                comment.setForeignKey(resource.getId());
                comment.setForeignModel(Comment.RESOURCE_FOREIGN_MODEL);
                comment.setContent(content);
                comment.setCreatedBy(author.getId());
                comment.setModifiedBy(author.getId());
                comment.setUserId(author.getId());
                return commentRepository.save(comment);
        }

        // ---------------------------------------------------------------
        // GET /comments/resource/{resourceId}.json
        // ---------------------------------------------------------------

        @Test
        void testGetComments_EmptyList() throws Exception {
                Resource resource = createResource("Commented", Permission.READ);

                mockMvc.perform(get("/comments/resource/" + resource.getId() + ".json"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.header.status").value("success"))
                                .andExpect(jsonPath("$.body").isArray())
                                .andExpect(jsonPath("$.body").isEmpty());
        }

        @Test
        void testGetComments_ReturnsThreadedComments() throws Exception {
                Resource resource = createResource("Commented", Permission.READ);
                Comment parent = createComment(resource, testUser, "parent comment", null);
                createComment(resource, otherUser, "child reply", parent.getId());

                mockMvc.perform(get("/comments/resource/" + resource.getId() + ".json"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.body.length()").value(1))
                                .andExpect(jsonPath("$.body[0].content").value("parent comment"))
                                .andExpect(jsonPath("$.body[0].foreign_model").value("Resource"))
                                .andExpect(jsonPath("$.body[0].foreign_key").value(resource.getId()))
                                .andExpect(jsonPath("$.body[0].user_id").value(testUser.getId()))
                                .andExpect(jsonPath("$.body[0].children.length()").value(1))
                                .andExpect(jsonPath("$.body[0].children[0].content").value("child reply"))
                                .andExpect(jsonPath("$.body[0].children[0].parent_id").value(parent.getId()));
        }

        @Test
        void testGetComments_WithContainCreator() throws Exception {
                Resource resource = createResource("Commented", Permission.READ);
                createComment(resource, testUser, "a comment", null);

                mockMvc.perform(get("/comments/resource/" + resource.getId() + ".json")
                                .param("contain[creator]", "1")
                                .param("contain[modifier]", "1"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.body[0].creator.username").value("test@example.com"))
                                .andExpect(jsonPath("$.body[0].creator.id").value(testUser.getId()))
                                .andExpect(jsonPath("$.body[0].modifier.username").value("test@example.com"));
        }

        @Test
        void testGetComments_WithoutPermission_Forbidden() throws Exception {
                Resource resource = createResource("Forbidden", null);

                mockMvc.perform(get("/comments/resource/" + resource.getId() + ".json"))
                                .andExpect(status().isForbidden())
                                .andExpect(jsonPath("$.header.status").value("error"));
        }

        @Test
        void testGetComments_ResourceNotFound() throws Exception {
                mockMvc.perform(get("/comments/resource/" + UUID.randomUUID() + ".json"))
                                .andExpect(status().isNotFound())
                                .andExpect(jsonPath("$.header.status").value("error"));
        }

        @Test
        void testGetComments_InvalidResourceId_BadRequest() throws Exception {
                mockMvc.perform(get("/comments/resource/not-a-uuid.json"))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.header.status").value("error"));
        }

        // ---------------------------------------------------------------
        // POST /comments/resource/{resourceId}.json
        // ---------------------------------------------------------------

        @Test
        void testAddComment_Success() throws Exception {
                Resource resource = createResource("Commented", Permission.READ);

                CommentDto.CreateRequest request = CommentDto.CreateRequest.builder()
                                .content("no comment")
                                .build();

                mockMvc.perform(post("/comments/resource/" + resource.getId() + ".json")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.header.status").value("success"))
                                .andExpect(jsonPath("$.body.content").value("no comment"))
                                .andExpect(jsonPath("$.body.foreign_model").value("Resource"))
                                .andExpect(jsonPath("$.body.user_id").value(testUser.getId()));

                List<Comment> comments = commentRepository.findByResourceId(resource.getId());
                assertThat(comments).hasSize(1);
                assertThat(comments.get(0).getCreatedBy()).isEqualTo(testUser.getId());
        }

        @Test
        void testAddComment_WithParent_NestedReply() throws Exception {
                Resource resource = createResource("Commented", Permission.READ);
                Comment parent = createComment(resource, otherUser, "parent comment", null);

                CommentDto.CreateRequest request = CommentDto.CreateRequest.builder()
                                .content("a reply")
                                .parentId(parent.getId())
                                .build();

                mockMvc.perform(post("/comments/resource/" + resource.getId() + ".json")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.body.parent_id").value(parent.getId()));
        }

        @Test
        void testAddComment_InvalidParent_BadRequest() throws Exception {
                Resource resource = createResource("Commented", Permission.READ);

                CommentDto.CreateRequest request = CommentDto.CreateRequest.builder()
                                .content("a reply")
                                .parentId(UUID.randomUUID().toString())
                                .build();

                mockMvc.perform(post("/comments/resource/" + resource.getId() + ".json")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.header.status").value("error"));
        }

        @Test
        void testAddComment_EmptyContent_BadRequest() throws Exception {
                Resource resource = createResource("Commented", Permission.READ);

                CommentDto.CreateRequest request = CommentDto.CreateRequest.builder()
                                .content("")
                                .build();

                mockMvc.perform(post("/comments/resource/" + resource.getId() + ".json")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.header.status").value("error"));
        }

        @Test
        void testAddComment_TooLongContent_BadRequest() throws Exception {
                Resource resource = createResource("Commented", Permission.READ);

                CommentDto.CreateRequest request = CommentDto.CreateRequest.builder()
                                .content("a".repeat(257))
                                .build();

                mockMvc.perform(post("/comments/resource/" + resource.getId() + ".json")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.header.status").value("error"));
        }

        @Test
        void testAddComment_WithoutPermission_Forbidden() throws Exception {
                Resource resource = createResource("Forbidden", null);

                CommentDto.CreateRequest request = CommentDto.CreateRequest.builder()
                                .content("no comment")
                                .build();

                mockMvc.perform(post("/comments/resource/" + resource.getId() + ".json")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isForbidden())
                                .andExpect(jsonPath("$.header.status").value("error"));
        }

        @Test
        void testAddComment_ResourceNotFound() throws Exception {
                CommentDto.CreateRequest request = CommentDto.CreateRequest.builder()
                                .content("no comment")
                                .build();

                mockMvc.perform(post("/comments/resource/" + UUID.randomUUID() + ".json")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isNotFound())
                                .andExpect(jsonPath("$.header.status").value("error"));
        }

        // ---------------------------------------------------------------
        // PUT /comments/{commentId}.json
        // ---------------------------------------------------------------

        @Test
        void testUpdateComment_ByCreator_Success() throws Exception {
                Resource resource = createResource("Commented", Permission.READ);
                Comment comment = createComment(resource, testUser, "original content", null);

                CommentDto.UpdateRequest request = CommentDto.UpdateRequest.builder()
                                .content("updated content")
                                .build();

                mockMvc.perform(put("/comments/" + comment.getId() + ".json")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.header.status").value("success"))
                                .andExpect(jsonPath("$.body.content").value("updated content"));

                Comment updated = commentRepository.findById(comment.getId()).orElseThrow();
                assertThat(updated.getContent()).isEqualTo("updated content");
                assertThat(updated.getModifiedBy()).isEqualTo(testUser.getId());
        }

        @Test
        void testUpdateComment_NotCreator_Forbidden() throws Exception {
                Resource resource = createResource("Commented", Permission.READ);
                Comment comment = createComment(resource, otherUser, "someone else's comment", null);

                CommentDto.UpdateRequest request = CommentDto.UpdateRequest.builder()
                                .content("hijacked")
                                .build();

                mockMvc.perform(put("/comments/" + comment.getId() + ".json")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isForbidden())
                                .andExpect(jsonPath("$.header.status").value("error"));
        }

        @Test
        void testUpdateComment_NotFound() throws Exception {
                CommentDto.UpdateRequest request = CommentDto.UpdateRequest.builder()
                                .content("anything")
                                .build();

                mockMvc.perform(put("/comments/" + UUID.randomUUID() + ".json")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isNotFound())
                                .andExpect(jsonPath("$.header.status").value("error"));
        }

        @Test
        void testUpdateComment_EmptyContent_BadRequest() throws Exception {
                Resource resource = createResource("Commented", Permission.READ);
                Comment comment = createComment(resource, testUser, "original content", null);

                CommentDto.UpdateRequest request = CommentDto.UpdateRequest.builder()
                                .content("")
                                .build();

                mockMvc.perform(put("/comments/" + comment.getId() + ".json")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.header.status").value("error"));
        }

        // ---------------------------------------------------------------
        // DELETE /comments/{commentId}.json
        // ---------------------------------------------------------------

        @Test
        void testDeleteComment_ByCreator_Success() throws Exception {
                Resource resource = createResource("Commented", Permission.READ);
                Comment comment = createComment(resource, testUser, "to delete", null);

                mockMvc.perform(delete("/comments/" + comment.getId() + ".json"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.header.status").value("success"));

                // Hard delete: the row is gone
                assertThat(commentRepository.findById(comment.getId())).isEmpty();
        }

        @Test
        void testDeleteComment_NotCreator_NotFound() throws Exception {
                Resource resource = createResource("Commented", Permission.READ);
                Comment comment = createComment(resource, otherUser, "someone else's comment", null);

                // Matching official Passbolt: deleting another user's comment
                // returns 404 so the comment's existence is not disclosed.
                mockMvc.perform(delete("/comments/" + comment.getId() + ".json"))
                                .andExpect(status().isNotFound())
                                .andExpect(jsonPath("$.header.status").value("error"));

                assertThat(commentRepository.findById(comment.getId())).isPresent();
        }

        @Test
        void testDeleteComment_NotFound() throws Exception {
                mockMvc.perform(delete("/comments/" + UUID.randomUUID() + ".json"))
                                .andExpect(status().isNotFound())
                                .andExpect(jsonPath("$.header.status").value("error"));
        }

        @Test
        void testDeleteComment_InvalidId_BadRequest() throws Exception {
                mockMvc.perform(delete("/comments/not-a-uuid.json"))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.header.status").value("error"));
        }
}
