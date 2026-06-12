package com.jpassbolt.api.controller;

import com.jpassbolt.api.exception.PassboltApiException;
import com.jpassbolt.api.service.AvatarService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Avatar image download endpoint, ported from PHP AvatarsViewController.
 * <p>
 * This is the only non-JSON endpoint of the whole API: no ".json" suffix, no
 * {header, body} envelope, raw image bytes in the response body. It is public
 * (PHP: {@code allowUnauthenticated(['view'])}, OpenAPI: {@code security: []})
 * because the official frontend references it from {@code <img src>} tags
 * without a JWT - SecurityConfig must permitAll "/avatars/view/**".
 * <p>
 * The Content-Type is unconditionally image/jpeg, even when the placeholder
 * bytes are actually PNG: this reproduces PHP's unconditional
 * {@code withType('jpg')} and matches the OpenAPI spec which only declares
 * image/jpeg. Do not "fix" this with byte sniffing.
 */
@Slf4j
@RestController
@RequestMapping("/avatars")
@RequiredArgsConstructor
public class AvatarController {

    private final AvatarService avatarService;

    /**
     * GET /avatars/view/{avatarId}/{avatarFormat}
     * <p>
     * avatarFormat is "small.jpg" or "medium.jpg" (the dot is part of the path
     * variable; Spring Boot 3's PathPatternParser does not truncate suffixes).
     * Unknown ids, invalid formats and empty data all yield a placeholder
     * image with HTTP 200 - never 400/404 (the official plugin does not
     * handle avatar errors).
     */
    @GetMapping("/view/{avatarId}/{avatarFormat}")
    public ResponseEntity<byte[]> view(@PathVariable String avatarId, @PathVariable String avatarFormat) {
        try {
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_JPEG)
                    .body(avatarService.getAvatarImage(avatarId, avatarFormat));
        } catch (Exception e) {
            // PHP maps any Throwable from the read service to a 404 (practically
            // unreachable). GlobalExceptionHandler renders a JSON envelope instead
            // of PHP's HTML error page; the spec declares no error response for
            // this operation and the plugin does not rely on it - acceptable.
            log.error("Error serving avatar {} in format {}: {}", avatarId, avatarFormat, e.getMessage(), e);
            throw new PassboltApiException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }
}
