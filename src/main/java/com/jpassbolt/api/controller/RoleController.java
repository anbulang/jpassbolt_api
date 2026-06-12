package com.jpassbolt.api.controller;

import com.jpassbolt.api.dto.RoleDto;
import com.jpassbolt.api.model.Role;
import com.jpassbolt.api.service.RoleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * RoleController provides the read-only roles index endpoint.
 * Mirrors the PHP RolesIndexController: any authenticated user may list
 * roles (no admin-only restriction, no filters, no pagination).
 * Authentication is enforced globally by JwtAuthenticationFilter +
 * anyRequest().authenticated(); no userId is needed here.
 *
 * Note on mappings: no class-level @RequestMapping — with Boot 3's
 * PathPatternParser, "/roles" combined with ".json" yields "/roles/.json"
 * (NOT "/roles.json"), so full method-level paths are used instead.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class RoleController {

        private final RoleService roleService;

        /**
         * GET /roles.json
         * Returns all roles. Any authenticated user can access this endpoint.
         */
        @GetMapping({ "/roles", "/roles.json" })
        public ResponseEntity<Map<String, Object>> index() {
                List<Role> roles = roleService.getAllRoles();
                List<RoleDto.Response> responseList = roles.stream()
                                .map(this::toResponseDto)
                                .collect(Collectors.toList());

                return ResponseEntity.ok(createResponse("success", "The operation was successful.",
                                responseList, "/roles.json"));
        }

        private RoleDto.Response toResponseDto(Role role) {
                return RoleDto.Response.builder()
                                .id(role.getId())
                                .name(role.getName())
                                .description(role.getDescription())
                                .created(role.getCreated())
                                .modified(role.getModified())
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
