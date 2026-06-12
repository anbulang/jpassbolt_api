package com.jpassbolt.api.service;

import com.jpassbolt.api.model.Avatar;
import com.jpassbolt.api.repository.AvatarRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

/**
 * Read path for avatar images, ported from the PHP reference implementation
 * ({@code AvatarsCacheService::readSteamFromId} +
 * {@code AvatarProcessing::resizeAndCrop}).
 * <p>
 * Behavioural contract (must stay byte-compatible with official Passbolt):
 * <ul>
 * <li>Invalid format: never an error - the id is discarded and the medium
 * placeholder is returned (PHP nulls the id and the unknown format has no
 * configured default image, so it falls back to the medium default).</li>
 * <li>Unknown/non-UUID avatar id or empty data: the placeholder of the
 * requested format is returned, never a 404.</li>
 * <li>{@code medium.jpg}: the stored blob is returned verbatim, no
 * re-encoding.</li>
 * <li>{@code small.jpg}: the stored blob (200x200 at upload time) is resized
 * and center-cropped to 80x80 on the fly, drawn on a white opaque canvas and
 * encoded as JPEG quality 0.9 - same semantics as PHP's Imagine pipeline.</li>
 * </ul>
 * Unlike PHP, no filesystem cache is kept: resizing a 200x200 source in
 * memory is cheap and a stateless deployment is simpler. The method keeps a
 * pure signature so Spring Cache can be added later without changes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AvatarService {

    public static final String FORMAT_SMALL = "small";
    public static final String FORMAT_MEDIUM = "medium";
    public static final String IMAGE_EXTENSION = ".jpg";
    public static final Set<String> VALID_FORMATS = Set.of("small.jpg", "medium.jpg");
    public static final int SMALL_SIZE = 80;
    public static final float JPEG_QUALITY = 0.9f;

    private static final String SMALL_FALLBACK_RESOURCE = "img/avatar/user.png";
    private static final String MEDIUM_FALLBACK_RESOURCE = "img/avatar/user_medium.png";

    private final AvatarRepository avatarRepository;

    /** Cached default avatar bytes, loaded once from the classpath. */
    private byte[] smallFallback;
    private byte[] mediumFallback;

    @PostConstruct
    void loadFallbackImages() {
        this.smallFallback = readClasspathResource(SMALL_FALLBACK_RESOURCE);
        this.mediumFallback = readClasspathResource(MEDIUM_FALLBACK_RESOURCE);
    }

    /**
     * Resolve the image bytes to serve for an avatar id and format.
     *
     * @param avatarId     avatar id from the URL (any string is tolerated)
     * @param avatarFormat requested format, expected "small.jpg" or "medium.jpg"
     * @return the image bytes (real avatar or placeholder), never null
     */
    @Transactional(readOnly = true)
    public byte[] getAvatarImage(String avatarId, String avatarFormat) {
        if (avatarFormat == null || !VALID_FORMATS.contains(avatarFormat)) {
            // PHP semantics: invalid format -> id nulled -> fallback; an unknown
            // format has no configured default image -> medium default wins.
            return loadFallback(FORMAT_MEDIUM);
        }

        String format = avatarFormat.substring(0, avatarFormat.length() - IMAGE_EXTENSION.length());

        Avatar avatar = (avatarId == null || avatarId.isBlank())
                ? null
                : avatarRepository.findById(avatarId).orElse(null);

        if (avatar == null || avatar.getData() == null || avatar.getData().length == 0) {
            return loadFallback(format);
        }

        if (FORMAT_SMALL.equals(format)) {
            try {
                return resizeAndCropToJpeg(avatar.getData(), SMALL_SIZE, SMALL_SIZE);
            } catch (Exception e) {
                // PHP logs the processing error and serves the fallback image.
                log.error("Error while processing small image for avatar with ID {}.", avatar.getId(), e);
                return loadFallback(FORMAT_SMALL);
            }
        }

        // Medium: serve the stored bytes verbatim (no re-encoding).
        return avatar.getData();
    }

    /**
     * Default avatar bytes for a format. The files are PNGs - the controller
     * still serves them as image/jpeg, faithfully reproducing the official
     * behaviour (PHP's withType('jpg') is unconditional).
     */
    private byte[] loadFallback(String format) {
        return FORMAT_SMALL.equals(format) ? smallFallback : mediumFallback;
    }

    private byte[] readClasspathResource(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Default avatar image not found on classpath: " + path, e);
        }
    }

    /**
     * Resize (cover) and center-crop the source image to the requested size,
     * drawn on a white opaque RGB canvas, encoded as JPEG quality 0.9.
     * <p>
     * Mirrors PHP {@code AvatarProcessing::resizeAndCrop}: scale by the
     * smallest width/height ratio so the shortest side covers the crop box,
     * then crop the center. Drawing on a TYPE_INT_RGB white canvas removes any
     * alpha channel (JPEG cannot encode ARGB) - same as PHP's white canvas.
     */
    private byte[] resizeAndCropToJpeg(byte[] source, int cropWidth, int cropHeight) throws IOException {
        BufferedImage src = ImageIO.read(new ByteArrayInputStream(source));
        if (src == null) {
            throw new IOException("Unable to decode avatar image data");
        }

        double widthRatio = (double) src.getWidth() / cropWidth;
        double heightRatio = (double) src.getHeight() / cropHeight;
        double minRatio = Math.min(widthRatio, heightRatio);

        int newWidth = (int) (src.getWidth() / minRatio);
        int newHeight = (int) (src.getHeight() / minRatio);

        int x = Math.max(0, (int) Math.round((newWidth - cropWidth) / 2.0));
        int y = Math.max(0, (int) Math.round((newHeight - cropHeight) / 2.0));

        BufferedImage canvas = new BufferedImage(cropWidth, cropHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, cropWidth, cropHeight);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            // Draw the scaled image shifted so the centered crop window lands at the origin.
            g.drawImage(src, -x, -y, newWidth, newHeight, null);
        } finally {
            g.dispose();
        }

        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(JPEG_QUALITY);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(canvas, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }
}
