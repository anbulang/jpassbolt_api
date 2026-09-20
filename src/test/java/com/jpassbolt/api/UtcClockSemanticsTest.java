package com.jpassbolt.api;

import com.jpassbolt.api.model.Resource;
import com.jpassbolt.api.model.User;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the project's UTC clock convention.
 *
 * <p>
 * Every {@code datetime} column in this codebase holds a <b>UTC wall clock</b>:
 * {@code BaseEntity} stamps created/modified with
 * {@code LocalDateTime.now(ZoneOffset.UTC)}, {@code JacksonConfig}'s inbound
 * deserializer normalises every client-supplied timestamp to UTC, and its
 * outbound serializer re-labels the stored value {@code +00:00}. But
 * {@link LocalDateTime} carries no zone of its own, so nothing in the type
 * system stops a comparison against a system-zone {@code LocalDateTime.now()} —
 * which is silently wrong by the host's UTC offset.
 * </p>
 *
 * <p>
 * That mistake has now been made and fixed twice, in nine separate call sites
 * (recipient filtering, two more copies of the same disabled predicate, verify-
 * and refresh-token expiry, MFA token duration, metadata-key expiry validation
 * and its soft-delete stamp, resource expiry). It is invisible on a UTC host and
 * one-sided on every other one, so ordinary unit tests do not catch it — hence a
 * source-level guard rather than another behavioural test.
 * </p>
 */
class UtcClockSemanticsTest {

    /**
     * {@code java.time} clock reads that silently adopt the host's zone. NB
     * {@code new java.util.Date()} is deliberately absent: it is an absolute
     * instant (epoch millis) and carries no zone ambiguity, so the existing uses
     * in JwtService / GpgServiceImpl are correct.
     */
    private static final Pattern BARE_LOCAL_CLOCK =
            Pattern.compile("\\b(LocalDateTime|LocalDate|LocalTime)\\.now\\(\\s*\\)");

    private static final Path MAIN_SOURCES = Path.of("src", "main", "java");

    @Test
    void mainSourcesNeverReadTheClockInTheHostZone() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    Matcher m = BARE_LOCAL_CLOCK.matcher(lines.get(i));
                    if (m.find()) {
                        offenders.add(file + ":" + (i + 1) + " → " + lines.get(i).trim());
                    }
                }
            }
        }
        assertThat(offenders)
                .as("Read the clock as LocalDateTime.now(ZoneOffset.UTC): every datetime column "
                        + "in this schema is a UTC wall clock (BaseEntity + JacksonConfig), so a "
                        + "host-zone now() is wrong by the host's UTC offset — invisible on a UTC "
                        + "box and one-sided everywhere else")
                .isEmpty();
    }

    // ------------------------------------------------------------------
    // The two predicates that read the clock directly. Each is asserted in
    // BOTH directions because the bug is one-sided per host: east of UTC a
    // host-zone now() runs ahead (the "not yet" case misfires), west of UTC it
    // runs behind (the "already" case misfires). Only one of the two pairs can
    // actually fail on any given machine — together they pin the semantic
    // wherever the suite runs.
    // ------------------------------------------------------------------

    @Test
    void userIsDisabledNow_trueOnlyOnceTheUtcInstantHasPassed() {
        User justDisabled = new User();
        justDisabled.setDisabled(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        assertThat(justDisabled.isDisabledNow()).isTrue();

        User disabledLaterToday = new User();
        disabledLaterToday.setDisabled(LocalDateTime.now(ZoneOffset.UTC).plusHours(1));
        assertThat(disabledLaterToday.isDisabledNow())
                .as("a suspension scheduled within the host's UTC offset must not read as elapsed")
                .isFalse();

        assertThat(new User().isDisabledNow())
                .as("null disabled = never suspended")
                .isFalse();
    }

    @Test
    void resourceIsExpired_trueOnlyOnceTheUtcInstantHasPassed() {
        Resource justExpired = new Resource();
        justExpired.setExpired(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        assertThat(justExpired.isExpired()).isTrue();

        Resource expiringLaterToday = new Resource();
        expiringLaterToday.setExpired(LocalDateTime.now(ZoneOffset.UTC).plusHours(1));
        assertThat(expiringLaterToday.isExpired())
                .as("an expiry within the host's UTC offset must not read as already past")
                .isFalse();

        assertThat(new Resource().isExpired())
                .as("null expired = never expires")
                .isFalse();
    }
}
