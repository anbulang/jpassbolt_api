package com.jpassbolt.api.util;

import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openpgp.*;
import org.bouncycastle.openpgp.jcajce.JcaPGPObjectFactory;
import org.bouncycastle.openpgp.operator.jcajce.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.Security;
import java.util.Date;
import java.util.Iterator;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * GPG test helper class for authentication tests.
 * Provides utility methods for creating and validating test data.
 */
public class GpgTestHelper {

    public static final String GPGAUTH_VERSION = "gpgauthv1.3.0";
    public static final int UUID_LENGTH = 36;

    // Nonce format: gpgauthv1.3.0|36|{UUID}|gpgauthv1.3.0
    private static final Pattern NONCE_PATTERN = Pattern.compile(
            "gpgauthv1\\.3\\.0\\|36\\|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\|gpgauthv1\\.3\\.0");

    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    /**
     * Generate a valid nonce in Passbolt format.
     * Format: gpgauthv1.3.0|36|{UUID}|gpgauthv1.3.0
     */
    public static String generateValidNonce() {
        String uuid = UUID.randomUUID().toString();
        return formatNonce(uuid);
    }

    /**
     * Format a UUID into a Passbolt nonce.
     */
    public static String formatNonce(String uuid) {
        return GPGAUTH_VERSION + "|" + UUID_LENGTH + "|" + uuid + "|" + GPGAUTH_VERSION;
    }

    /**
     * Check if a nonce matches the expected Passbolt format.
     */
    public static boolean isValidNonce(String nonce) {
        if (nonce == null || nonce.isEmpty()) {
            return false;
        }
        return NONCE_PATTERN.matcher(nonce).matches();
    }

    /**
     * Extract UUID from a valid nonce.
     */
    public static String extractUuidFromNonce(String nonce) {
        if (!isValidNonce(nonce)) {
            return null;
        }
        String[] parts = nonce.split("\\|");
        if (parts.length != 4) {
            return null;
        }
        return parts[2];
    }

    /**
     * Generate various invalid nonce formats for testing.
     */
    public static String[] getInvalidNonceFormats() {
        String uuid = UUID.randomUUID().toString();
        return new String[] {
                "", // empty
                "XXX", // wrong format
                "gpgauthv1.2.0|36|" + uuid + "|gpgauthv1.3.0", // wrong version
                "gpgauthv1.3.0|36|" + uuid + "|gpgauthv1.2.0", // version mismatch
                "gpgauthv1.3.0|32|" + uuid + "|gpgauthv1.3.0", // wrong length (32)
                "gpgauthv1.3.0||" + uuid + "|gpgauthv1.3.0", // missing length
                "gpgauthv1.3.0,36|" + uuid + "|gpgauthv1.3.0", // wrong delimiter
                "gpgauthv1.3.0|36|invalid-uuid|gpgauthv1.3.0", // invalid UUID
                "gpgauthv1.3.0|36|" + uuid + "|gpgauthv1.3.0|extra", // extra section
        };
    }

    /**
     * Generate various invalid fingerprint formats for testing.
     */
    public static String[] getInvalidFingerprints() {
        return new String[] {
                "", // empty
                "XXX", // too short
                "333788B5464B797FDF10A98F2FE96B47C7FF421B", // valid format but non-existent
                "333788B5464B797FDF10A98F2FE96B47C7FF421AB", // 41 chars (too long)
                "333788B5464B797FDF10A98F2FE96\\47C7FF41AB", // contains backslash
                "333788B5464B797FDF10A98F2FE96\"47C7FF41AB", // contains quotes
                "333788B5464B797FDF10A98F2FE96'47C7FF41AZ", // contains Z (not hex)
        };
    }

    /**
     * Get the 16-character key ID from a 40-character fingerprint.
     */
    public static String fingerprintToKeyId(String fingerprint) {
        if (fingerprint == null || fingerprint.length() < 16) {
            return null;
        }
        return fingerprint.substring(fingerprint.length() - 16);
    }

    /**
     * Validate fingerprint format (40 hex characters).
     */
    public static boolean isValidFingerprint(String fingerprint) {
        if (fingerprint == null) {
            return false;
        }
        return fingerprint.matches("^[A-Fa-f0-9]{40}$");
    }

