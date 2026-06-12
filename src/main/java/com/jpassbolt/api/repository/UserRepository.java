package com.jpassbolt.api.repository;

import com.jpassbolt.api.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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
}
