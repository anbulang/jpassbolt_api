package com.jpassbolt.api.repository;

import com.jpassbolt.api.model.Permission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for Permission entity operations.
 */
@Repository
public interface PermissionRepository extends JpaRepository<Permission, String> {

        /**
         * Find all permissions for a specific resource.
         */
        List<Permission> findByAcoAndAcoForeignKey(String aco, String acoForeignKey);

        /**
         * Find all permissions for a specific user.
         */
        List<Permission> findByAroAndAroForeignKey(String aro, String aroForeignKey);

        /**
         * Find a specific permission for a resource and user/group.
         */
        Optional<Permission> findByAcoForeignKeyAndAroForeignKey(String acoForeignKey, String aroForeignKey);

        /**
         * Locate a permission by id scoped to its ACO (PHP getPermission does
         * the same id + aco_foreign_key lookup, preventing cross-resource id
         * injection in share payloads).
         */
        Optional<Permission> findByIdAndAcoForeignKey(String id, String acoForeignKey);

        /**
         * All permission rows of an ACO for one ARO kind ("User" or "Group") —
         * used by getUsersIdsHavingAccessTo to fan groups out to their members.
         */
        List<Permission> findByAcoForeignKeyAndAro(String acoForeignKey, String aro);

        /**
         * Check if a user has at least the specified permission level on a resource.
         */
        @Query("SELECT CASE WHEN COUNT(p) > 0 THEN true ELSE false END FROM Permission p " +
                        "WHERE p.aco = :aco AND p.acoForeignKey = :acoForeignKey " +
                        "AND p.aro = :aro AND p.aroForeignKey = :aroForeignKey " +
                        "AND p.type >= :minType")
        boolean hasAccess(
                        @Param("aco") String aco,
                        @Param("acoForeignKey") String acoForeignKey,
                        @Param("aro") String aro,
                        @Param("aroForeignKey") String aroForeignKey,
                        @Param("minType") int minType);

        /**
         * Group-inclusive access check (PHP PermissionsFindersTrait::hasAccess →
         * findHighestByAcoAndAro → findAllByAro with checkGroupsUsers=true): the
         * user's direct "User" rows are merged with the "Group" rows of every
         * group the user belongs to, and access is granted as soon as any of
         * them reaches the requested level. Single query — replaces the former
         * N+1 service-level fan-out.
         */
        @Query("SELECT CASE WHEN COUNT(p) > 0 THEN true ELSE false END FROM Permission p " +
                        "WHERE p.aco = :aco AND p.acoForeignKey = :acoForeignKey " +
                        "AND p.type >= :minType " +
                        "AND ((p.aro = 'User' AND p.aroForeignKey = :userId) " +
                        "OR (p.aro = 'Group' AND p.aroForeignKey IN " +
                        "(SELECT gu.groupId FROM GroupUser gu WHERE gu.userId = :userId)))")
        boolean hasAccessIncludingGroups(
                        @Param("aco") String aco,
                        @Param("acoForeignKey") String acoForeignKey,
                        @Param("userId") String userId,
                        @Param("minType") int minType);

        /**
         * Delete all permissions for a specific resource.
         */
        void deleteByAcoForeignKey(String acoForeignKey);

        /**
         * Find all permissions for a resource.
         */
        default List<Permission> findByResourceId(String resourceId) {
                return findByAcoAndAcoForeignKey(Permission.RESOURCE_ACO, resourceId);
        }

        /**
         * Check if a user has access to a resource with at least the given permission
         * level.
         */
        default boolean userHasAccess(String resourceId, String userId, int minPermissionType) {
                return hasAccess(Permission.RESOURCE_ACO, resourceId, Permission.USER_ARO, userId, minPermissionType);
        }

        /**
         * Check if a user has access to a resource with at least the given
         * permission level, either through a direct "User" permission or
         * inherited from one of their groups (Resource ACO wrapper, symmetric
         * with {@link #userHasAccess}).
         */
        default boolean userHasAccessIncludingGroups(String resourceId, String userId, int minPermissionType) {
                return hasAccessIncludingGroups(Permission.RESOURCE_ACO, resourceId, userId, minPermissionType);
        }

        /**
         * Find all resource IDs that a user has at least the given permission level on.
         */
        @Query("SELECT p.acoForeignKey FROM Permission p " +
                        "WHERE p.aco = 'Resource' AND p.aro = 'User' " +
                        "AND p.aroForeignKey = :userId AND p.type >= :minType")
        List<String> findAccessibleResourceIds(
                        @Param("userId") String userId,
                        @Param("minType") int minType);

