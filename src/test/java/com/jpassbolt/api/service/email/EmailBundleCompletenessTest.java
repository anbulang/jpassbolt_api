package com.jpassbolt.api.service.email;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the message-bundle contract that only shows up at send time.
 *
 * <p>
 * {@code MailMessageConfig} sets {@code fallbackToSystemLocale(false)} over
 * basename {@code messages/email}, which makes the BASE bundle
 * ({@code email.properties}) the ultimate fallback for every locale without a
 * dedicated file. A key present only in {@code email_en} / {@code email_zh}
 * therefore throws {@link org.springframework.context.NoSuchMessageException}
 * — not at startup, not in any redactor unit test that happens to use en or zh,
 * but only when a user whose locale is de / fr / ja triggers that particular
 * notification. Seven {@code email.mfa.reset.*} keys had been sitting in exactly
 * that state.
 * </p>
 *
 * <p>
 * The reverse direction matters too: a key referenced by a Thymeleaf template or
 * a redactor but defined nowhere fails the same way, and an orphaned key is dead
 * copy that drifts out of sync with the reference wording.
 * </p>
 */
class EmailBundleCompletenessTest {

    private static final Path MESSAGES = Path.of("src", "main", "resources", "messages");
    private static final Path TEMPLATES = Path.of("src", "main", "resources", "templates", "email");
    private static final Path JAVA = Path.of("src", "main", "java");

    private static final Pattern TEMPLATE_KEY = Pattern.compile("#\\{([a-zA-Z0-9_.]+)");
    private static final Pattern JAVA_KEY = Pattern.compile("\"(email\\.[a-zA-Z0-9_.]+)\"");

    private static Set<String> keysOf(String bundle) throws IOException {
        Set<String> keys = new LinkedHashSet<>();
        for (String line : Files.readAllLines(MESSAGES.resolve(bundle), StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains("=")) {
                continue;
            }
            keys.add(trimmed.substring(0, trimmed.indexOf('=')).trim());
        }
        return keys;
    }

    private static Set<String> scan(Path root, String suffix, Pattern pattern) throws IOException {
        Set<String> found = new LinkedHashSet<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(suffix)).toList()) {
                Matcher m = pattern.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (m.find()) {
                    found.add(m.group(1));
                }
            }
        }
        return found;
    }

    @Test
    void everyLocaleBundleHasExactlyTheBaseBundlesKeys() throws IOException {
        Set<String> base = keysOf("email.properties");
        assertThat(base).as("the base bundle must not be empty — did the path move?").isNotEmpty();

        for (String bundle : List.of("email_en.properties", "email_zh.properties")) {
            Set<String> locale = keysOf(bundle);
            assertThat(new TreeSet<>(subtract(locale, base)))
                    .as("%s defines keys the BASE bundle lacks; the base file is the ultimate "
                            + "fallback (fallbackToSystemLocale=false), so any locale without its "
                            + "own bundle would throw NoSuchMessageException at send time", bundle)
                    .isEmpty();
            assertThat(new TreeSet<>(subtract(base, locale)))
                    .as("%s is missing keys the base bundle defines — that locale would silently "
                            + "fall back to English for them", bundle)
                    .isEmpty();
        }
    }

    @Test
    void everyReferencedKeyIsDefinedAndEveryDefinedKeyIsReferenced() throws IOException {
        Set<String> base = keysOf("email.properties");
        Set<String> referenced = new LinkedHashSet<>(scan(TEMPLATES, ".html", TEMPLATE_KEY));
        referenced.addAll(scan(JAVA, ".java", JAVA_KEY));
        assertThat(referenced).as("found no key references — did the paths move?").isNotEmpty();

        assertThat(new TreeSet<>(subtract(referenced, base)))
                .as("referenced by a template or redactor but defined in no bundle — "
                        + "NoSuchMessageException at send time")
                .isEmpty();
        assertThat(new TreeSet<>(subtract(base, referenced)))
                .as("defined but referenced nowhere — dead copy that drifts out of sync "
                        + "with the PHP reference wording")
                .isEmpty();
    }

    private static List<String> subtract(Set<String> from, Set<String> remove) {
        List<String> out = new ArrayList<>(from);
        out.removeAll(remove);
        return out;
    }
}
