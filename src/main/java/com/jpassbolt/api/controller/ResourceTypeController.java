package com.jpassbolt.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jpassbolt.api.dto.ResourceTypeDto;
import com.jpassbolt.api.exception.PassboltApiException;
import com.jpassbolt.api.model.ResourceType;
import com.jpassbolt.api.service.ResourceTypeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * ResourceTypeController provides read-only resource type endpoints,
 * compatible with the Passbolt v4 API:
 *
 * <ul>
 *   <li>GET /resource-types.json — active v4 types (deleted IS NULL,
 *       v5 slugs excluded). Any authenticated user, no role check.</li>
 *   <li>GET /resource-types/{id}.json — single type by id, no deleted/v5
 *       filtering (PHP Table::get() semantics).</li>
 * </ul>
 *
 * <p>The v5-only query parameters contain[resources_count] and
 * filter[is-deleted] are intentionally not bound: the PHP v4 branch ignores
 * them entirely, and so does this controller (Spring ignores unbound query
 * parameters by default). PUT/DELETE on the same path are v5-only routes and
 * are deliberately not implemented (Spring answers 405).</p>
 *
 * <p>Note on mappings: no class-level @RequestMapping — with Boot 3's
 * PathPatternParser, "/resource-types" combined with ".json" yields
 * "/resource-types/.json" (NOT "/resource-types.json"), so full
 * method-level paths are used instead.</p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class ResourceTypeController {

        private final ResourceTypeService resourceTypeService;
        private final ObjectMapper objectMapper;

        /**
         * GET /resource-types.json
         * Returns all active v4 resource types. Any authenticated user.
         */
        @GetMapping({ "/resource-types", "/resource-types.json" })
        public ResponseEntity<Map<String, Object>> index() {
                List<ResourceTypeDto.Response> responseList = resourceTypeService.getResourceTypes().stream()
                                .map(this::toResponseDto)
                                .collect(Collectors.toList());

                return ResponseEntity.ok(createResponse("success", "The operation was successful.",
                                responseList, "/resource-types.json"));
        }

        /**
         * GET /resource-types/{id}.json
         * Returns a single resource type by id. Validation order ported from
         * PHP ResourceTypesViewController: UUID check (400) before lookup (404).
         */
        @GetMapping("/resource-types/{id}.json")
        public ResponseEntity<Map<String, Object>> view(@PathVariable String id) {
                try {
                        UUID.fromString(id);
                } catch (IllegalArgumentException e) {
                        throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                                        "The resource identifier should be a valid UUID.");
                }

                ResourceType resourceType = resourceTypeService.getResourceTypeById(id);

                return ResponseEntity.ok(createResponse("success", "The operation was successful.",
                                toResponseDto(resourceType), "/resource-types/" + id + ".json"));
        }

        /**
         * Map entity to response DTO. The definition column stores a raw JSON
         * string; it must be deserialized to a JSON object here, otherwise the
         * client would receive a doubly-serialized string and the browser
         * extension could not parse the JSON Schema.
         */
        private ResourceTypeDto.Response toResponseDto(ResourceType resourceType) {
                Object definition = null;
                if (resourceType.getDefinition() != null) {
                        try {
                                definition = objectMapper.readTree(resourceType.getDefinition());
                        } catch (Exception e) {
                                log.warn("Failed to parse definition JSON for resource type {} ({}): {}",
                                                resourceType.getId(), resourceType.getSlug(), e.getMessage());
                        }
                }

                return ResourceTypeDto.Response.builder()
                                .id(resourceType.getId())
                                .slug(resourceType.getSlug())
                                .name(resourceType.getName())
                                .description(resourceType.getDescription())
                                .definition(definition)
                                .deleted(resourceType.getDeleted())
                                .created(resourceType.getCreated())
                                .modified(resourceType.getModified())
                                .build();
        }

        private Map<String, Object> createResponse(String status, String message, Object body, String url) {
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("header", Map.of(
                                "id", java.util.UUID.randomUUID().toString(),
                                "status", status,
                                "servertime", System.currentTimeMillis() / 1000,
                                "code", "success".equals(status) ? 200 : 400,
                                "message", message,
                                "url", url));
                response.put("body", body != null ? body : new LinkedHashMap<>());
                return response;
        }
}
