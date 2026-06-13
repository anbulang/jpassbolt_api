package com.jpassbolt.api.service;

import org.bouncycastle.bcpg.ArmoredInputStream;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openpgp.PGPEncryptedDataList;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPObjectFactory;
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
import java.util.Iterator;

/**
 * Parse-only Bouncy Castle validation helper for the v5 (zero-knowledge)
 * metadata system. Lives in the keys domain and is reused (by injection) by
 * the session-keys domain.
 *
 * <p>
 * Project iron rule #1: GPG/PGP work goes through Bouncy Castle only, never the
 * system gpg binary. Equally important for v5: the server is ZERO-KNOWLEDGE —
 * it must <em>never decrypt</em> metadata, metadata private keys or session
 * keys. Therefore the methods here only confirm that an ASCII-armored blob is a
 * <em>well-formed OpenPGP MESSAGE / public key</em> at the packet level
 * (structural parse), and they never attempt decryption.
 * </p>
 *
 * <p>
 * This mirrors the PHP reference behaviour:
 * <ul>
 *   <li>{@code MessageValidationService::isParsableArmoredMessage} — the
 *       metadata_private_keys.data / session_keys.data blobs must be a parsable
 *       armored OpenPGP MESSAGE (used by {@code IsParsableMessageValidationRule}
 *       on MetadataPrivateKeysTable.data).</li>
 *   <li>{@code IsParsableArmoredKeyValidationRule} — the metadata_keys.armored_key
 *       must be a parsable armored public key, from which we extract the
 *       fingerprint for the uniqueness / server-key / user-key reuse checks.</li>
 * </ul>
 * </p>
 */
@Service
public class MetadataKeyValidationService {

    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    /**
     * Assert that the supplied blob is a well-formed ASCII-armored OpenPGP
     * MESSAGE. Performs a structural parse ONLY — it never decrypts (the server
     * holds no private key for these messages and must remain zero-knowledge).
     *
     * <p>
     * A valid metadata-private-key / session-key blob is an asymmetric
     * (public-key encrypted) MESSAGE: the first object produced by the PGP
     * object factory must be a {@link PGPEncryptedDataList}. We do not iterate
     * into the encrypted payload (that would require the private key).
     * </p>
     *
     * @param armored the ASCII-armored OpenPGP MESSAGE
     * @return {@code true} when the blob parses as an OpenPGP MESSAGE,
     *         {@code false} otherwise
     */
    public boolean isParsableOpenPgpMessage(String armored) {
        if (armored == null || armored.isBlank()) {
            return false;
        }
        // The PHP ascii() validator rejects non-ASCII payloads before the
        // parse rule runs; mirror that here so an obviously-malformed blob is
        // rejected up front.
        if (!isAscii(armored)) {
            return false;
        }
        try (InputStream raw = new ByteArrayInputStream(armored.getBytes(StandardCharsets.UTF_8));
                ArmoredInputStream armoredIn = new ArmoredInputStream(raw)) {
            InputStream decoded = PGPUtil.getDecoderStream(armoredIn);
            PGPObjectFactory factory = new PGPObjectFactory(decoded, new JcaKeyFingerprintCalculator());
            Object first = factory.nextObject();
            if (first == null) {
                return false;
            }
            // An asymmetric OpenPGP MESSAGE starts with a list of public-key
            // (or symmetric) encrypted session-key packets. We accept the blob
            // as a MESSAGE if the leading object is an encrypted data list —
            // crucially WITHOUT attempting to decrypt it (zero-knowledge).
            return first instanceof PGPEncryptedDataList;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * Like {@link #isParsableOpenPgpMessage(String)} but throws when the blob
     * is not a parsable OpenPGP MESSAGE, so callers can surface a clean 400.
     *
     * @param armored the ASCII-armored OpenPGP MESSAGE
     * @throws IllegalArgumentException when the blob is not a parsable message
     */
    public void assertParsableOpenPgpMessage(String armored) {
        if (!isParsableOpenPgpMessage(armored)) {
            throw new IllegalArgumentException(
                    "The message should be a valid ASCII-armored OpenPGP message.");
        }
    }

    /**
     * Parse an ASCII-armored OpenPGP public key (parse-only) and return the
     * master-key fingerprint (40-char uppercase hex). Used by the keys-domain
     * service to enforce fingerprint uniqueness and the server/user key reuse
     * rules. No decryption is performed.
     *
     * @param armoredKey the ASCII-armored OpenPGP public key block
     * @return the master key fingerprint (uppercase hex)
     * @throws IllegalArgumentException when the key cannot be parsed
     */
    public String extractFingerprint(String armoredKey) {
        if (armoredKey == null || armoredKey.isBlank()) {
            throw new IllegalArgumentException("An armored key is required.");
        }
        if (!isAscii(armoredKey)) {
            throw new IllegalArgumentException("The armored key should be a valid ASCII string.");
        }
        try (InputStream in = PGPUtil.getDecoderStream(
                new ByteArrayInputStream(armoredKey.getBytes(StandardCharsets.UTF_8)))) {
            PGPPublicKeyRingCollection collection = new PGPPublicKeyRingCollection(
                    in, new JcaKeyFingerprintCalculator());
            Iterator<PGPPublicKeyRing> rings = collection.getKeyRings();
            if (!rings.hasNext()) {
                throw new IllegalArgumentException("A valid OpenPGP key must be provided.");
            }
            PGPPublicKey masterKey = rings.next().getPublicKey();
            return bytesToHex(masterKey.getFingerprint()).toUpperCase();
        } catch (IOException | PGPException | RuntimeException e) {
            throw new IllegalArgumentException("The armored key could not be parsed.", e);
        }
    }

    /**
     * Parse-only validation that the supplied blob is a well-formed armored
     * OpenPGP public key and not revoked (matching
     * {@code IsParsableArmoredKeyValidationRule} + {@code IsPublicKeyRevokedRule}
     * from the PHP reference). No decryption is performed.
     *
     * @param armoredKey the ASCII-armored OpenPGP public key block
     * @return {@code true} when the key parses and is not revoked
     */
    public boolean isParsablePublicKey(String armoredKey) {
        if (armoredKey == null || armoredKey.isBlank() || !isAscii(armoredKey)) {
            return false;
        }
        try (InputStream in = PGPUtil.getDecoderStream(
                new ByteArrayInputStream(armoredKey.getBytes(StandardCharsets.UTF_8)))) {
            PGPPublicKeyRingCollection collection = new PGPPublicKeyRingCollection(
                    in, new JcaKeyFingerprintCalculator());
            Iterator<PGPPublicKeyRing> rings = collection.getKeyRings();
            if (!rings.hasNext()) {
                return false;
            }
            PGPPublicKey masterKey = rings.next().getPublicKey();
            return !masterKey.hasRevocation();
        } catch (IOException | PGPException | RuntimeException e) {
            return false;
        }
    }

    private boolean isAscii(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) > 0x7F) {
                return false;
            }
        }
        return true;
    }

    /**
     * Same hex conversion as GpgServiceImpl#bytesToHex / GpgKeyParserService
     * (copied on purpose so neither existing class needs touching).
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
}
