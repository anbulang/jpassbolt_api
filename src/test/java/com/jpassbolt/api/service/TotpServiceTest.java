package com.jpassbolt.api.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit tests for {@link TotpService} (no Spring context): RFC 4226
 * appendix D HOTP vectors, RFC 6238 appendix B TOTP vectors, provisioning
 * URI build/parse round-trips and strict single-window verification.
 */
class TotpServiceTest {

    private final TotpService totpService = new TotpService();

    /** RFC 4226 / RFC 6238 SHA1 shared secret (ASCII). */
    private static final byte[] RFC_SHA1_KEY = "12345678901234567890"
            .getBytes(StandardCharsets.US_ASCII);

    // ------------------------------------------------------------------
    // RFC vectors
    // ------------------------------------------------------------------

    @Test
    void generateHotp_Rfc4226AppendixDVectors() {
        String[] expected = {
                "755224", "287082", "359152", "969429", "338314",
                "254676", "287922", "162583", "399871", "520489" };
        for (int counter = 0; counter < expected.length; counter++) {
            assertThat(totpService.generateHotp(RFC_SHA1_KEY, counter, 6, "SHA1"))
                    .as("HOTP counter %d", counter)
                    .isEqualTo(expected[counter]);
        }
    }

    @Test
    void generateHotp_Rfc6238Sha1Vectors() {
        long[] times = { 59L, 1111111109L, 1111111111L, 1234567890L, 2000000000L, 20000000000L };
        String[] expected = { "94287082", "07081804", "14050471", "89005924", "69279037", "65353130" };
        for (int i = 0; i < times.length; i++) {
            assertThat(totpService.generateHotp(RFC_SHA1_KEY, times[i] / 30, 8, "SHA1"))
                    .as("TOTP T=%d", times[i])
                    .isEqualTo(expected[i]);
        }
    }

    @Test
    void generateHotp_Rfc6238Sha256Vector() {
        byte[] key = "12345678901234567890123456789012".getBytes(StandardCharsets.US_ASCII);
        assertThat(totpService.generateHotp(key, 59L / 30, 8, "SHA256")).isEqualTo("46119246");
    }

    @Test
    void generateHotp_Rfc6238Sha512Vector() {
        byte[] key = "1234567890123456789012345678901234567890123456789012345678901234"
                .getBytes(StandardCharsets.US_ASCII);
        assertThat(totpService.generateHotp(key, 59L / 30, 8, "SHA512")).isEqualTo("90693936");
    }

    // ------------------------------------------------------------------
    // Secret generation
    // ------------------------------------------------------------------

    @Test
    void generateSecret_Base32WithoutPadding_Decodes32Bytes() {
        String secret = totpService.generateSecret();
        assertThat(secret).matches("[A-Z2-7]+");
        assertThat(secret).doesNotContain("=");
        // 32 bytes -> 52 unpadded Base32 characters
        assertThat(secret).hasSize(52);
        assertThat(TotpService.decodeBase32(secret)).hasSize(32);
    }

    @Test
    void decodeBase32_ToleratesLowercaseAndMissingPadding() {
        // "MZXW6YTB" decodes to "fooba"; lowercase + stripped padding variant
        assertThat(TotpService.decodeBase32("mzxw6ytb"))
                .isEqualTo("fooba".getBytes(StandardCharsets.US_ASCII));
        assertThat(TotpService.decodeBase32("MZXW6"))
                .isEqualTo("foo".getBytes(StandardCharsets.US_ASCII));
    }

    // ------------------------------------------------------------------
    // Provisioning URI round-trips
    // ------------------------------------------------------------------

    @Test
    void provisioningUri_RoundTrip_Defaults() {
        String secret = totpService.generateSecret();
        String uri = totpService.buildProvisioningUri("www.passbolt.test", "ada@passbolt.com", secret);

        TotpService.ParsedTotp parsed = totpService.parseProvisioningUri(uri);
        assertThat(parsed.secret()).isEqualTo(secret);
        assertThat(parsed.digits()).isEqualTo(6);
        assertThat(parsed.period()).isEqualTo(30);
        assertThat(parsed.algorithm()).isEqualTo("SHA1");
        assertThat(parsed.issuer()).isEqualTo("www.passbolt.test");
        assertThat(parsed.label()).isEqualTo("ada@passbolt.com");
    }

