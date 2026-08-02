package com.jpassbolt.api.service;

import com.jpassbolt.api.model.Folder;
import com.jpassbolt.api.model.FoldersRelation;
import com.jpassbolt.api.model.Permission;
import com.jpassbolt.api.model.Resource;
import com.jpassbolt.api.model.Role;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.FolderRepository;
import com.jpassbolt.api.repository.FoldersRelationRepository;
import com.jpassbolt.api.repository.PermissionRepository;
import com.jpassbolt.api.repository.ResourceRepository;
import com.jpassbolt.api.repository.RoleRepository;
import com.jpassbolt.api.repository.UserRepository;
import com.jpassbolt.api.service.email.event.FolderDeletedEvent;
import com.jpassbolt.api.service.email.event.ResourceDeletedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the notification fan-out of a CASCADING folder delete.
 *
 * <p>
 * Deleting a folder with {@code cascade=1} soft-deletes every child resource.
 * Publishing a {@link ResourceDeletedEvent} for each of them would mail every
 * recipient one "X deleted the password Y" notice per child on top of the single
 * folder-delete notice — a fan-out official Passbolt never produces:
 * {@code ResourceDeleteEmailRedactor} subscribes only to
 * {@code ResourcesDeleteController::DELETE_SUCCESS_EVENT_NAME}, and
 * {@code FoldersDeleteService::deleteResource} calls
 * {@code ResourcesTable::softDelete()} directly, bypassing that controller.
 * </p>
 *
 * <p>
 * Asserting on the published EVENTS rather than on delivered mail is deliberate:
 * the redactors are {@code AFTER_COMMIT} listeners, which never fire under a
 * rolled-back {@code @Transactional} test. The event is the last observable
 * point inside the transaction and is exactly what the fix moved.
 * </p>
 */
@SpringBootTest
@Transactional
@RecordApplicationEvents
class ResourceDeleteCascadeEventTest {

    @Autowired private FolderService folderService;
    @Autowired private ResourceService resourceService;
    @Autowired private ApplicationEvents events;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private FolderRepository folderRepository;
    @Autowired private ResourceRepository resourceRepository;
    @Autowired private PermissionRepository permissionRepository;
    @Autowired private FoldersRelationRepository foldersRelationRepository;

    private User owner;

    @BeforeEach
    void seed() {
        Role role = new Role();
        role.setName("user");
        String roleId = roleRepository.save(role).getId();

        owner = new User();
        owner.setUsername("cascade-owner@passbolt.com");
        owner.setRoleId(roleId);
        owner.setActive(true);
        owner.setDeleted(false);
        owner = userRepository.save(owner);
    }

    private Folder folder(String name) {
        Folder f = new Folder();
        f.setName(name);
        f.setCreatedBy(owner.getId());
        f.setModifiedBy(owner.getId());
        f = folderRepository.save(f);
        grant(FolderService.FOLDER_ACO, f.getId());
        relation(FoldersRelation.FOREIGN_MODEL_FOLDER, f.getId(), null);
        return f;
    }

    private Resource resource(String name, String parentId) {
        Resource r = new Resource();
        r.setName(name);
        r.setCreatedBy(owner.getId());
        r.setModifiedBy(owner.getId());
        r.setDeleted(false);
        r = resourceRepository.save(r);
        grant(Permission.RESOURCE_ACO, r.getId());
        relation(FoldersRelation.FOREIGN_MODEL_RESOURCE, r.getId(), parentId);
        return r;
    }

    private void grant(String aco, String acoForeignKey) {
        Permission p = new Permission();
        p.setAco(aco);
        p.setAcoForeignKey(acoForeignKey);
        p.setAro(Permission.USER_ARO);
        p.setAroForeignKey(owner.getId());
        p.setType(Permission.OWNER);
        permissionRepository.save(p);
    }

    private void relation(String model, String foreignId, String parentId) {
        FoldersRelation rel = new FoldersRelation();
        rel.setForeignModel(model);
        rel.setForeignId(foreignId);
        rel.setUserId(owner.getId());
        rel.setFolderParentId(parentId);
        foldersRelationRepository.save(rel);
    }

    @Test
    void cascadingFolderDelete_publishesNoResourceDeletedEvent() {
        Folder parent = folder("Ops");
        Resource child = resource("db-root", parent.getId());

        folderService.deleteFolder(parent.getId(), true, owner.getId());

        assertThat(resourceRepository.findById(child.getId()).orElseThrow().getDeleted())
                .as("the child resource is still soft-deleted by the cascade")
                .isTrue();
        assertThat(events.stream(FolderDeletedEvent.class).count())
                .as("the single folder-delete notice is the whole notification")
                .isEqualTo(1);
        assertThat(events.stream(ResourceDeletedEvent.class).count())
                .as("no per-child password-delete mail — PHP's FoldersDeleteService "
                        + "bypasses ResourcesDeleteController, the only event the redactor subscribes to")
                .isZero();
    }

    @Test
    void directResourceDelete_stillPublishesResourceDeletedEvent() {
        // The guard against over-correcting: suppressing the cascade must not
        // suppress the ordinary DELETE /resources/{id}.json notification.
        Resource standalone = resource("standalone", null);

        boolean deleted = resourceService.deleteResource(standalone.getId(), owner.getId());

        assertThat(deleted).isTrue();
        assertThat(events.stream(ResourceDeletedEvent.class).count()).isEqualTo(1);
    }
}
