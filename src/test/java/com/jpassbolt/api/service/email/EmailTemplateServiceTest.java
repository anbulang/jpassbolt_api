package com.jpassbolt.api.service.email;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the email-rendering foundation: the dedicated Thymeleaf
 * engine ({@code templates/email/*}) wired to the mail message bundles. Verifies
 * per-recipient localization (en + zh), variable injection, the shared layout
 * footer, and the {@code show_uri} content gate.
 */
@SpringBootTest
class EmailTemplateServiceTest {

    @Autowired
    private EmailTemplateService templateService;

    private Map<String, Object> baseVars() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("actorName", "Ada Lovelace");
        vars.put("resourceName", "AWS root");
        vars.put("link", "http://localhost:5173/app/passwords/view/abc-123");
        vars.put("showUri", false);
        return vars;
    }

    @Test
    void rendersEnglishBody() {
        String html = templateService.render("lu/resource_share", Locale.ENGLISH, baseVars());
        assertThat(html)
                .contains("A password was shared with you")                  // title (en)
                .contains("Ada Lovelace")                                    // actor var
                .contains("AWS root")                                        // resource var
                .contains("http://localhost:5173/app/passwords/view/abc-123")// link var
                .contains("Open in JPassbolt")                               // cta (en)
                .contains("JPassbolt — end-to-end encrypted password manager"); // shared footer
    }

    @Test
    void rendersChineseBody() {
        String html = templateService.render("lu/resource_share", Locale.SIMPLIFIED_CHINESE, baseVars());
        assertThat(html)
                .contains("有人与您共享了密码")        // title (zh)
                .contains("在 JPassbolt 中打开")       // cta (zh)
                .contains("AWS root")
                .doesNotContain("A password was shared with you"); // not the english title
    }

    @Test
    void rendersFallbackLocaleViaBaseBundle() {
        // A supported locale with no dedicated bundle (e.g. German) must fall back
        // to the base email.properties (English copy) rather than throwing
        // NoSuchMessageException — the contract MailMessageConfig documents.
        String html = templateService.render("lu/resource_share", Locale.GERMAN, baseVars());
        assertThat(html)
                .contains("A password was shared with you")  // English base-bundle title
                .contains("Open in JPassbolt")
                .contains("Ada Lovelace");
        assertThat(templateService.subject("email.resource.share.subject.generic", Locale.GERMAN, "Ada"))
                .isEqualTo("Ada shared a resource");
    }

    @Test
    void subjectIsLocalized() {
        assertThat(templateService.subject("email.resource.share.subject.generic", Locale.ENGLISH, "Ada"))
                .isEqualTo("Ada shared a resource");
        assertThat(templateService.subject("email.resource.share.subject.generic",
                Locale.SIMPLIFIED_CHINESE, "Ada"))
                .contains("与您共享了一个资源");
    }

    @Test
    void showUriGateControlsUriRendering() {
        Map<String, Object> vars = baseVars();
        vars.put("resourceUri", "https://secret.internal.example.com/login");

        // gate closed (default false) → the URI must not leak into the body
        vars.put("showUri", false);
        assertThat(templateService.render("lu/resource_share", Locale.ENGLISH, vars))
                .doesNotContain("secret.internal.example.com");

        // gate open → the URI is rendered
        vars.put("showUri", true);
        assertThat(templateService.render("lu/resource_share", Locale.ENGLISH, vars))
                .contains("https://secret.internal.example.com/login");
    }
}
