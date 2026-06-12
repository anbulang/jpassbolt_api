package com.jpassbolt.api.repository;

import com.jpassbolt.api.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, String> {
    Optional<User> findByUsername(String username);

    List<User> findByDeletedFalse();

    Optional<User> findByUsernameAndDeletedFalse(String username);

    /**
     * Username uniqueness check scoped to non-deleted users (PHP
     * isUniqueUsername only looks at deleted=false rows).
     */
    boolean existsByUsernameAndDeletedFalse(String username);

    Optional<User> findByIdAndDeletedFalse(String id);

    /**
     * search-aros user search (PHP UsersFindersTrait::findIndex with
     * filter[search] + forced filter[is-active]): active, non-deleted,
     * guest role excluded; case-insensitive LIKE across username and the
     * profile first/last name. Caller passes term = "%" + lower(search) + "%"
     * ("%%" for an empty search) and PageRequest.of(0, 25).
     */
    @Query("SELECT DISTINCT u FROM User u LEFT JOIN Profile p ON p.userId = u.id " +
           "WHERE u.deleted = false AND u.active = true AND u.roleId <> :guestRoleId " +
           "AND (LOWER(u.username) LIKE :term OR LOWER(p.firstName) LIKE :term OR LOWER(p.lastName) LIKE :term)")
    List<User> searchActiveAros(@Param("term") String term, @Param("guestRoleId") String guestRoleId, Pageable pageable);
}
