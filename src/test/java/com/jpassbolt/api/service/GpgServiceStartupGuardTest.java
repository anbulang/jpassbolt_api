package com.jpassbolt.api.service;

import com.jpassbolt.api.config.GpgProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Startup passphrase validation in {@link GpgServiceImpl#init()}.
 *
 * <p>
 * Regression lock for the four-round GpgAuth review: {@code init()} used to only
 * PARSE the server secret-key ring, so a wrong {@code JPASSBOLT_GPG_PASSPHRASE}
 * booted cleanly and failed every Stage 0 request instead — where the error was
 * indistinguishable from bad client ciphertext and got reported as a 400. The
 * fix extracts every server private key at startup so an operator misconfiguration
 * fails fast (loudly, at boot) and Stage 0's 400 stays attributable to the caller.
 * </p>
 *
 * <p>
 * Uses the committed {@code ada_private.asc} fixture (passphrase {@code password})
 * rather than the gitignored {@code server_private.asc}, so the test is portable.
 * </p>
 */
class GpgServiceStartupGuardTest {

    private GpgServiceImpl gpgServiceWith(String passphrase) {
        GpgProperties props = new GpgProperties();
        props.getServerKey().setPrivateLocation("classpath:gpg/ada_private.asc");
        props.getServerKey().setPublicLocation("classpath:gpg/ada_public.asc");
        props.getServerKey().setPassphrase(passphrase);
        return new GpgServiceImpl(props, new DefaultResourceLoader());
    }

    @Test
    void correctPassphrase_initSucceeds() {
        assertThatCode(() -> gpgServiceWith("password").init()).doesNotThrowAnyException();
    }

    @Test
    void wrongPassphrase_failsAtStartupNotLater() {
        // The whole point: a wrong passphrase must blow up init(), not sail
        // through startup and surface only on the first GpgAuth request.
        assertThatThrownBy(() -> gpgServiceWith("definitely-not-the-passphrase").init())
                .isInstanceOf(RuntimeException.class);
    }
}
