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

    /** PKCS#8 PEM private key, defaults to the bundled dev key (override in production). */
    @Value("${jpassbolt.jwt.private-key-location:classpath:jwt/jwt.key}")
    private String privateKeyLocation;

    /** X.509/SPKI PEM public key matching the private key. */
    @Value("${jpassbolt.jwt.public-key-location:classpath:jwt/jwt.pem}")
    private String publicKeyLocation;

    /** iss claim — PHP Router::url('/', true). Reuses the settings cluster's full-base-url. */
    @Value("${jpassbolt.settings.full-base-url:http://localhost:8080}")
    private String fullBaseUrl;

    private RSAPrivateKey privateKey;
    private RSAPublicKey publicKey;
    private String publicKeyPem;

    @PostConstruct
    public void loadKeys() {
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
