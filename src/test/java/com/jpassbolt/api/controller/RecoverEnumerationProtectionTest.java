package com.jpassbolt.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jpassbolt.api.repository.AuthenticationTokenRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The enumeration-protection ON variant of {@link RecoverControllerTest}'s
 * unknown-email case: with jpassbolt.security.prevent-email-enumeration=true
 * (official passbolt.security.preventEmailEnumeration), POST /users/recover.json
 * for an unknown email answers 200 "check your email" instead of 404, so the
 * account's (non-)existence is not leaked. Separate class so the flag can be
 * flipped for the whole (cached) context — the default-false path is covered by
 * RecoverControllerTest.testRecover_NonExistentUser_NotFound_ByDefault.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "jpassbolt.security.prevent-email-enumeration=true")
class RecoverEnumerationProtectionTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuthenticationTokenRepository authenticationTokenRepository;

    @Test
    void testRecover_NonExistentUser_EnumerationSafeSuccess() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("username", "ghost@example.com"));

        long tokensBefore = authenticationTokenRepository.count();

        mockMvc.perform(post("/users/recover.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.header.message").value("Recovery process started, check your email."));

        // Still no token issued: only the RESPONSE is disguised, not the effect.
        assertThat(authenticationTokenRepository.count()).isEqualTo(tokensBefore);
    }
}
