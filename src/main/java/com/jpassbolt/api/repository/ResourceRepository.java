package com.jpassbolt.api.repository;

import com.jpassbolt.api.model.Resource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ResourceRepository extends JpaRepository<Resource, String> {

    List<Resource> findByDeletedFalse();

    List<Resource> findByCreatedBy(String userId);
}
