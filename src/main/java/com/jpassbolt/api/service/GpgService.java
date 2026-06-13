package com.jpassbolt.api.service;

public interface GpgService {
    String getServerPublicKey();

    String encrypt(String data, String userPublicKey);

    String decrypt(String encryptedData);

    /**
     * Encrypt for the given user public key AND sign with the server private
     * key (PHP {@code OpenPGPBackend::encryptSign}). Used by the JWT login
     * response so official clients can verify the server signature.
     */
    String encryptSign(String data, String userPublicKey);

    /**
     * Decrypt with the server private key AND verify the embedded signature
     * against the given user public key (PHP {@code gpg->decrypt(..., true)}).
     *
     * @throws InvalidSignatureException when the payload carries no signature
     *                                   or the signature does not verify
     *                                   against the user key
     */
    String decryptVerify(String encryptedData, String userPublicKey);

    /**
     * Get the fingerprint of the server's public key.
     *
     * @return 40-character hexadecimal fingerprint
     */
    String getServerKeyFingerprint();

    /**
     * Raised by {@link #decryptVerify} when the signature is missing or does
     * not verify (PHP InvalidSignatureException family).
     */
    class InvalidSignatureException extends RuntimeException {
        public InvalidSignatureException(String message) {
            super(message);
        }

        public InvalidSignatureException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
