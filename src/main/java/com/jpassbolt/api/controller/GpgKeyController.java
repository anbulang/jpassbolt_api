package com.jpassbolt.api.controller;

import com.jpassbolt.api.dto.GpgKeyDto;
import com.jpassbolt.api.exception.PassboltApiException;
import com.jpassbolt.api.model.GpgKey;
import com.jpassbolt.api.service.GpgKeyService;
import com.jpassbolt.api.util.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * GpgKeyController exposes the read-only GPG public key directory.
 *
 * Ported from the PHP reference controllers GpgkeysIndexController /
 * GpgkeysViewController. Any authenticated user may call these endpoints —
 * there is no per-object permission check (the PHP controllers only rely on
 * the Authentication component). The official plugin uses /gpgkeys.json to
 * synchronize all user public keys into its local keyring, which is a
 * prerequisite for client-side encryption when sharing secrets.
 *
 * Routing note: this controller deliberately does NOT use the
 * class-level @RequestMapping("/gpgkeys") + @GetMapping({"", ".json"})
 * pattern seen in ResourceController. With Spring's PathPatternParser
 * (the Boot 3 default), combine("/gpgkeys", ".json") yields
 * "/gpgkeys/.json", so the ".json" variant silently never matches
 * GET /gpgkeys.json (verified empirically against spring-web 6.1).
 * Full method-level paths are used instead so that /gpgkeys.json — the
 * exact path the official plugin requests — is actually routable.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class GpgKeyController {

    /**
     * UUID validation pattern mirroring CakePHP Validation::uuid
     * (version 1-5, RFC 4122 variant).
     */
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[1-5][a-fA-F0-9]{3}-[89aAbB][a-fA-F0-9]{3}-[a-fA-F0-9]{12}$");

    private final GpgKeyService gpgKeyService;

    /**
     * GET /gpgkeys.json
     * Lists GPG public keys for keyring synchronization.
     *
     * Query parameters (both optional):
     * - filter[modified-after]: unix timestamp (seconds); only keys with
     *   modified STRICTLY greater than this instant are returned.
     * - filter[is-deleted]: boolean (0/1/true/false); defaults to false.
     *
     * NOTE (deliberately not implemented): the PHP whitelist also supports
     * filter[has-users] (array of user uuids) and the legacy top-level
     * "modified_after" alias. Neither is defined in the OpenAPI spec nor used
     * by the official plugin's keyring sync (the plugin pulls the full
     * directory, optionally with filter[modified-after]), so this is a known,
     * intentional gap rather than a pending task. Add filter[has-users] (plus
     * a findByDeletedAndUserIdIn query and the ref's negative-case test) only
     * if a future cluster actually requires it.
     */
    @GetMapping(value = { "/gpgkeys", "/gpgkeys.json" })
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> index(
            @RequestParam(name = "filter[modified-after]", required = false) String modifiedAfter,
            @RequestParam(name = "filter[is-deleted]", required = false) String isDeleted) {

        boolean deleted = parseIsDeletedFilter(isDeleted);
        LocalDateTime modifiedAfterDate = parseModifiedAfterFilter(modifiedAfter);

        List<GpgKey> keys = gpgKeyService.getGpgKeys(deleted, modifiedAfterDate);
        List<GpgKeyDto.Response> responseList = keys.stream()
                .map(this::toResponseDto)
                .collect(Collectors.toList());

        Map<String, Object> response = createResponse("success", "The operation was successful.",
                responseList, "/gpgkeys.json");

        // The gpgkeys_index response uses headerWithPagination: the header
        // additionally requires pagination {count, page, limit}. limit is
        // nullable, so a LinkedHashMap is used instead of Map.of (NPE on null).
        Map<String, Object> header = new LinkedHashMap<>((Map<String, Object>) response.get("header"));
        Map<String, Object> pagination = new LinkedHashMap<>();
        pagination.put("count", responseList.size());
        pagination.put("page", 1);
        pagination.put("limit", null);
        header.put("pagination", pagination);
        response.put("header", header);

        return ResponseEntity.ok(response);
    }

    /**
     * GET /gpgkeys/{id}.json
     * Returns a single GPG public key by id.
     *
     * Behavior ported from PHP GpgkeysViewController.view():
     * - non-UUID id -> 400 "The OpenPGP key identifier should be a valid UUID."
     * - unknown id -> 404 "The OpenPGP key does not exist."
     * - soft-deleted keys are STILL returned with 200 (deleted=true): the PHP
     *   findView does not filter on deleted. See GpgKeyService.getGpgKeyById.
     */
    @GetMapping("/gpgkeys/{id}.json")
    public ResponseEntity<Map<String, Object>> view(@PathVariable String id) {
        String url = "/gpgkeys/" + id + ".json";

        if (!UUID_PATTERN.matcher(id).matches()) {
            return ResponseEntity.status(400)
                    .body(createResponse("error", "The OpenPGP key identifier should be a valid UUID.",
                            null, url));
        }

        return gpgKeyService.getGpgKeyById(id)
                .map(key -> ResponseEntity.ok(createResponse("success", "The operation was successful.",
                        toResponseDto(key), url)))
                .orElse(ResponseEntity.status(404)
                        .body(createResponse("error", "The OpenPGP key does not exist.", null, url)));
    }

    /**
     * Parse filter[is-deleted]; mirrors PHP QueryStringComponent::normalizeBoolean,
     * which accepts 0/1/true/false. Anything else is a 400 BadRequest.
     */
    private boolean parseIsDeletedFilter(String value) {
        if (value == null) {
            return false;
        }
        switch (value.toLowerCase()) {
            case "0":
            case "false":
                return false;
            case "1":
            case "true":
                return true;
            default:
                throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                        "Invalid filter. \"" + value + "\" is not a valid value for filter is-deleted.");
        }
    }

    /**
     * Parse filter[modified-after]; mirrors PHP
     * QueryStringComponent::validateFilterTimestamp / isTimestamp — the value
     * must be a unix timestamp (seconds), otherwise 400 BadRequest.
     *
     * The epoch is converted with ZoneOffset.UTC: entity timestamps are now
     * written with LocalDateTime.now(ZoneOffset.UTC) in BaseEntity (full-chain
     * UTC alignment), so the comparison must use UTC to stay internally
     * consistent.
     */
    private LocalDateTime parseModifiedAfterFilter(String value) {
        if (value == null) {
            return null;
        }
        if (!value.matches("\\d+")) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "Invalid filter. \"" + value + "\" is not a valid timestamp for filter modified-after.");
        }
        long epochSeconds;
        try {
            epochSeconds = Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "Invalid filter. \"" + value + "\" is not a valid timestamp for filter modified-after.");
        }
        return LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), ZoneOffset.UTC);
    }

    /**
     * Map entity to response DTO. armored_key is passed through verbatim
     * (including line breaks) — the plugin feeds it directly to openpgp.js,
     * so any normalization would break client-side encryption.
     */
    private GpgKeyDto.Response toResponseDto(GpgKey gpgKey) {
        return GpgKeyDto.Response.builder()
                .id(gpgKey.getId())
                .userId(gpgKey.getUserId())
                .armoredKey(gpgKey.getArmoredKey())
                .bits(gpgKey.getBits())
                .uid(gpgKey.getUid())
                .keyId(gpgKey.getKeyId())
                .fingerprint(gpgKey.getFingerprint())
                .type(gpgKey.getType())
                .expires(gpgKey.getExpires())
                .keyCreated(gpgKey.getKeyCreated())
                .deleted(gpgKey.getDeleted())
                .created(gpgKey.getCreated())
                .modified(gpgKey.getModified())
                .build();
    }

    private Map<String, Object> createResponse(String status, String message, Object body, String url) {
        // 迁移到共享信封工具：补 action(uuid) 等 spec required 字段，保留原 200/400 code 语义。
        return ApiResponse.withCode(status, message, body, "success".equals(status) ? 200 : 400, url);
    }
}
