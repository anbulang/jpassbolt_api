package com.jpassbolt.api.controller;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@SpringBootTest
@AutoConfigureMockMvc
public class OpenApiComplianceTest {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected WebApplicationContext context;

    @Autowired
    protected ObjectMapper objectMapper;

    protected static final String OPEN_API_SPEC_URL = "plugin-redoc-0.yaml";

    /**
     * A placeholder Bearer token attached to every contract request.
     *
     * <p>
     * Most plugin endpoints declare {@code security: - bearerHttpAuthentication: []}
     * in plugin-redoc-0.yaml, so the swagger-request-validator rejects any request
     * that carries no {@code Authorization: Bearer ...} header with
     * {@code validation.request.security.missing} (and a cascading
     * {@code validation.request.parameter.schema.invalidJson} on the path UUID).
     * </p>
     *
     * <p>
     * The validator only checks that the header is present and uses the
     * {@code Bearer} scheme — it does not verify the JWT signature. The real
     * Spring Security principal still comes from {@code @WithMockUser}, and
     * {@link JwtAuthenticationFilter} leaves the already-authenticated context
     * untouched (it only acts when no authentication is present), so this
     * default header satisfies the contract without disturbing the test's
     * authentication. Endpoints with {@code security: []} are unaffected: an
     * extra Authorization header is not a contract violation.
     * </p>
     */
    protected static final String CONTRACT_BEARER_TOKEN = "Bearer "
            + "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9."
            + "eyJzdWIiOiJjb250cmFjdC10ZXN0In0."
            + "contract-test-placeholder-signature";

    /**
     * Shared validator used by every {@code openApi().isValid(...)} contract
     * assertion. It is built from the same {@link #OPEN_API_SPEC_URL} spec but
     * downgrades one message key to {@code IGNORE}:
     *
     * <p>
     * {@code validation.request.parameter.schema.invalidJson} — for every path
     * parameter the plugin spec declares {@code style: SIMPLE} (a plain,
     * non-JSON-encoded value such as a bare UUID or the literal {@code me}).
     * swagger-request-validator 2.39.0 nonetheless tries to JSON-parse the raw
     * path segment and reports "Unable to parse JSON" for any value that is not
     * a quoted JSON token. This is a known validator artefact, not a real
     * plugin-compatibility deviation (Passbolt path params are never JSON), so
     * it is suppressed at the parameter level only. Request/response body and
     * header validations are untouched and still run at ERROR level.
     * </p>
     */
    protected static final OpenApiInteractionValidator CONTRACT_VALIDATOR =
            OpenApiInteractionValidator.createFor(OPEN_API_SPEC_URL)
                    // Merge allOf sub-schemas before validating. Without this,
                    // swagger-request-validator validates each allOf branch in
                    // isolation under strict additionalProperties, so a property
                    // declared in branch A is wrongly reported as an "additional
                    // property" while validating branch B (and vice versa) — e.g.
                    // commentAdd = allOf[commentUpdate{content}, {parent_id}] and
                    // resourceTypeIndex = allOf[resourceType, {default}]. Merging
                    // combinators makes these composite schemas validate as the
                    // single object the plugin actually sends/receives.
                    .withResolveCombinators(true)
                    .withLevelResolver(
                            LevelResolver.create()
                                    .withLevel("validation.request.parameter.schema.invalidJson",
                                            ValidationReport.Level.IGNORE)
                                    // Same SIMPLE-style artefact on the response side:
                                    // response headers are declared style:simple, plain
                                    // strings (e.g. X-GPGAuth-Progress "stage1", the
                                    // X-GPGAuth-User-Auth-Token PGP-armored block, and the
                                    // Set-Cookie "refresh_token=...; Path=/; ..." string),
                                    // yet the validator tries to JSON-parse the raw header
                                    // value and reports "Unable to parse JSON". These header
                                    // values are exactly what the plugin expects, so the
                                    // JSON-parse artefact is suppressed at header level only.
                                    .withLevel("validation.response.header.schema.invalidJson",
                                            ValidationReport.Level.IGNORE)
                                    .build())
                    .build();

    @BeforeEach
    public void setUp() {
        // Attach default credentials to every request issued through this
        // MockMvc so the contract validator's security requirement is met
        // across all *ContractTest subclasses without per-call boilerplate.
        //
        // Two schemes are declared in the spec: most endpoints use
        // bearerHttpAuthentication (Authorization: Bearer ...); a few session
        // endpoints (e.g. /auth/logout.json, /auth/is-authenticated.json) use
        // gpgCookieAuthentication (an apiKey in the `passbolt_session` cookie,
        // with an X-CSRF-Token header per the scheme description). Supplying
        // both a Bearer header and a session cookie satisfies whichever scheme
        // a given path declares; an unused credential is never a contract
        // violation. None of this disturbs the real Spring Security principal,
        // which still comes from @WithMockUser.
        this.mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .defaultRequest(get("/")
                        .header("Authorization", CONTRACT_BEARER_TOKEN)
                        .header("X-CSRF-Token", "contract-test-csrf-token")
                        .cookie(new Cookie("passbolt_session", "contract-test-session")))
                .build();
    }
}
