package com.jpassbolt.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * FoldersRelation entity — one row per (item, user) describing where the item
 * sits in THAT user's tree ({@code folder_parent_id}, null = root).
 *
 * <p>
 * The same item (folder or resource) can live under different parents for
 * different users; moving an item only rewrites relation rows, never the
 * folders table itself (PHP plugin: Passbolt/Folders FoldersRelations).
 * </p>
 * <p>
 * Schema notes: matches the official {@code folders_relations} DDL exactly —
 * no created_by/modified_by columns, no FK constraints (indexes only), so no
 * JPA navigation associations are mapped.
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "folders_relations")
public class FoldersRelation extends BaseEntity {

    /** Allowed foreign models. */
    public static final String FOREIGN_MODEL_FOLDER = "Folder";
    public static final String FOREIGN_MODEL_RESOURCE = "Resource";

    /** The type of item the relation locates: "Folder" or "Resource". */
    @Column(name = "foreign_model", nullable = false, length = 30)
    private String foreignModel;

    /** The id of the located item (folder id or resource id). */
    @Column(name = "foreign_id", nullable = false, length = 36, columnDefinition = "char(36)")
    private String foreignId;

    /** The user whose tree this relation belongs to. */
    @Column(name = "user_id", nullable = false, length = 36, columnDefinition = "char(36)")
    private String userId;

    /** The parent folder in this user's tree; null means root. */
    @Column(name = "folder_parent_id", length = 36, columnDefinition = "char(36)")
    private String folderParentId;
}
