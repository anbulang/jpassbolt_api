package com.jpassbolt.api.service.email.event;

import java.util.Set;

/**
 * Published by {@code PermissionService.shareFolder()} after a folder share
 * commits, consumed by {@code ShareFolderEmailRedactor}. Port of PHP
 * {@code FoldersShareService::FOLDERS_SHARE_FOLDER_EVENT} consumed by
 * {@code ShareFolderEmailRedactor} (gate {@code send.folder.share}).
 *
 * <p>Recipients are the users who <em>newly gained</em> access — the difference
 * between the post- and pre-change access sets computed by the share. The actor
 * kept their existing access and is never in the added set, but the redactor
 * still filters them out defensively. Only published when someone actually gained
 * access (a revoke-only share notifies no one).</p>
 *
 * @param folderId     the shared folder id (builds the SPA deep link)
 * @param folderName   v4 plaintext name, or {@code null} for v5
 * @param isV5         whether the folder carries a v5 encrypted metadata blob
 * @param actorId      the sharer's user id (excluded from recipients)
 * @param addedUserIds users who newly gained access (the recipients)
 */
public record FolderSharedEvent(
        String folderId,
        String folderName,
        boolean isV5,
        String actorId,
        Set<String> addedUserIds) {
}
