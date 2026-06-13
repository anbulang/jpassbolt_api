package com.jpassbolt.api.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Date;
import java.util.function.Function;

/**
 * JWT service signing access tokens with RS256, mirroring the PHP
 * {@code JwtTokenCreateService} / {@code JwksGetService} of the official
 * JwtAuthentication plugin.
 *
 * <p>
 * Claims follow the official plugin exactly: {@code iss} = full base url
 * (PHP {@code Router::url('/', true)}), {@code sub} = the user UUID (NOT the
 * username — the plugin resolves users by sub), {@code exp} = now + expiry.
 * The signing private key (PKCS#8 PEM, the equivalent of PHP
 * {@code config/jwt/jwt.key}) and the verification public key (X.509/SPKI
 * PEM, {@code config/jwt/jwt.pem}) are loaded at startup with plain JCE.
 * The public key is served by {@code GET /auth/jwt/jwks.json} and
 * {@code GET /auth/jwt/rsa.json} so clients can verify access tokens.
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JwtService {

    private final ResourceLoader resourceLoader;

    /** Access token validity in milliseconds (PHP access_token.expiry '5 minutes' by default; kept at the project's existing value). */
    @Value("${jpassbolt.jwt.expiration}")
    private long jwtExpiration;

    /**
     * PKCS#8 PEM private key location. No key is bundled with the repository:
     * production MUST inject a deployment-specific pair (fail-fast below).
     */
    @Value("${jpassbolt.jwt.private-key-location:}")
    private String privateKeyLocation;

    /** X.509/SPKI PEM public key matching the private key. */
    @Value("${jpassbolt.jwt.public-key-location:}")
    private String publicKeyLocation;

    /**
     * Dev/test escape hatch (local profile + tests only): when no external
     * key pair is configured, generate an ephemeral in-memory RS256 pair
     * instead of refusing to start. NEVER enable in production — tokens do
     * not survive a restart and the key is never persisted.
     */
    @Value("${jpassbolt.jwt.allow-ephemeral-dev-key:false}")
    private boolean allowEphemeralDevKey;

    /** iss claim — PHP Router::url('/', true). Reuses the settings cluster's full-base-url. */
    @Value("${jpassbolt.settings.full-base-url:http://localhost:8080}")
    private String fullBaseUrl;

    private RSAPrivateKey privateKey;
    private RSAPublicKey publicKey;
    private String publicKeyPem;

    @PostConstruct
    public void loadKeys() {
        boolean externalPairConfigured = privateKeyLocation != null && !privateKeyLocation.isBlank()
                && publicKeyLocation != null && !publicKeyLocation.isBlank();

        if (!externalPairConfigured) {
            if (!allowEphemeralDevKey) {
                // Fail-fast: never fall back to a bundled/predictable key.
                // Anyone holding a known private key could forge an access
                // token for ANY user (sub = user UUID), i.e. full account
                // takeover — refusing to start is the only safe behaviour.
                throw new IllegalStateException(
                        "No JWT RS256 key pair configured. Set jpassbolt.jwt.private-key-location and "
                                + "jpassbolt.jwt.public-key-location (e.g. via JPASSBOLT_JWT_PRIVATE_KEY_LOCATION / "
                                + "JPASSBOLT_JWT_PUBLIC_KEY_LOCATION) to a deployment-specific key pair. "
                                + "Bundled development keys are no longer shipped; ephemeral dev keys are only "
                                + "allowed with jpassbolt.jwt.allow-ephemeral-dev-key=true (local/test profiles).");
            }
            generateEphemeralDevKeyPair();
            return;
        }

        try {
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");

            String privatePem = readResource(privateKeyLocation);
            privateKey = (RSAPrivateKey) keyFactory.generatePrivate(
                    new PKCS8EncodedKeySpec(decodePem(privatePem)));

            publicKeyPem = readResource(publicKeyLocation);
            publicKey = (RSAPublicKey) keyFactory.generatePublic(
                    new X509EncodedKeySpec(decodePem(publicKeyPem)));

            log.info("JWT RS256 key pair loaded ({} bits)", publicKey.getModulus().bitLength());
        } catch (Exception e) {
            // Mirrors the PHP InvalidJwtKeyPairException + Log::alert path
            log.error("Failed to load the JWT RSA key pair", e);
            throw new IllegalStateException("Failed to load the JWT RSA key pair", e);
        }
    }

    /**
     * Dev/test only: in-memory RS256 pair, never persisted. The PEM form of
     * the public key is still derived so the JWKS/rsa endpoints keep working.
     */
    private void generateEphemeralDevKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            privateKey = (RSAPrivateKey) pair.getPrivate();
            publicKey = (RSAPublicKey) pair.getPublic();
            publicKeyPem = "-----BEGIN PUBLIC KEY-----\n"
                    + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                            .encodeToString(publicKey.getEncoded())
                    + "\n-----END PUBLIC KEY-----\n";
            log.warn("JWT RS256: using an EPHEMERAL in-memory dev key pair "
                    + "(allow-ephemeral-dev-key=true). Do NOT use in production.");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate the ephemeral JWT RSA key pair", e);
        }
    }

    /**
     * Generate an RS256 access token for the given user.
     *
     * @param userId the user UUID — becomes the {@code sub} claim
     * @return the compact JWT string
     */
    public String generateToken(String userId) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .setIssuer(fullBaseUrl)
                .setSubject(userId)
                .setIssuedAt(new Date(now))
                .setExpiration(new Date(now + jwtExpiration))
                .signWith(privateKey, SignatureAlgorithm.RS256)
                .compact();
    }

    /**
     * Extract the subject (user UUID) from a token. The RS256 signature is
     * verified as part of parsing — an invalid signature throws.
     */
    public String extractSubject(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    /**
     * Validate signature and expiry. The subject is the user UUID; the
     * authentication filter resolves it to a user record.
     */
    public boolean isTokenValid(String token) {
        try {
            return !isTokenExpired(token);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * The RSA verification public key, for the JWKS endpoint (n/e components).
     */
    public RSAPublicKey getRsaPublicKey() {
        return publicKey;
    }

    /**
     * The verification public key as X.509/SPKI PEM, served by
     * {@code GET /auth/jwt/rsa.json} (keydata).
     */
    public String getPublicKeyPem() {
        return publicKeyPem;
    }

    private boolean isTokenExpired(String token) {
        return extractExpiration(token).before(new Date());
    }

    private Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    private Claims extractAllClaims(String token) {
        return Jwts
                .parserBuilder()
                .setSigningKey(publicKey)
                // iss must match this deployment (defence in depth: a token
                // signed for another instance/domain is rejected even if the
                // key pair were ever shared between deployments).
                .requireIssuer(fullBaseUrl)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    private String readResource(String location) throws Exception {
        try (InputStream in = resourceLoader.getResource(location).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Strip the PEM armor ("-----BEGIN/END ...-----") and Base64-decode the
     * body. Pure JCE — no extra dependency needed for PKCS#8/SPKI PEM files.
     */
    private static byte[] decodePem(String pem) {
        String base64 = pem
                .replaceAll("-----BEGIN [A-Z ]+-----", "")
                .replaceAll("-----END [A-Z ]+-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(base64);
    }
}
