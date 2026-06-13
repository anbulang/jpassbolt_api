package com.jpassbolt.api.repository;

import com.jpassbolt.api.model.GroupUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for GroupUser (groups_users membership) entity operations.
 */
@Repository
public interface GroupUserRepository extends JpaRepository<GroupUser, String> {

    /**
     * Find all memberships of a group.
     */
    List<GroupUser> findByGroupId(String groupId);

    /**
     * Find all memberships of a user (the groups the user belongs to).
     */
    List<GroupUser> findByUserId(String userId);

    /**
     * Find the group managers of a group.
     */
    List<GroupUser> findByGroupIdAndIsAdminTrue(String groupId);

    /**
     * Find the membership of a specific user in a specific group.
     */
    Optional<GroupUser> findByGroupIdAndUserId(String groupId, String userId);

    /**
     * Check whether a user is a manager (is_admin) of a group.
     */
    boolean existsByGroupIdAndUserIdAndIsAdminTrue(String groupId, String userId);

    /**
     * Delete all memberships of a group (requires an active transaction).
     */
    void deleteByGroupId(String groupId);

    /**
     * Delete all memberships of a user (user deletion cascade, requires an
     * active transaction) — PHP UsersTable::softDelete deleteAll.
     */
    void deleteByUserId(String userId);

    /**
     * Groups where the user is the only manager (other groups may have more
     * managers) — PHP findGroupsWhereUserIsSoleManager.
     */
    @Query("SELECT gu.groupId FROM GroupUser gu WHERE gu.userId = :userId AND gu.isAdmin = true " +
            "AND gu.groupId NOT IN (SELECT gu2.groupId FROM GroupUser gu2 " +
            "WHERE gu2.userId <> :userId AND gu2.isAdmin = true)")
    List<String> findGroupIdsWhereUserIsSoleManager(@Param("userId") String userId);

    /**
     * Groups where the user is the only member —
     * PHP findGroupsWhereUserOnlyMember.
     */
    @Query("SELECT DISTINCT gu.groupId FROM GroupUser gu WHERE gu.userId = :userId " +
            "AND gu.groupId NOT IN (SELECT gu2.groupId FROM GroupUser gu2 WHERE gu2.userId <> :userId)")
    List<String> findGroupIdsWhereUserOnlyMember(@Param("userId") String userId);

    /**
     * Member user ids of a group whose user row is not soft-deleted —
     * defensive filter for permission fan-outs (a deleted user must never be
     * required to receive a Secret).
     */
    @Query("SELECT gu.userId FROM GroupUser gu, User u " +
            "WHERE gu.groupId = :groupId AND u.id = gu.userId AND u.deleted = false")
    List<String> findActiveMemberUserIds(@Param("groupId") String groupId);
}
