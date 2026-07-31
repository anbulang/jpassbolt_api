package com.jpassbolt.api.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the seed safety guard {@link DataInitializer#isEmbeddedH2}.
 *
 * <p>
 * The guard exists so the committed dev credentials (ada@passbolt.com admin,
 * passphrase "password") can only ever be seeded into a throwaway embedded H2.
 * The regression these tests lock: an earlier version matched a bare
 * {@code jdbc:h2:} prefix, which also accepts H2's NETWORKED modes
 * ({@code tcp:} / {@code ssl:}) — a remote H2 server that is just as real as
 * MySQL — re-opening the exact hole the guard closes.
 * </p>
 */
class DataInitializerGuardTest {

    @Test
    void embeddedInMemory_isAllowed() {
        assertThat(DataInitializer.isEmbeddedH2(
                "jdbc:h2:mem:jpassbolt;DB_CLOSE_DELAY=-1;MODE=MySQL", "H2")).isTrue();
    }

    @Test
    void persistentFile_isRejected() {
        // file: is persistent and its path could be a shared/mounted volume, so
        // it is not the guaranteed-ephemeral store the seed guarantee assumes.
        assertThat(DataInitializer.isEmbeddedH2("jdbc:h2:file:/var/data/dev", "H2")).isFalse();
    }

    @Test
    void networkedTcp_isRejected() {
        // The whole point: a remote H2 over TCP must NOT be treated as embedded.
        assertThat(DataInitializer.isEmbeddedH2("jdbc:h2:tcp://db.internal:9092/prod", "H2")).isFalse();
    }

    @Test
    void networkedSsl_isRejected() {
        assertThat(DataInitializer.isEmbeddedH2("jdbc:h2:ssl://db.internal:9092/prod", "H2")).isFalse();
    }

    @Test
    void mysql_isRejected() {
        assertThat(DataInitializer.isEmbeddedH2("jdbc:mysql://db.internal:3306/prod", "MySQL")).isFalse();
    }

    @Test
    void h2UrlButNonH2Product_isRejected() {
        // A spoofed URL cannot pass without the driver actually being H2.
        assertThat(DataInitializer.isEmbeddedH2("jdbc:h2:mem:x", "MySQL")).isFalse();
    }

    @Test
    void caseAndWhitespaceInsensitive() {
        assertThat(DataInitializer.isEmbeddedH2("  JDBC:H2:MEM:X  ", "h2")).isTrue();
    }

    @Test
    void nullsAreRejected() {
        assertThat(DataInitializer.isEmbeddedH2(null, "H2")).isFalse();
        assertThat(DataInitializer.isEmbeddedH2("jdbc:h2:mem:x", null)).isFalse();
    }

    // --- redactJdbcUrl: the reject-path log must never leak embedded credentials ---

    @Test
    void redact_stripsHostCredentialsAndQueryProps() {
        // A MySQL URL with an embedded password must not survive into logs.
        assertThat(DataInitializer.redactJdbcUrl(
                "jdbc:mysql://user:s3cr3t@db.internal:3306/prod?password=s3cr3t"))
                .isEqualTo("jdbc:mysql:***")
                .doesNotContain("s3cr3t");
    }

    @Test
    void redact_keepsOnlyScheme() {
        assertThat(DataInitializer.redactJdbcUrl("jdbc:h2:tcp://host:9092/prod")).isEqualTo("jdbc:h2:***");
    }

    @Test
    void redact_handlesNullAndGarbage() {
        assertThat(DataInitializer.redactJdbcUrl(null)).isEqualTo("null");
        assertThat(DataInitializer.redactJdbcUrl("not-a-jdbc-url")).isEqualTo("***");
    }
}
