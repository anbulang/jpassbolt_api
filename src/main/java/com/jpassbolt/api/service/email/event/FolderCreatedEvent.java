package com.jpassbolt.api.service.email.event;

/**
 * Published by {@code FolderService.createFolder()} after a folder create
 * commits, consumed by {@code CreateFolderEmailRedactor}. Port of PHP
 * {@code FoldersCreateService::FOLDERS_CREATE_FOLDER_EVENT} consumed by
 * {@code CreateFolderEmailRedactor} (gate {@code send.folder.create}).
 *
 * <p>The sole recipient is the creator themselves — a "you added the folder X"
 * confirmation. An immutable scalar snapshot rather than a JPA entity: the
 * {@code AFTER_COMMIT} listener runs on a detached async thread, so the v4
 * plaintext {@link #folderName} is captured at publish time (and is {@code null}
 * for a v5 folder, whose name lives inside the encrypted metadata blob —
 * {@link #isV5} is then true and the subject/body use the generic wording).</p>
 *
 * @param folderId   the created folder id (builds the SPA deep link)
 * @param folderName v4 plaintext name, or {@code null} for v5
 * @param isV5       whether the folder carries a v5 encrypted metadata blob
 * @param actorId    the creator's user id (also the only recipient)
 */
public record FolderCreatedEvent(
        String folderId,
        String folderName,
        boolean isV5,
        String actorId) {
}
