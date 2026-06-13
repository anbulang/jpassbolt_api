package com.jpassbolt.api.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jpassbolt.api.service.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigInteger;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the JWT key distribution endpoints (PHP
 * JwksController::jwks / ::rsa):
 * <ul>
 * <li>GET /auth/jwt/jwks.json — normalized RFC 7517 document, NO envelope</li>
 * <li>GET /.well-known/jwks.json — top-level alias</li>
 * <li>GET /auth/jwt/rsa.json — standard envelope with the PEM keydata</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class JwksControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtService jwtService;

    @Test
    void testJwks_ReturnsRawJwksDocumentWithoutEnvelope() throws Exception {
        MvcResult result = mockMvc.perform(get("/auth/jwt/jwks.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys").isArray())
                .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
                .andExpect(jsonPath("$.keys[0].alg").value("RS256"))
                .andExpect(jsonPath("$.keys[0].use").value("sig"))
                .andExpect(jsonPath("$.keys[0].n").exists())
                .andExpect(jsonPath("$.keys[0].e").exists())
                // Normalized endpoint: no {header, body} envelope
                .andExpect(jsonPath("$.header").doesNotExist())
                .andExpect(jsonPath("$.body").doesNotExist())
                .andReturn();

        // n must decode (base64url, no padding, no sign byte) to the actual
        // RSA modulus — otherwise clients rebuild a wrong verification key
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        byte[] nBytes = Base64.getUrlDecoder().decode(json.at("/keys/0/n").asText());
        BigInteger n = new BigInteger(1, nBytes);
        assertThat(n).isEqualTo(jwtService.getRsaPublicKey().getModulus());

        byte[] eBytes = Base64.getUrlDecoder().decode(json.at("/keys/0/e").asText());
        BigInteger e = new BigInteger(1, eBytes);
        assertThat(e).isEqualTo(jwtService.getRsaPublicKey().getPublicExponent());
    }

    /**
     * The PHP top-level routes redirect /.well-known/jwks.json onto the
     * plugin's jwks action; the alias is permitAll in SecurityConfig, so it
     * must work anonymously (no @WithMockUser).
     */
    @Test
    void testWellKnownJwksAlias_ReturnsSameDocument() throws Exception {
        mockMvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
                .andExpect(jsonPath("$.keys[0].alg").value("RS256"));
    }

    @Test
    void testRsa_ReturnsEnvelopeWithPemKeydata() throws Exception {
        MvcResult result = mockMvc.perform(get("/auth/jwt/rsa.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.body.keydata").exists())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(json.at("/body/keydata").asText())
                .startsWith("-----BEGIN PUBLIC KEY-----");
    }

    @Test
    void testJwksWithWrongMethod_Returns405() throws Exception {
        mockMvc.perform(post("/auth/jwt/jwks.json"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void testRsaWithWrongMethod_Returns405() throws Exception {
        mockMvc.perform(post("/auth/jwt/rsa.json"))
                .andExpect(status().isMethodNotAllowed());
    }
}
