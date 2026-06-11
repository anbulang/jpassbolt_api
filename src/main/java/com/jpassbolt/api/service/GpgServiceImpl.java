package com.jpassbolt.api.service;

import com.jpassbolt.api.config.GpgProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openpgp.*;
import org.bouncycastle.openpgp.jcajce.JcaPGPObjectFactory;
import org.bouncycastle.openpgp.operator.jcajce.*;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.Security;
import java.util.Date;
import java.util.Iterator;

/**
 * Implementation of GpgService using Bouncy Castle library for PGP
 * encryption/decryption.
 * This replaces the mock implementation with real cryptographic operations.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GpgServiceImpl implements GpgService {

    private final GpgProperties gpgProperties;
    private final ResourceLoader resourceLoader;

    private PGPSecretKeyRing serverSecretKeyRing;
    private PGPPublicKeyRing serverPublicKeyRing;
    private String serverPublicKeyArmored;

    @PostConstruct
    public void init() {
        // Register Bouncy Castle provider
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }

        try {
            loadServerKeys();
            log.info("GPG keys loaded successfully. Server key fingerprint: {}", getServerKeyFingerprint());
        } catch (Exception e) {
            log.error("Failed to load GPG keys", e);
            throw new RuntimeException("Failed to initialize GPG service", e);
        }
    }

    private void loadServerKeys() throws IOException, PGPException {
        // Load private key
        try (InputStream privateKeyStream = resourceLoader.getResource(
                gpgProperties.getServerKey().getPrivateLocation()).getInputStream()) {
            PGPSecretKeyRingCollection secretKeyRings = new PGPSecretKeyRingCollection(
                    PGPUtil.getDecoderStream(privateKeyStream),
                    new JcaKeyFingerprintCalculator());
            serverSecretKeyRing = secretKeyRings.iterator().next();
        }

        // Load public key
        try (InputStream publicKeyStream = resourceLoader.getResource(
                gpgProperties.getServerKey().getPublicLocation()).getInputStream()) {
            PGPPublicKeyRingCollection publicKeyRings = new PGPPublicKeyRingCollection(
                    PGPUtil.getDecoderStream(publicKeyStream),
                    new JcaKeyFingerprintCalculator());
            serverPublicKeyRing = publicKeyRings.iterator().next();
        }

        // Cache the armored public key
        try (InputStream publicKeyStream = resourceLoader.getResource(
                gpgProperties.getServerKey().getPublicLocation()).getInputStream()) {
            serverPublicKeyArmored = new String(publicKeyStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Override
    public String getServerPublicKey() {
        return serverPublicKeyArmored;
    }

    @Override
    public String getServerKeyFingerprint() {
        PGPPublicKey masterKey = serverPublicKeyRing.getPublicKey();
        byte[] fingerprint = masterKey.getFingerprint();
        return bytesToHex(fingerprint).toUpperCase();
    }

    @Override
    public String encrypt(String data, String userPublicKey) {
        try {
            // Parse the user's public key
            PGPPublicKey encryptionKey = getEncryptionKey(userPublicKey);

            ByteArrayOutputStream encryptedOut = new ByteArrayOutputStream();
            ArmoredOutputStream armoredOut = new ArmoredOutputStream(encryptedOut);

            // Create encrypted data generator
            PGPEncryptedDataGenerator encryptedDataGenerator = new PGPEncryptedDataGenerator(
                    new JcePGPDataEncryptorBuilder(PGPEncryptedData.AES_256)
                            .setWithIntegrityPacket(true)
                            .setSecureRandom(new SecureRandom())
                            .setProvider(BouncyCastleProvider.PROVIDER_NAME));

            encryptedDataGenerator.addMethod(
                    new JcePublicKeyKeyEncryptionMethodGenerator(encryptionKey)
                            .setProvider(BouncyCastleProvider.PROVIDER_NAME));

            byte[] dataBytes = data.getBytes(StandardCharsets.UTF_8);

            try (OutputStream encryptedStream = encryptedDataGenerator.open(armoredOut, new byte[4096])) {
                // Create literal data
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

        } catch (Exception e) {
            log.error("Failed to encrypt data", e);
            throw new RuntimeException("Encryption failed", e);
        }
    }

    @Override
    public String decrypt(String encryptedData) {
        try {
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

            // Find the encrypted data for our key
            PGPPrivateKey privateKey = null;
            PGPPublicKeyEncryptedData encryptedDataPacket = null;

            Iterator<PGPEncryptedData> encryptedDataIterator = encryptedDataList.getEncryptedDataObjects();
            while (privateKey == null && encryptedDataIterator.hasNext()) {
                encryptedDataPacket = (PGPPublicKeyEncryptedData) encryptedDataIterator.next();
                privateKey = findPrivateKey(encryptedDataPacket.getKeyID());
            }

            if (privateKey == null || encryptedDataPacket == null) {
                throw new RuntimeException("No matching private key found for decryption");
            }

            // Decrypt the data
            InputStream decryptedStream = encryptedDataPacket.getDataStream(
                    new JcePublicKeyDataDecryptorFactoryBuilder()
                            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                            .build(privateKey));

            JcaPGPObjectFactory decryptedFactory = new JcaPGPObjectFactory(decryptedStream);
            Object decryptedObject = decryptedFactory.nextObject();

            // Handle compressed data if present
            if (decryptedObject instanceof PGPCompressedData) {
                PGPCompressedData compressedData = (PGPCompressedData) decryptedObject;
                decryptedFactory = new JcaPGPObjectFactory(compressedData.getDataStream());
                decryptedObject = decryptedFactory.nextObject();
            }

            // Extract literal data
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

        } catch (Exception e) {
            log.error("Failed to decrypt data", e);
            throw new RuntimeException("Decryption failed", e);
        }
    }

    /**
     * Extract an encryption-capable public key from an armored key string.
     */
    private PGPPublicKey getEncryptionKey(String armoredPublicKey) throws IOException, PGPException {
        InputStream keyStream = new ByteArrayInputStream(armoredPublicKey.getBytes(StandardCharsets.UTF_8));
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
        throw new RuntimeException("No encryption key found in the provided public key");
    }

    /**
     * Find the private key matching the given key ID.
     */
    private PGPPrivateKey findPrivateKey(long keyId) throws PGPException {
        PGPSecretKey secretKey = serverSecretKeyRing.getSecretKey(keyId);
        if (secretKey == null) {
            return null;
        }

        char[] passphrase = gpgProperties.getServerKey().getPassphrase().toCharArray();
        return secretKey.extractPrivateKey(
                new JcePBESecretKeyDecryptorBuilder()
                        .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                        .build(passphrase));
    }

    /**
     * Convert byte array to hexadecimal string.
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