        /**
         * Group-inclusive variant of {@link #findAccessibleResourceIds}: every
         * resource the user can reach with at least the given level, through a
         * direct "User" row or any "Group" row of a group the user belongs to
         * (DISTINCT — a user may hold several access paths to one resource).
         * Used by the resources index so group-shared resources are listed.
         */
        @Query("SELECT DISTINCT p.acoForeignKey FROM Permission p " +
                        "WHERE p.aco = 'Resource' AND p.type >= :minType " +
                        "AND ((p.aro = 'User' AND p.aroForeignKey = :userId) " +
                        "OR (p.aro = 'Group' AND p.aroForeignKey IN " +
                        "(SELECT gu.groupId FROM GroupUser gu WHERE gu.userId = :userId)))")
        List<String> findAccessibleResourceIdsIncludingGroups(
                        @Param("userId") String userId,
                        @Param("minType") int minType);

        /**
         * Resource ids where the user is the sole OWNER of a SHARED resource
         * (exactly one OWNER permission, more than one permission in total) —
         * PHP findSharedAcosByAroIsSoleOwner, User ARO only. The group
         * dimension is composed in UserDeleteService from the ARO-set
         * variants below (PHP checkGroupsUsers branch).
         */
        @Query("SELECT p.acoForeignKey FROM Permission p WHERE p.aco = 'Resource' AND p.aro = 'User' AND p.aroForeignKey = :userId AND p.type = 15 " +
                        "AND (SELECT COUNT(p2) FROM Permission p2 WHERE p2.acoForeignKey = p.acoForeignKey AND p2.type = 15) = 1 " +
                        "AND (SELECT COUNT(p3) FROM Permission p3 WHERE p3.acoForeignKey = p.acoForeignKey) > 1")
        List<String> findSharedResourceIdsWhereUserIsSoleOwner(@Param("userId") String userId);

        /**
         * Resource ids only this user can access (single permission row) —
         * PHP findAcosOnlyAroCanAccess; soft-deleted along with the user.
         */
        @Query("SELECT p.acoForeignKey FROM Permission p WHERE p.aco = 'Resource' AND p.aro = 'User' AND p.aroForeignKey = :userId " +
                        "AND (SELECT COUNT(p2) FROM Permission p2 WHERE p2.acoForeignKey = p.acoForeignKey) = 1")
        List<String> findResourceIdsOnlyAccessibleByUser(@Param("userId") String userId);

        /**
         * Resource ids whose every OWNER permission belongs to one of the
         * given AROs (user + groups) — PHP findAcosByArosAreSoleOwner.
         */
        @Query("SELECT DISTINCT p.acoForeignKey FROM Permission p WHERE p.aco = 'Resource' AND p.type = 15 " +
                        "AND p.aroForeignKey IN :aros " +
                        "AND p.acoForeignKey NOT IN (SELECT p2.acoForeignKey FROM Permission p2 " +
                        "WHERE p2.aco = 'Resource' AND p2.type = 15 AND p2.aroForeignKey NOT IN :aros)")
        List<String> findResourceIdsWhereArosAreSoleOwner(
                        @Param("aros") java.util.Collection<String> aros);

        /**
         * Resource ids on which at least one of the given AROs holds an OWNER
         * permission (used to subtract the resources owned by non-empty
         * sole-manager groups, PHP checkGroupsUsers branch).
         */
        @Query("SELECT DISTINCT p.acoForeignKey FROM Permission p WHERE p.aco = 'Resource' AND p.type = 15 " +
                        "AND p.aroForeignKey IN :aros")
        List<String> findResourceIdsOwnedByAros(@Param("aros") java.util.Collection<String> aros);

        /**
         * Resource ids only the given AROs (user + only-member groups) can
         * access — PHP findAcosOnlyAroCanAccess(checkGroupsUsers=true).
         */
        @Query("SELECT DISTINCT p.acoForeignKey FROM Permission p WHERE p.aco = 'Resource' " +
                        "AND p.aroForeignKey IN :aros " +
                        "AND p.acoForeignKey NOT IN (SELECT p2.acoForeignKey FROM Permission p2 " +
                        "WHERE p2.aco = 'Resource' AND p2.aroForeignKey NOT IN :aros)")
        List<String> findResourceIdsOnlyAccessibleByAros(
                        @Param("aros") java.util.Collection<String> aros);

        /**
         * Group-inclusive accessible ACO ids for any ACO kind (Folder or
         * Resource) — used by the folders index so group-shared folders are
         * listed (PHP findAllByAro checkGroupsUsers=true).
         */
        @Query("SELECT DISTINCT p.acoForeignKey FROM Permission p " +
                        "WHERE p.aco = :aco AND p.type >= :minType " +
                        "AND ((p.aro = 'User' AND p.aroForeignKey = :userId) " +
                        "OR (p.aro = 'Group' AND p.aroForeignKey IN " +
                        "(SELECT gu.groupId FROM GroupUser gu WHERE gu.userId = :userId)))")
        List<String> findAccessibleAcoIdsIncludingGroups(
                        @Param("aco") String aco,
                        @Param("userId") String userId,
                        @Param("minType") int minType);

        /**
         * Hard-delete every permission row of an ARO (user deletion cascade).
         * Caller must be @Transactional.
         */
        void deleteByAroAndAroForeignKey(String aro, String aroForeignKey);
}
