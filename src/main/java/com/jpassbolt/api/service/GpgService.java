package com.jpassbolt.api.service;

public interface GpgService {
    String getServerPublicKey();

    String encrypt(String data, String userPublicKey);

    String decrypt(String encryptedData);

    /**
     * Get the fingerprint of the server's public key.
     * 
     * @return 40-character hexadecimal fingerprint
     */
    String getServerKeyFingerprint();
}
