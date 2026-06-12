package com.jpassbolt.api.service;

import com.jpassbolt.api.model.GpgKey;
import com.jpassbolt.api.model.Group;
import com.jpassbolt.api.model.GroupUser;
import com.jpassbolt.api.model.Profile;
import com.jpassbolt.api.model.Role;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.GpgKeyRepository;
import com.jpassbolt.api.repository.GroupRepository;
import com.jpassbolt.api.repository.GroupUserRepository;
import com.jpassbolt.api.repository.ProfileRepository;
import com.jpassbolt.api.repository.RoleRepository;
import com.jpassbolt.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Query service backing GET /share/search-aros.json — ported from the PHP
 * ShareSearchController (+ UsersFindersTrait::findIndex and
 * GroupsFindersTrait::findIndex).
 *
 * <p>
 * PHP semantics replicated here:
 * users are forced to active=true, deleted=false and the guest role is
 * excluded; the search term matches username / profile first_name /
 * profile last_name (users) and name (groups), case-insensitively;
 * each model is limited to 25 rows BEFORE merging (not 25 overall);
 * groups always carry user_count. The merged alphabetical sort happens in
 * the controller (PHP _formatResult).
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShareSearchService {

    /** Per-model result limit (PHP ShareSearchController::LIMIT). */
    private static final int LIMIT = 25;

    private final UserRepository userRepository;
    private final ProfileRepository profileRepository;
    private final GpgKeyRepository gpgKeyRepository;
    private final RoleRepository roleRepository;
    private final GroupRepository groupRepository;
    private final GroupUserRepository groupUserRepository;

    /**
     * Aggregated search result: matched users with their batch-loaded
     * associations (avoiding N+1 rendering) plus matched groups with their
     * member counts.
     */
    public static class AroSearchResult {
        public List<User> users = new ArrayList<>();
        public Map<String, Profile> profilesByUserId = new HashMap<>();
        public Map<String, GpgKey> gpgkeysByUserId = new HashMap<>();
        public Map<String, Role> rolesById = new HashMap<>();
        public Map<String, List<GroupUser>> groupsUsersByUserId = new HashMap<>();
        public List<Group> groups = new ArrayList<>();
        public Map<String, Integer> userCountByGroupId = new HashMap<>();
    }

    /**
     * Search users and groups to share with.
     *
     * @param search             the raw filter[search] term (null/empty = all)
     * @param containGpgkey      hydrate each user's non-deleted gpgkey
     * @param containRole        hydrate each user's role
     * @param containGroupsUsers hydrate each user's group memberships
     * @return the aggregated result (profiles are ALWAYS loaded — profile is
     *         not part of the PHP contain whitelist and is always rendered)
     */
    @Transactional(readOnly = true)
    public AroSearchResult searchAros(String search, boolean containGpgkey,
            boolean containRole, boolean containGroupsUsers) {
        String needle = search == null ? "" : search.toLowerCase();
        String term = "%" + needle + "%";

        AroSearchResult result = new AroSearchResult();

        // --- Users (active, not deleted, guest role excluded) ---
        String guestRoleId = roleRepository.findByName(Role.GUEST)
                .map(Role::getId)
                .orElse("");
        List<User> users = new ArrayList<>(
                userRepository.searchActiveAros(term, guestRoleId, PageRequest.of(0, LIMIT)));
        users.sort(Comparator.comparing(User::getUsername, String.CASE_INSENSITIVE_ORDER));
        result.users = users;

        Set<String> userIds = users.stream().map(User::getId).collect(Collectors.toSet());

        // Profile is always rendered (not part of the contain whitelist).
        for (Profile profile : profileRepository.findByUserIdIn(userIds)) {
            result.profilesByUserId.putIfAbsent(profile.getUserId(), profile);
        }

        if (containGpgkey && !userIds.isEmpty()) {
            for (GpgKey key : gpgKeyRepository.findByDeleted(false)) {
                if (userIds.contains(key.getUserId())) {
                    result.gpgkeysByUserId.putIfAbsent(key.getUserId(), key);
                }
            }
        }

        if (containRole) {
            Set<String> roleIds = users.stream().map(User::getRoleId).collect(Collectors.toSet());
            for (Role role : roleRepository.findAllById(roleIds)) {
                result.rolesById.put(role.getId(), role);
            }
        }

        if (containGroupsUsers && !userIds.isEmpty()) {
            result.groupsUsersByUserId = groupUserRepository.findAll().stream()
                    .filter(gu -> userIds.contains(gu.getUserId()))
                    .collect(Collectors.groupingBy(GroupUser::getUserId));
        }

        // --- Groups (deleted=false, name LIKE, always with user_count) ---
        List<Group> groups = groupRepository.findByDeletedFalse().stream()
                .filter(g -> g.getName() != null && g.getName().toLowerCase().contains(needle))
                .sorted(Comparator.comparing(Group::getName, String.CASE_INSENSITIVE_ORDER))
                .limit(LIMIT)
                .collect(Collectors.toList());
        result.groups = groups;
        for (Group group : groups) {
            result.userCountByGroupId.put(group.getId(),
                    groupUserRepository.findByGroupId(group.getId()).size());
        }

        return result;
    }
}
