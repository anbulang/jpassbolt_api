package com.jpassbolt.api.service.email;

import com.jpassbolt.api.model.AccountSetting;
import com.jpassbolt.api.model.Profile;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.AccountSettingRepository;
import com.jpassbolt.api.repository.GroupUserRepository;
import com.jpassbolt.api.repository.ProfileRepository;
import com.jpassbolt.api.repository.UserRepository;
import com.jpassbolt.api.service.AccountLocaleService;
import com.jpassbolt.api.service.PermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Turns a set of user ids (or a domain selector such as "everyone with access to
 * a resource") into mailable {@link Recipient}s, aggregating the three sources of
 * a person's identity: the {@code users} row (email == username), the
 * {@code profiles} row (name) and the effective locale
 * (account_settings → org → default).
 *
 * <p>Read-only. Soft-deleted and disabled users are dropped here so callers
 * (redactors) never have to re-check. Crucially, <em>inactive</em> users are kept
 * — a setup invite / account-recovery mail goes precisely to a not-yet-active
 * user, so filtering on {@code active} would silently swallow those notifications.
 * Excluding the actor (the person who caused the event) is the caller's concern:
 * they pass the actor id and filter it out, keeping repository queries free of a
 * per-call actor parameter.</p>
 */
@Service
@RequiredArgsConstructor
public class RecipientResolver {

    private final PermissionService permissionService;
    private final GroupUserRepository groupUserRepository;
    private final UserRepository userRepository;
    private final ProfileRepository profileRepository;
    private final AccountSettingRepository accountSettingRepository;
    private final AccountLocaleService accountLocaleService;

    /** Everyone (users + expanded group members) with any permission on a resource. */
    @Transactional(readOnly = true)
    public Set<Recipient> resolveUsersWithAccessToResource(String resourceId) {
        return resolveUsers(permissionService.getUsersIdsHavingAccessTo(resourceId));
    }

    /** Everyone (users + expanded group members) with any permission on a folder. */
    @Transactional(readOnly = true)
    public Set<Recipient> resolveUsersWithAccessToFolder(String folderId) {
        return resolveUsers(permissionService.getUsersIdsHavingAccessToFolder(folderId));
    }

    /** Active (non-soft-deleted) members of a group. */
    @Transactional(readOnly = true)
    public Set<Recipient> resolveGroupMembers(String groupId) {
        return resolveUsers(groupUserRepository.findActiveMemberUserIds(groupId));
    }

    /** All active, non-deleted administrators (admin-targeted notifications). */
    @Transactional(readOnly = true)
    public Set<Recipient> resolveAllAdmins() {
        return resolveUsers(userRepository.findActiveAdmins().stream().map(User::getId).toList());
    }

    /** A single recipient by id (empty if deleted/disabled/absent). */
    @Transactional(readOnly = true)
    public Optional<Recipient> resolveUser(String userId) {
        return resolveUsers(List.of(userId)).stream().findFirst();
    }

    /**
     * Core fan-in: dedupe ids, drop deleted/disabled users, then batch-load
     * profiles and locales (no N+1) to build the recipient view.
     */
    @Transactional(readOnly = true)
    public Set<Recipient> resolveUsers(Collection<String> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Set.of();
        }
        Set<String> ids = new LinkedHashSet<>(userIds);

        List<User> users = userRepository.findAllById(ids).stream()
                .filter(u -> !Boolean.TRUE.equals(u.getDeleted()))
                // `disabled` is a timestamp, not a flag: a future-dated value means
                // the user is still active until then, so only a past/now disable
                // excludes them (PHP UsersFindersTrait::findNotDisabled —
                // "disabled IS NULL OR disabled > now()").
                .filter(u -> u.getDisabled() == null || u.getDisabled().isAfter(LocalDateTime.now()))
                .toList();
        if (users.isEmpty()) {
            return Set.of();
        }

        Set<String> liveIds = users.stream().map(User::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Map<String, Profile> profiles = profileRepository.findByUserIdIn(liveIds).stream()
                .collect(Collectors.toMap(Profile::getUserId, Function.identity(), (a, b) -> a));

        Map<String, Locale> locales = resolveLocales(liveIds);

        Set<Recipient> recipients = new LinkedHashSet<>();
        for (User user : users) {
            Profile profile = profiles.get(user.getId());
            recipients.add(new Recipient(
                    user.getId(),
                    user.getUsername(),
                    locales.getOrDefault(user.getId(), defaultLocale()),
                    profile == null ? null : profile.getFirstName(),
                    profile == null ? null : profile.getLastName()));
        }
        return recipients;
    }

    /**
     * Batch-resolve each user's locale: their {@code account_settings.locale} if
     * set, else the organization locale, else the system default — the same
     * resolution chain as {@link AccountLocaleService#getUserLocale(String)} but
     * in a single query for the whole set.
     */
    private Map<String, Locale> resolveLocales(Collection<String> userIds) {
        Map<String, String> codes = accountSettingRepository
                .findByUserIdInAndProperty(userIds, AccountLocaleService.LOCALE_PROPERTY).stream()
                .filter(s -> s.getValue() != null && !s.getValue().isBlank())
                .collect(Collectors.toMap(AccountSetting::getUserId, AccountSetting::getValue, (a, b) -> a));

        String orgCode = accountLocaleService.getOrganizationLocale();
        Map<String, Locale> result = new LinkedHashMap<>();
        for (String userId : userIds) {
            String code = codes.getOrDefault(userId, orgCode);
            result.put(userId, accountLocaleService.toJavaLocale(code));
        }
        return result;
    }

    private Locale defaultLocale() {
        return accountLocaleService.toJavaLocale(AccountLocaleService.DEFAULT_LOCALE);
    }
}
