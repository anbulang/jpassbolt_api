package com.jpassbolt.api.controller;

import com.jpassbolt.api.dto.FolderDto;
import com.jpassbolt.api.dto.ResourceDto;
import com.jpassbolt.api.exception.PassboltApiException;
import com.jpassbolt.api.model.Folder;
import com.jpassbolt.api.model.FoldersRelation;
import com.jpassbolt.api.model.Permission;
import com.jpassbolt.api.model.Resource;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.FoldersRelationRepository;
import com.jpassbolt.api.repository.PermissionRepository;
import com.jpassbolt.api.repository.ResourceRepository;
import com.jpassbolt.api.repository.UserRepository;
import com.jpassbolt.api.service.FolderService;
import com.jpassbolt.api.util.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * FolderController — folders CRUD (PHP plugin Passbolt/Folders, v4).
 *
 * Note on mappings: no class-level @RequestMapping (Boot 3 PathPatternParser
 * would turn "/folders" + ".json" into "/folders/.json"), full method-level
 * paths instead — same rationale as ResourceController.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class FolderController {

    private final FolderService folderService;
    private final FoldersRelationRepository foldersRelationRepository;
    private final PermissionRepository permissionRepository;
    private final ResourceRepository resourceRepository;
    private final UserRepository userRepository;

    /**
     * GET /folders.json
     * Returns all folders the current user has READ access to. Supports
     * filter[has-id] and contain[children_resources|children_folders|permissions].
     */
    @GetMapping({ "/folders", "/folders.json" })
    public ResponseEntity<Map<String, Object>> getAllFolders(
            @RequestParam(name = "filter[has-id]", required = false) String hasId,
            @RequestParam(name = "contain[children_resources]", required = false) Integer containChildrenResources,
            @RequestParam(name = "contain[children_folders]", required = false) Integer containChildrenFolders,
            @RequestParam(name = "contain[permissions]", required = false) Integer containPermissions) {
        String userId = getCurrentUserId();

        List<Folder> folders = folderService.getAccessibleFolders(userId);
        if (hasId != null) {
            folders = folders.stream()
                    .filter(f -> f.getId().equals(hasId))
                    .collect(Collectors.toList());
        }

        boolean withPermissions = Integer.valueOf(1).equals(containPermissions);
        boolean withChildrenResources = Integer.valueOf(1).equals(containChildrenResources);
        boolean withChildrenFolders = Integer.valueOf(1).equals(containChildrenFolders);

        List<FolderDto.Response> responseList = folders.stream()
                .map(f -> toResponseDto(f, userId, withPermissions, withChildrenResources, withChildrenFolders))
                .collect(Collectors.toList());

        return ResponseEntity.ok(createResponse("success", "The operation was successful.",
                responseList, "/folders.json"));
    }

    /**
     * GET /folders/{id}.json
     * Returns a single folder. Like PHP, an invisible or missing folder is a
     * 404 (no 403 on view).
     */
    @GetMapping("/folders/{id}.json")
    public ResponseEntity<Map<String, Object>> getFolder(
            @PathVariable String id,
            @RequestParam(name = "contain[children_resources]", required = false) Integer containChildrenResources,
            @RequestParam(name = "contain[children_folders]", required = false) Integer containChildrenFolders,
            @RequestParam(name = "contain[permissions]", required = false) Integer containPermissions) {
        String userId = getCurrentUserId();
        String url = "/folders/" + id + ".json";

        if (!folderService.userHasFolderAccess(id, userId, Permission.READ)) {
            return ResponseEntity.status(404)
                    .body(createResponse("error", "The folder does not exist.", null, url));
        }

        boolean withPermissions = Integer.valueOf(1).equals(containPermissions);
        boolean withChildrenResources = Integer.valueOf(1).equals(containChildrenResources);
        boolean withChildrenFolders = Integer.valueOf(1).equals(containChildrenFolders);

        return folderService.getFolderById(id)
                .map(folder -> ResponseEntity.ok(createResponse("success", "The operation was successful.",
                        toResponseDto(folder, userId, withPermissions, withChildrenResources, withChildrenFolders),
                        url)))
                .orElse(ResponseEntity.status(404)
                        .body(createResponse("error", "The folder does not exist.", null, url)));
    }

    /**
     * POST /folders.json
     * Creates a folder; the creator gets an OWNER permission and a relation
     * row in their tree. Returns 200 like the reference implementation.
     */
    @PostMapping({ "/folders", "/folders.json" })
    public ResponseEntity<Map<String, Object>> createFolder(@RequestBody FolderDto.CreateRequest request) {
        String userId = getCurrentUserId();
        Folder folder = folderService.createFolder(request, userId);
        return ResponseEntity.ok(createResponse("success", "The folder has been added successfully.",
                toResponseDto(folder, userId, false, false, false), "/folders.json"));
    }

    /**
     * PUT /folders/{id}.json
     * Renames a folder. 404 when invisible, 403 when below UPDATE.
     */
    @PutMapping("/folders/{id}.json")
    public ResponseEntity<Map<String, Object>> updateFolder(
            @PathVariable String id,
            @RequestBody FolderDto.UpdateRequest request) {
        String userId = getCurrentUserId();
        Folder folder = folderService.updateFolder(id, request, userId);
        return ResponseEntity.ok(createResponse("success", "The folder has been updated successfully.",
                toResponseDto(folder, userId, false, false, false), "/folders/" + id + ".json"));
    }

    /**
     * DELETE /folders/{id}.json[?cascade=1]
     * Hard-deletes a folder. Without cascade the content moves to the root;
     * with cascade the writable content is deleted too. Requires UPDATE.
     */
    @DeleteMapping("/folders/{id}.json")
    public ResponseEntity<Map<String, Object>> deleteFolder(
            @PathVariable String id,
            @RequestParam(name = "cascade", required = false) String cascade) {
        String userId = getCurrentUserId();
        boolean cascadeFlag = "1".equals(cascade) || "true".equalsIgnoreCase(cascade);
        folderService.deleteFolder(id, cascadeFlag, userId);
        return ResponseEntity.ok(createResponse("success", "The folder has been deleted successfully.",
                null, "/folders/" + id + ".json"));
    }

    private FolderDto.Response toResponseDto(Folder folder, String userId,
            boolean withPermissions, boolean withChildrenResources, boolean withChildrenFolders) {
        FolderDto.Response dto = FolderDto.Response.builder()
                .id(folder.getId())
                .name(folder.getName())
                .created(folder.getCreated())
                .modified(folder.getModified())
                .createdBy(folder.getCreatedBy())
                .modifiedBy(folder.getModifiedBy())
                .folderParentId(folderService.getFolderParentIdForUser(folder.getId(), userId))
                .personal(folderService.isPersonal(folder.getId()))
                .build();

        if (withPermissions) {
            dto.setPermissions(permissionRepository
                    .findByAcoAndAcoForeignKey(FolderService.FOLDER_ACO, folder.getId()).stream()
                    .map(this::toPermissionDto)
                    .collect(Collectors.toList()));
        }
        if (withChildrenResources) {
            List<String> resourceIds = foldersRelationRepository
                    .findByUserIdAndFolderParentId(userId, folder.getId()).stream()
                    .filter(rel -> FoldersRelation.FOREIGN_MODEL_RESOURCE.equals(rel.getForeignModel()))
                    .map(FoldersRelation::getForeignId)
                    .collect(Collectors.toList());
            List<ResourceDto.Response> childrenResources = resourceIds.isEmpty()
                    ? List.of()
                    : resourceRepository.findAllById(resourceIds).stream()
                            .filter(r -> !r.getDeleted())
                            .map(this::toResourceDto)
                            .collect(Collectors.toList());
            dto.setChildrenResources(childrenResources);
        }
        if (withChildrenFolders) {
            List<FolderDto.Response> childrenFolders = foldersRelationRepository
                    .findByUserIdAndFolderParentId(userId, folder.getId()).stream()
                    .filter(rel -> FoldersRelation.FOREIGN_MODEL_FOLDER.equals(rel.getForeignModel()))
                    .map(rel -> folderService.getFolderById(rel.getForeignId()))
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    // children are rendered flat (no nested contains)
                    .map(child -> toResponseDto(child, userId, false, false, false))
                    .collect(Collectors.toList());
            dto.setChildrenFolders(childrenFolders);
        }
        return dto;
    }

    private FolderDto.PermissionResponse toPermissionDto(Permission permission) {
        return FolderDto.PermissionResponse.builder()
                .id(permission.getId())
                .aco(permission.getAco())
                .acoForeignKey(permission.getAcoForeignKey())
                .aro(permission.getAro())
                .aroForeignKey(permission.getAroForeignKey())
                .type(permission.getType())
                .created(permission.getCreated())
                .modified(permission.getModified())
                .build();
    }

    private ResourceDto.Response toResourceDto(Resource resource) {
        return ResourceDto.Response.builder()
                .id(resource.getId())
                .name(resource.getName())
                .username(resource.getUsername())
                .uri(resource.getUri())
                .description(resource.getDescription())
                .deleted(resource.getDeleted())
                .expired(resource.getExpired())
                .created(resource.getCreated())
                .modified(resource.getModified())
                .createdBy(resource.getCreatedBy())
                .modifiedBy(resource.getModifiedBy())
                .resourceTypeId(resource.getResourceTypeId())
                .build();
    }

    private Map<String, Object> createResponse(String status, String message, Object body, String url) {
        // 迁移到共享信封工具：补 action(uuid) 等 spec required 字段，保留原 200/400 code 语义。
        return ApiResponse.withCode(status, message, body, "success".equals(status) ? 200 : 400, url);
    }

    private String getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            throw new PassboltApiException(HttpStatus.UNAUTHORIZED, "No authenticated user");
        }
        String username = auth.getName();
        Optional<User> user = userRepository.findByUsername(username);
        return user.map(User::getId)
                .orElseThrow(() -> new PassboltApiException(HttpStatus.NOT_FOUND,
                        "User not found: " + username));
    }
}
