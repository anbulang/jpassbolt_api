package com.jpassbolt.api.repository;

import com.jpassbolt.api.model.Profile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface ProfileRepository extends JpaRepository<Profile, String> {

    /**
     * Find the profile belonging to a user (1:1 in practice).
     */
    Optional<Profile> findByUserId(String userId);

    /**
     * Batch lookup for index rendering (avoids N+1 when listing users).
     */
    List<Profile> findByUserIdIn(Collection<String> userIds);
}