    @Test
    void provisioningUri_RoundTrip_IssuerWithSpacesAndColon() {
        String secret = totpService.generateSecret();

        String spaced = totpService.buildProvisioningUri("Acme Corp", "ada@passbolt.com", secret);
        // spaces must be %20, never '+'
        assertThat(spaced).doesNotContain("+");
        TotpService.ParsedTotp parsedSpaced = totpService.parseProvisioningUri(spaced);
        assertThat(parsedSpaced.issuer()).isEqualTo("Acme Corp");
        assertThat(parsedSpaced.label()).isEqualTo("ada@passbolt.com");

        String colon = totpService.buildProvisioningUri("acme:prod", "ada@passbolt.com", secret);
        TotpService.ParsedTotp parsedColon = totpService.parseProvisioningUri(colon);
        assertThat(parsedColon.issuer()).isEqualTo("acme:prod");
        assertThat(parsedColon.label()).isEqualTo("ada@passbolt.com");
    }

    @Test
    void parseProvisioningUri_ExplicitParametersOverrideDefaults() {
        TotpService.ParsedTotp parsed = totpService.parseProvisioningUri(
                "otpauth://totp/issuer:label?secret=MZXW6YTB&digits=8&period=60&algorithm=sha256&issuer=issuer");
        assertThat(parsed.digits()).isEqualTo(8);
        assertThat(parsed.period()).isEqualTo(60);
        assertThat(parsed.algorithm()).isEqualTo("SHA256");
    }

    // ------------------------------------------------------------------
    // Invalid provisioning URIs
    // ------------------------------------------------------------------

    @Test
    void parseProvisioningUri_RejectsInvalidInput() {
        String[] invalid = {
                null,
                "",
                "http://example.com/?secret=MZXW6YTB",
                "otpauth://hotp/issuer:label?secret=MZXW6YTB",
                "otpauth://totp/issuer:label", // no query string
                "otpauth://totp/issuer:label?issuer=foo", // no secret
                "otpauth://totp/issuer:label?secret=", // empty secret
                "otpauth://totp/issuer:label?secret=ABC18", // '1'/'8' not Base32
                "otpauth://totp/issuer:label?secret=MZXW6YTB&digits=0",
                "otpauth://totp/issuer:label?secret=MZXW6YTB&digits=abc",
                "otpauth://totp/issuer:label?secret=MZXW6YTB&period=-1",
                "otpauth://totp/issuer:label?secret=MZXW6YTB&algorithm=MD5",
        };
        for (String uri : invalid) {
            assertThatThrownBy(() -> totpService.parseProvisioningUri(uri))
                    .as("uri: %s", uri)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ------------------------------------------------------------------
    // Verification (strict current window, no leeway)
    // ------------------------------------------------------------------

    @Test
    void verifyCode_AcceptsCurrentWindowCode() {
        String uri = totpService.buildProvisioningUri("issuer", "label", totpService.generateSecret());
        // Retry once in case the 30s window rolls between generate and verify
        String code = totpService.generateCurrentCode(uri);
        boolean valid = totpService.verifyCode(uri, code);
        if (!valid) {
            code = totpService.generateCurrentCode(uri);
            valid = totpService.verifyCode(uri, code);
        }
        assertThat(valid).isTrue();
    }

    @Test
    void verifyCode_RejectsWrongEmptyAndNullCodes() {
        String uri = totpService.buildProvisioningUri("issuer", "label", totpService.generateSecret());
        String current = totpService.generateCurrentCode(uri);
        String wrong = current.equals("111111") ? "222222" : "111111";

        assertThat(totpService.verifyCode(uri, wrong)).isFalse();
        assertThat(totpService.verifyCode(uri, "")).isFalse();
        assertThat(totpService.verifyCode(uri, null)).isFalse();
    }

    @Test
    void verifyCode_RejectsPreviousWindowCode_NoLeeway() {
        String secret = totpService.generateSecret();
        String uri = totpService.buildProvisioningUri("issuer", "label", secret);

        long counter = Instant.now().getEpochSecond() / 30;
        String previousWindowCode = totpService.generateHotp(
                TotpService.decodeBase32(secret), counter - 1, 6, "SHA1");
        String currentCode = totpService.generateHotp(
                TotpService.decodeBase32(secret), counter, 6, "SHA1");

        // 1e-6 chance both windows yield the same digits — skip then.
        if (!previousWindowCode.equals(currentCode)) {
            assertThat(totpService.verifyCode(uri, previousWindowCode)).isFalse();
        }
    }

    @Test
    void verifyCode_InvalidUri_Throws() {
        assertThatThrownBy(() -> totpService.verifyCode("otpauth://hotp/x?secret=MZXW6YTB", "123456"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
