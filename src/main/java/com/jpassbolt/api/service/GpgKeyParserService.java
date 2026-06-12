package com.jpassbolt.api.service;

import lombok.Builder;
import lombok.Data;
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyRing;
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.Security;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Iterator;

/**
 * Pure Bouncy Castle OpenPGP public key parser/validator used by the account
 * setup flow (project iron rule #1: GPG operations only through Bouncy
 * Castle, never the system gpg binary).
 *
 * <p>
 * Deliberately a new component: {@link GpgServiceImpl} handles the SERVER
 * key pair (encrypt/decrypt for GpgAuth) and must not be touched; this class
 * only inspects USER public keys uploaded during setup. The hex conversion
 * replicates GpgServiceImpl#bytesToHex (copied, not shared, to keep the
 * existing class untouched).
 * </p>
 */
@Service
public class GpgKeyParserService {

    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    /**
     * Parse and validate an ASCII-armored OpenPGP public key.
     *
     * <p>
     * Checks (messages aligned with the PHP reference behaviour):
     * parseable single key block, not revoked, not expired, usable for
     * encryption (master key or at least one subkey).
     * </p>
     *
     * @param armoredKey ASCII-armored public key
     * @return extracted metadata
     * @throws IllegalArgumentException when the key is missing or invalid
     */
    public GpgKeyMetadata parse(String armoredKey) {
        if (armoredKey == null || armoredKey.isBlank()) {
            throw new IllegalArgumentException("An OpenPGP key must be provided.");
        }

        PGPPublicKeyRing keyRing;
        try (InputStream in = PGPUtil.getDecoderStream(
                new ByteArrayInputStream(armoredKey.getBytes(StandardCharsets.UTF_8)))) {
            PGPPublicKeyRingCollection collection = new PGPPublicKeyRingCollection(
                    in, new JcaKeyFingerprintCalculator());
            Iterator<PGPPublicKeyRing> rings = collection.getKeyRings();
            if (!rings.hasNext()) {
                throw new IllegalArgumentException("A valid OpenPGP key must be provided.");
            }
            // Take the first key ring, mirroring the existing GpgServiceImpl
            // behaviour (publicKeyRings.iterator().next()). Bouncy Castle's
            // PGPPublicKeyRingCollection can split a single logical public key
            // (master + subkeys) across several PGPPublicKeyRing instances, so
            // the presence of more than one ring does not mean the uploader
            // sent multiple distinct keys. Validation of the actual master key
            // below still rejects revoked/expired/non-encrypting keys.
            keyRing = rings.next();
        } catch (IOException | PGPException e) {
            throw new IllegalArgumentException("A valid OpenPGP key must be provided.");
        }

        PGPPublicKey masterKey = keyRing.getPublicKey();

        if (masterKey.hasRevocation()) {
            throw new IllegalArgumentException("The OpenPGP key is revoked.");
        }

        LocalDateTime keyCreated = toUtcLocalDateTime(masterKey.getCreationTime().toInstant());
        LocalDateTime expires = null;
        long validSeconds = masterKey.getValidSeconds();
        if (validSeconds > 0) {
            Instant expiry = masterKey.getCreationTime().toInstant().plusSeconds(validSeconds);
            expires = toUtcLocalDateTime(expiry);
            if (expiry.isBefore(Instant.now())) {
                throw new IllegalArgumentException("The OpenPGP key is expired.");
            }
        }

        if (!canEncrypt(keyRing)) {
            throw new IllegalArgumentException("The OpenPGP key can not be used to encrypt.");
        }

        String fingerprint = bytesToHex(masterKey.getFingerprint()).toUpperCase();
        String uid = masterKey.getUserIDs().hasNext() ? masterKey.getUserIDs().next() : null;

        return GpgKeyMetadata.builder()
                .fingerprint(fingerprint)
                .keyId(fingerprint.substring(fingerprint.length() - 16))
                .uid(uid)
                .bits(masterKey.getBitStrength())
                .type(algorithmToType(masterKey.getAlgorithm()))
                .keyCreated(keyCreated)
                .expires(expires)
                .build();
    }

    /**
     * True when the master key or at least one non-revoked subkey can encrypt.
     */
    private boolean canEncrypt(PGPPublicKeyRing keyRing) {
        Iterator<PGPPublicKey> keys = keyRing.getPublicKeys();
        while (keys.hasNext()) {
            PGPPublicKey key = keys.next();
            if (key.isEncryptionKey() && !key.hasRevocation()) {
                return true;
            }
        }
        return false;
    }

    private String algorithmToType(int algorithm) {
        switch (algorithm) {
            case PublicKeyAlgorithmTags.RSA_GENERAL:
            case PublicKeyAlgorithmTags.RSA_ENCRYPT:
            case PublicKeyAlgorithmTags.RSA_SIGN:
                return "RSA";
            case PublicKeyAlgorithmTags.DSA:
                return "DSA";
            case PublicKeyAlgorithmTags.ELGAMAL_ENCRYPT:
            case PublicKeyAlgorithmTags.ELGAMAL_GENERAL:
                return "ELGAMAL";
            case PublicKeyAlgorithmTags.ECDH:
            case PublicKeyAlgorithmTags.ECDSA:
            case PublicKeyAlgorithmTags.EDDSA:
                return "ECC";
            default:
                return "UNKNOWN";
        }
    }

    private LocalDateTime toUtcLocalDateTime(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /**
     * Same hex conversion as GpgServiceImpl#bytesToHex (copied on purpose —
     * the existing class must stay untouched).
     */
    private String bytesToHex(byte[] bytes) {
        StringBuilder hexString = new StringBuilder();
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }

    /**
     * Metadata extracted from an uploaded public key, ready to be stored in
     * the gpgkeys table.
     */
    @Data
    @Builder
    public static class GpgKeyMetadata {
        /** 40-char uppercase hex fingerprint of the master key. */
        private String fingerprint;
        /** Last 16 chars of the fingerprint. */
        private String keyId;
        /** First user ID packet, may be null. */
        private String uid;
        private Integer bits;
        /** RSA / DSA / ELGAMAL / ECC / UNKNOWN. */
        private String type;
        private LocalDateTime keyCreated;
        /** null = never expires. */
        private LocalDateTime expires;
    }
}
