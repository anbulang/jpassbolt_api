package com.jpassbolt.api.service;

import com.jpassbolt.api.model.Role;
import com.jpassbolt.api.repository.RoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Read-only service for roles, mirroring the PHP RolesTable find('all').
 * The roles table has no "deleted" column, so no soft-delete filtering
 * applies, and the reference implementation defines no ordering clause.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoleService {

    private final RoleRepository roleRepository;

    /**
     * Returns all roles.
     *
     * @return list of all roles in the database
     */
    @Transactional(readOnly = true)
    public List<Role> getAllRoles() {
        return roleRepository.findAll();
    }
}
