package com.jpassbolt.api.service.email.event;

/**
 * Published by {@code FolderService.updateFolder()} after a folder update
 * commits, consumed by {@code UpdateFolderEmailRedactor}. Port of PHP
 * {@code FoldersUpdateService::FOLDERS_UPDATE_FOLDER_EVENT} consumed by
 * {@code UpdateFolderEmailRedactor} (gate {@code send.folder.update}).
 *
 * <p>Recipients are <em>everyone with access</em> to the folder, the operator
 * included (they get the "You edited …" wording, others get "{actor} edited …").
 * Because an update neither removes the folder nor its permissions, the recipient
 * set is re-resolved in the redactor at {@code AFTER_COMMIT} time (the folder and
 * its permission rows still exist), exactly as PHP's redactor calls
 * {@code getUsersIdsHavingAccessTo} — so only the scalar folder snapshot travels
 * on the event.</p>
 *
 * @param folderId   the updated folder id (builds the SPA deep link)
 * @param folderName v4 plaintext name, or {@code null} for v5
 * @param isV5       whether the folder carries a v5 encrypted metadata blob
 * @param actorId    the editor's user id (kept, gets the "you" wording)
 */
public record FolderUpdatedEvent(
        String folderId,
        String folderName,
        boolean isV5,
        String actorId) {
}
