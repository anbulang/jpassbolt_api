package com.jpassbolt.api.controller;

import com.jpassbolt.api.model.Avatar;
import com.jpassbolt.api.repository.AvatarRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for AvatarController (GET /avatars/view/{id}/{format}).
 * <p>
 * Deliberately NO class-level @WithMockUser: this endpoint is public
 * (SecurityConfig permits "/avatars/view/**") and anonymous access is the core
 * contract being verified - the official frontend loads avatars via plain
 * &lt;img src&gt; tags without a JWT.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AvatarControllerTest {

    private static final byte[] PNG_MAGIC = { (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A };

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AvatarRepository avatarRepository;

    @BeforeEach
    void setUp() {
        // avatars has no FK dependencies on other tested tables - no reverse chain.
        avatarRepository.deleteAll();
    }

    /** Generate a deterministic-size test JPEG with some color structure. */
    private byte[] makeJpeg(int width, int height) throws IOException {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(Color.BLUE);
            g.fillRect(0, 0, width, height);
            g.setColor(Color.RED);
            g.fillRect(0, 0, width / 2, height / 2);
            g.setColor(Color.GREEN);
            g.fillRect(width / 2, height / 2, width / 2, height / 2);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }

    /** Persist an avatar row; data may be null (DDL allows NULL). */
    private Avatar createAvatar(byte[] data) {
        Avatar avatar = new Avatar();
        avatar.setProfileId(UUID.randomUUID().toString());
        avatar.setData(data);
        return avatarRepository.save(avatar);
    }

    /** Read expected placeholder bytes from the main classpath. */
    private byte[] classpathBytes(String path) throws IOException {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return in.readAllBytes();
        }
    }

    private byte[] getBytes(String url) throws Exception {
        MvcResult result = mockMvc.perform(get(url))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_JPEG))
                .andReturn();
        return result.getResponse().getContentAsByteArray();
    }

    @Test
    void testViewMediumReturnsStoredBytes() throws Exception {
        byte[] source = makeJpeg(200, 200);
        Avatar avatar = createAvatar(source);

        byte[] body = getBytes("/avatars/view/" + avatar.getId() + "/medium.jpg");

        // Medium must be served verbatim - no re-encoding.
        assertThat(body).isEqualTo(source);
    }

    @Test
    void testViewSmallReturnsResizedImage() throws Exception {
        byte[] source = makeJpeg(200, 200);
        Avatar avatar = createAvatar(source);

        byte[] body = getBytes("/avatars/view/" + avatar.getId() + "/small.jpg");

        assertThat(body).isNotEqualTo(source);
        BufferedImage resized = ImageIO.read(new ByteArrayInputStream(body));
        assertThat(resized).isNotNull();
        assertThat(resized.getWidth()).isEqualTo(80);
        assertThat(resized.getHeight()).isEqualTo(80);
    }

    @Test
    void testViewNonExistentAvatarReturnsPlaceholder() throws Exception {
        // Contract: unknown avatar -> 200 + placeholder, NOT 404.
        String unknownId = UUID.randomUUID().toString();

        byte[] medium = getBytes("/avatars/view/" + unknownId + "/medium.jpg");
        assertThat(medium).isEqualTo(classpathBytes("img/avatar/user_medium.png"));

        byte[] small = getBytes("/avatars/view/" + unknownId + "/small.jpg");
        assertThat(small).isEqualTo(classpathBytes("img/avatar/user.png"));
    }

    @Test
    void testViewInvalidFormatReturnsMediumPlaceholder() throws Exception {
        // Contract: invalid format on a REAL avatar -> 200 + medium placeholder,
        // NOT 400, and never the real avatar bytes (PHP nulls the id).
        byte[] source = makeJpeg(200, 200);
        Avatar avatar = createAvatar(source);
        byte[] mediumPlaceholder = classpathBytes("img/avatar/user_medium.png");

        for (String invalidFormat : new String[] { "large.jpg", "small.png", "medium", "SMALL.JPG" }) {
            byte[] body = getBytes("/avatars/view/" + avatar.getId() + "/" + invalidFormat);
            assertThat(body)
                    .as("invalid format '%s' must yield the medium placeholder", invalidFormat)
                    .isEqualTo(mediumPlaceholder)
                    .isNotEqualTo(source);
        }
    }

    @Test
    void testViewAnonymousAccessAllowed() throws Exception {
        // No @WithMockUser / @WithAnonymousUser anywhere: a purely anonymous
        // request must succeed (SecurityConfig permitAll "/avatars/view/**").
        // A 401/403 here means the official frontend's avatars are all broken.
        Avatar avatar = createAvatar(makeJpeg(200, 200));

        mockMvc.perform(get("/avatars/view/" + avatar.getId() + "/medium.jpg"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_JPEG));
    }

    @Test
    void testViewAvatarWithNullDataReturnsPlaceholder() throws Exception {
        // DDL allows data NULL; PHP getAvatarFileName falls back on empty data.
        Avatar avatar = createAvatar(null);

        byte[] medium = getBytes("/avatars/view/" + avatar.getId() + "/medium.jpg");
        assertThat(medium).isEqualTo(classpathBytes("img/avatar/user_medium.png"));

        byte[] small = getBytes("/avatars/view/" + avatar.getId() + "/small.jpg");
        assertThat(small).isEqualTo(classpathBytes("img/avatar/user.png"));
    }

    @Test
    void testViewNonUuidAvatarIdReturnsPlaceholder() throws Exception {
        // Spec declares uuid, but the server must tolerate any string:
        // 200 + placeholder, never 500 (findById on a random string is safe).
        byte[] body = getBytes("/avatars/view/not-a-uuid/medium.jpg");
        assertThat(body).isEqualTo(classpathBytes("img/avatar/user_medium.png"));
    }

    @Test
    void testViewPlaceholderContentTypeIsJpeg() throws Exception {
        // Official quirk: placeholder files are PNG but the response is always
        // declared image/jpeg (PHP's unconditional withType('jpg')). Lock both
        // the header and the PNG magic bytes so it is never "helpfully fixed".
        MvcResult result = mockMvc.perform(get("/avatars/view/" + UUID.randomUUID() + "/medium.jpg"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_JPEG))
                .andReturn();

        byte[] body = result.getResponse().getContentAsByteArray();
        assertThat(body.length).isGreaterThan(PNG_MAGIC.length);
        byte[] head = new byte[PNG_MAGIC.length];
        System.arraycopy(body, 0, head, 0, PNG_MAGIC.length);
        assertThat(head).isEqualTo(PNG_MAGIC);
    }

    @Test
    void testViewFormatPathVariableKeepsDotSuffix() throws Exception {
        // If the path variable were suffix-truncated ("small.jpg" -> "small"),
        // the request would fall into the invalid-format branch and return the
        // medium placeholder. Asserting an actual 80x80 resize proves the dot
        // suffix survives Spring Boot 3's PathPatternParser.
        Avatar avatar = createAvatar(makeJpeg(200, 200));
        byte[] mediumPlaceholder = classpathBytes("img/avatar/user_medium.png");

        byte[] body = getBytes("/avatars/view/" + avatar.getId() + "/small.jpg");

        assertThat(body).isNotEqualTo(mediumPlaceholder);
        BufferedImage resized = ImageIO.read(new ByteArrayInputStream(body));
        assertThat(resized).isNotNull();
        assertThat(resized.getWidth()).isEqualTo(80);
        assertThat(resized.getHeight()).isEqualTo(80);
    }
}
