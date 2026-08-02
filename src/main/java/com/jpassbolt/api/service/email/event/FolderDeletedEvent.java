package com.jpassbolt.api.service.email.event;

import java.util.Set;

/**
 * Published by {@code FolderService.deleteFolder()} after a folder delete
 * commits, consumed by {@code DeleteFolderEmailRedactor}. Port of PHP
 * {@code FoldersDeleteService::FOLDERS_DELETE_FOLDER_EVENT} consumed by
 * {@code DeleteFolderEmailRedactor} (gate {@code send.folder.delete}).
 *
 * <p>Unlike the update event, the recipients are carried <em>on the event</em>:
 * the delete physically removes the folder's permission rows, so by the time the
 * {@code AFTER_COMMIT} listener runs there is nothing left to resolve "who had
 * access" from. {@link #recipientUserIds} is therefore snapshotted in the service
 * <em>before</em> deletion (the same {@code $users} the PHP service hands the
 * event). The operator is kept in the set — they get the "You deleted …"
 * wording, others get "{actor} deleted …". The folder itself no longer exists,
 * so the notification carries no deep link, only the (snapshotted) name.</p>
 *
 * @param folderId        the deleted folder id (informational only; no live link)
 * @param folderName      v4 plaintext name, or {@code null} for v5
 * @param isV5            whether the folder carried a v5 encrypted metadata blob
 * @param actorId         the deleter's user id (kept, gets the "you" wording)
 * @param recipientUserIds users who had access at deletion time (the recipients)
 */
public record FolderDeletedEvent(
        String folderId,
        String folderName,
        boolean isV5,
        String actorId,
        Set<String> recipientUserIds) {
}
