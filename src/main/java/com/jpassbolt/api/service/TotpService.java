package com.jpassbolt.api.service;

import org.bouncycastle.util.encoders.Base32;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pure JCE/Bouncy Castle implementation of RFC 6238 (TOTP) on top of
 * RFC 4226 (HOTP). No external OTP library is used: HMAC comes from
 * {@code javax.crypto.Mac} and Base32 from
 * {@code org.bouncycastle.util.encoders.Base32} (bcprov 1.70).
 *
 * <p>
 * Behavior mirrors the PHP OTPHP library as used by Passbolt
 * ({@code MfaOtpFactory} / {@code TotpVerifyForm}):
 * </p>
 * <ul>
 * <li>secrets are 32 random bytes, Base32-encoded without '=' padding</li>
 * <li>verification is strict on the current 30s window — OTPHP's
 * {@code verify()} default has no leeway, so no ±1 window drift tolerance is
 * applied here either (do not "fix" this: it would change the security
 * properties relative to the official implementation)</li>
 * </ul>
 */
@Service
public class TotpService {

    static final int DEFAULT_DIGITS = 6;
    static final int DEFAULT_PERIOD_SECONDS = 30;
    static final String DEFAULT_ALGORITHM = "SHA1";

    /** PHP MfaOtpFactory::PASSBOLT_PLUGINS_MFA_TOTP_DEFAULT_SECRET_LENGTH. */
    private static final int SECRET_LENGTH_BYTES = 32;

    private static final String OTPAUTH_TOTP_PREFIX = "otpauth://totp/";

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Parsed representation of an {@code otpauth://totp/...} provisioning URI.
     */
    public record ParsedTotp(String secret, int digits, int period, String algorithm, String issuer, String label) {
    }

    /**
     * Generate a new random TOTP secret: 32 random bytes, Base32-encoded with
     * the '=' padding stripped (PHP MfaOtpFactory::generateTOTP).
     */
    public String generateSecret() {
        byte[] bytes = new byte[SECRET_LENGTH_BYTES];
        RANDOM.nextBytes(bytes);
        return Base32.toBase32String(bytes).replace("=", "");
    }

    /**
     * Build an {@code otpauth://totp/{issuer}:{label}?secret=..&issuer=..}
     * provisioning URI with default digits/period/algorithm (6/30/SHA1),
     * matching the URIs produced by OTPHP for Passbolt. Spaces are encoded
     * as {@code %20}, never '+'.
     */
    public String buildProvisioningUri(String issuer, String label, String secret) {
        String encodedIssuer = urlEncode(issuer);
        String encodedLabel = urlEncode(label);
        return OTPAUTH_TOTP_PREFIX + encodedIssuer + ":" + encodedLabel
                + "?secret=" + secret + "&issuer=" + encodedIssuer;
    }

    /**
     * Parse and validate a TOTP provisioning URI.
     *
     * @throws IllegalArgumentException if the scheme/type is not
     *                                  {@code otpauth://totp/}, the secret is
     *                                  missing or not valid Base32, or
     *                                  digits/period/algorithm are invalid
     */
    public ParsedTotp parseProvisioningUri(String uri) {
        if (uri == null || !uri.startsWith(OTPAUTH_TOTP_PREFIX)) {
            throw new IllegalArgumentException("Not a valid otpauth TOTP provisioning uri.");
        }
        String rest = uri.substring(OTPAUTH_TOTP_PREFIX.length());
        int queryStart = rest.indexOf('?');
        if (queryStart < 0) {
            throw new IllegalArgumentException("The provisioning uri has no query parameters.");
        }
        Map<String, String> params = parseQuery(rest.substring(queryStart + 1));

        String secret = params.get("secret");
        if (secret == null || secret.isEmpty()) {
            throw new IllegalArgumentException("The provisioning uri has no secret.");
        }
        decodeBase32(secret); // validates the Base32 encoding

        int digits = parsePositiveInt(params.getOrDefault("digits", String.valueOf(DEFAULT_DIGITS)), "digits");
        int period = parsePositiveInt(params.getOrDefault("period", String.valueOf(DEFAULT_PERIOD_SECONDS)), "period");
        String algorithm = params.getOrDefault("algorithm", DEFAULT_ALGORITHM).toUpperCase();
        hmacAlgorithm(algorithm); // validates the algorithm name

        // Split issuer:label on the RAW (still percent-encoded) path segment
        // before decoding — a ':' inside the issuer or the label is encoded
        // as %3A, so the first literal ':' is always the separator.
        String rawLabel = rest.substring(0, queryStart);
        String issuer = params.get("issuer");
        String label;
        int colon = rawLabel.indexOf(':');
        if (colon >= 0) {
            String pathIssuer = urlDecode(rawLabel.substring(0, colon));
            if (issuer == null) {
                issuer = pathIssuer;
            }
            label = urlDecode(rawLabel.substring(colon + 1));
        } else {
            label = urlDecode(rawLabel);
        }
        return new ParsedTotp(secret, digits, period, algorithm, issuer, label);
    }

    /**
     * Verify a TOTP code against a provisioning URI, strictly on the current
     * time window (no leeway, like OTPHP's verify() default). Constant-time
     * comparison via {@link MessageDigest#isEqual}.
     *
     * @throws IllegalArgumentException if the provisioning URI is invalid
     */
    public boolean verifyCode(String provisioningUri, String code) {
        if (code == null || code.isEmpty()) {
            return false;
        }
        String expected = generateCurrentCode(provisioningUri);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                code.getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * Compute the code for the current time window of the given provisioning
     * URI. Also used by integration tests to obtain a currently-valid code
     * without mocking time.
     */
    public String generateCurrentCode(String provisioningUri) {
        ParsedTotp totp = parseProvisioningUri(provisioningUri);
        long counter = Instant.now().getEpochSecond() / totp.period();
        return generateHotp(decodeBase32(totp.secret()), counter, totp.digits(), totp.algorithm());
    }

    /**
     * RFC 4226 HOTP with dynamic truncation (§5.3). Package-visible so the
     * unit test can check the RFC 4226 appendix D vectors counter by counter.
     */
    String generateHotp(byte[] key, long counter, int digits, String algorithm) {
        try {
            String macAlgorithm = hmacAlgorithm(algorithm);
            Mac mac = Mac.getInstance(macAlgorithm);
            mac.init(new SecretKeySpec(key, macAlgorithm));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24)
                    | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8)
                    | (hash[offset + 3] & 0xff);
            // Use a long modulus: (int) Math.pow(10, digits) overflows for
            // digits > 9, yielding a wrong (or negative) modulus. The 31-bit
            // truncated binary always fits a long, so the result is exact for
            // any sane digit count (RFC 4226 uses 6-8).
            long otp = binary % (long) Math.pow(10, digits);
            return String.format("%0" + digits + "d", otp);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to compute the HOTP value.", e);
        }
    }

