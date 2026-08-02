package com.jpassbolt.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "users")
public class User extends BaseEntity {

    @Column(name = "role_id", nullable = false, length = 36, columnDefinition = "char(36)")
    private String roleId;

    /**
     * No unique=true: the official Passbolt schema has NO unique key on
     * username (only KEY deleted). Uniqueness is a business rule scoped to
     * deleted=false users (re-inviting the username of a soft-deleted user
     * must succeed) and is enforced by UserService
     * (existsByUsernameAndDeletedFalse + lowercase normalization).
     */
    @Column(name = "username", nullable = false, length = 255)
    private String username;

    @Column(name = "active", nullable = false)
    private Boolean active;

    @Column(name = "deleted", nullable = false)
    private Boolean deleted = false;

    @Column(name = "disabled")
    private LocalDateTime disabled;

    /**
     * PHP {@code User::isDisabled()} — {@code disabled} is a TIMESTAMP, not a
     * flag, so a future-dated value still counts as active (the suspension has
     * not taken effect yet). Mirrors the SQL predicate
     * {@code disabled IS NULL OR disabled > now()} in
     * {@code UsersFindersTrait::findNotDisabled}, negated.
     *
     * <p>
     * <b>The clock must be read in UTC.</b> {@link LocalDateTime} carries no
     * zone, and every writer of this column stores a UTC wall clock — the same
     * convention {@link BaseEntity} uses for created/modified, and what
     * {@code UserService.parseDateTime} produces when it keeps the fields of the
     * client's ISO-8601 {@code "…Z"} value. Comparing against a system-zone
     * {@code now()} is off by the host's UTC offset, and the failure is
     * one-sided per host: west of UTC a just-disabled user reads as
     * "disabled in the future" and keeps receiving notification mail, while
     * east of UTC a suspension scheduled within the offset reads as already
     * elapsed. A UTC+N development box masks the first direction entirely.
     * </p>
     *
     * <p>
     * Centralised here because the predicate had drifted across the codebase in
     * TWO different broken shapes. Three sites (RecipientResolver, UserService,
     * RecoverService) each kept their own {@code now()} call, all reading the
     * host zone. Eight more — the authentication, refresh, recovery and setup
     * gates in {@code AuthService}, {@code JwtAuthService},
     * {@code JwtAuthenticationFilter}, {@code RecoverService} and
     * {@code SetupService} — tested {@code disabled != null}, i.e. treated the
     * column as a boolean flag, so a suspension scheduled for next week locked
     * the account out immediately. PHP gates every one of those on
     * {@code User::isDisabled()} (GpgAuthenticator, GpgJwtAuthenticator,
     * RefreshTokenAbstractService, SessionAuthPreventDeletedOrDisabledUsers
     * Middleware, UserRecoverService, UserGetService).
     * </p>
     *
     * <p>
     * Not to be confused with the NULL → non-NULL <em>transition</em> check in
     * {@code UserService.isBeingDisabled}: that one fires the notification when
     * the column is first set, future-dated or not, and is correct as written.
     * </p>
     */
    public boolean isDisabledNow() {
        return disabled != null && !disabled.isAfter(LocalDateTime.now(ZoneOffset.UTC));
    }
}