    /**
     * Encrypt data using a public key.
     */
    public static String encrypt(String data, PGPPublicKey publicKey) throws Exception {
        ByteArrayOutputStream encryptedOut = new ByteArrayOutputStream();
        ArmoredOutputStream armoredOut = new ArmoredOutputStream(encryptedOut);

        PGPEncryptedDataGenerator encryptedDataGenerator = new PGPEncryptedDataGenerator(
                new JcePGPDataEncryptorBuilder(PGPEncryptedData.AES_256)
                        .setWithIntegrityPacket(true)
                        .setSecureRandom(new SecureRandom())
                        .setProvider(BouncyCastleProvider.PROVIDER_NAME));

        encryptedDataGenerator.addMethod(
                new JcePublicKeyKeyEncryptionMethodGenerator(publicKey)
                        .setProvider(BouncyCastleProvider.PROVIDER_NAME));

        byte[] dataBytes = data.getBytes(StandardCharsets.UTF_8);

        try (OutputStream encryptedStream = encryptedDataGenerator.open(armoredOut, new byte[4096])) {
            PGPLiteralDataGenerator literalDataGenerator = new PGPLiteralDataGenerator();
            try (OutputStream literalOut = literalDataGenerator.open(
                    encryptedStream,
                    PGPLiteralData.UTF8,
                    PGPLiteralData.CONSOLE,
                    dataBytes.length,
                    new Date())) {
                literalOut.write(dataBytes);
            }
        }

        armoredOut.close();
        return encryptedOut.toString(StandardCharsets.UTF_8);
    }

    /**
     * Decrypt data using a private key.
     */
    public static String decrypt(String encryptedData, PGPPrivateKey privateKey) throws Exception {
        byte[] encryptedBytes = encryptedData.getBytes(StandardCharsets.UTF_8);
        InputStream inputStream = PGPUtil.getDecoderStream(new ByteArrayInputStream(encryptedBytes));

        JcaPGPObjectFactory pgpObjectFactory = new JcaPGPObjectFactory(inputStream);
        Object object = pgpObjectFactory.nextObject();

        PGPEncryptedDataList encryptedDataList;
        if (object instanceof PGPEncryptedDataList) {
            encryptedDataList = (PGPEncryptedDataList) object;
        } else {
            encryptedDataList = (PGPEncryptedDataList) pgpObjectFactory.nextObject();
        }

        PGPPublicKeyEncryptedData encryptedDataPacket = (PGPPublicKeyEncryptedData) encryptedDataList
                .getEncryptedDataObjects().next();

        InputStream decryptedStream = encryptedDataPacket.getDataStream(
                new JcePublicKeyDataDecryptorFactoryBuilder()
                        .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                        .build(privateKey));

        JcaPGPObjectFactory decryptedFactory = new JcaPGPObjectFactory(decryptedStream);
        Object decryptedObject = decryptedFactory.nextObject();

        if (decryptedObject instanceof PGPCompressedData) {
            PGPCompressedData compressedData = (PGPCompressedData) decryptedObject;
            decryptedFactory = new JcaPGPObjectFactory(compressedData.getDataStream());
            decryptedObject = decryptedFactory.nextObject();
        }

        if (decryptedObject instanceof PGPLiteralData) {
            PGPLiteralData literalData = (PGPLiteralData) decryptedObject;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream literalStream = literalData.getInputStream()) {
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = literalStream.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
            }
            return out.toString(StandardCharsets.UTF_8);
        }

        throw new RuntimeException("Unexpected PGP object type: " + decryptedObject.getClass().getName());
    }

    /**
     * Load a public key from armored string.
     */
    public static PGPPublicKey loadPublicKey(String armoredKey) throws Exception {
        InputStream keyStream = new ByteArrayInputStream(armoredKey.getBytes(StandardCharsets.UTF_8));
        PGPPublicKeyRingCollection keyRings = new PGPPublicKeyRingCollection(
                PGPUtil.getDecoderStream(keyStream),
                new JcaKeyFingerprintCalculator());

        for (PGPPublicKeyRing keyRing : keyRings) {
            Iterator<PGPPublicKey> keys = keyRing.getPublicKeys();
            while (keys.hasNext()) {
                PGPPublicKey key = keys.next();
                if (key.isEncryptionKey()) {
                    return key;
                }
            }
        }
        throw new RuntimeException("No encryption key found");
    }

    /**
     * Get fingerprint from public key.
     */
    public static String getFingerprint(PGPPublicKey publicKey) {
        byte[] fingerprint = publicKey.getFingerprint();
        StringBuilder hexString = new StringBuilder();
        for (byte b : fingerprint) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString().toUpperCase();
    }
}