    /**
     * Decode an RFC 4648 Base32 string. Tolerates missing '=' padding and
     * lowercase input (Google Authenticator style secrets).
     *
     * @throws IllegalArgumentException if the input is not valid Base32
     */
    static byte[] decodeBase32(String secret) {
        String normalized = secret.trim().replace(" ", "").toUpperCase();
        if (normalized.isEmpty() || !normalized.matches("[A-Z2-7]+")) {
            throw new IllegalArgumentException("The secret is not valid Base32.");
        }
        int remainder = normalized.length() % 8;
        if (remainder != 0) {
            normalized = normalized + "=".repeat(8 - remainder);
        }
        try {
            byte[] decoded = Base32.decode(normalized);
            if (decoded.length == 0) {
                throw new IllegalArgumentException("The secret is empty.");
            }
            return decoded;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("The secret is not valid Base32.", e);
        }
    }

    private static String hmacAlgorithm(String algorithm) {
        switch (algorithm) {
            case "SHA1":
                return "HmacSHA1";
            case "SHA256":
                return "HmacSHA256";
            case "SHA512":
                return "HmacSHA512";
            default:
                throw new IllegalArgumentException("Unsupported TOTP algorithm: " + algorithm);
        }
    }

    private static int parsePositiveInt(String value, String name) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed <= 0) {
                throw new IllegalArgumentException("The " + name + " parameter must be positive.");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("The " + name + " parameter is not a number.", e);
        }
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> params = new LinkedHashMap<>();
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = eq >= 0 ? pair.substring(0, eq) : pair;
            String value = eq >= 0 ? urlDecode(pair.substring(eq + 1)) : "";
            params.put(key, value);
        }
        return params;
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String urlDecode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
