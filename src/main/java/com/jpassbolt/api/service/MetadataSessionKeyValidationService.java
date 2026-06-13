package com.jpassbolt.api.service;

import com.jpassbolt.api.exception.PassboltApiException;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.openpgp.PGPEncryptedData;
import org.bouncycastle.openpgp.PGPEncryptedDataList;
import org.bouncycastle.openpgp.PGPPublicKeyEncryptedData;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.jcajce.JcaPGPObjectFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

/**
 * Parse-only validator for the {@code data} blob of a metadata session key.
 *
 * <p>The v5 metadata system is <strong>zero-knowledge</strong>: the server only
 * stores and forwards the armored OpenPGP ciphertext (the per-user-encrypted
 * session key). This validator therefore NEVER decrypts the message — it only
 * confirms, with Bouncy Castle (bcpg / bcpkix), that the blob is a well-formed
 * asymmetric OpenPGP MESSAGE. It mirrors the structural checks of the official
 * Passbolt {@code MessageValidationService::getAsymmetricMessageRules()} used by
 * {@code MetadataSessionKeysTable}/{@code MetadataSessionKeyUpdateForm}:</p>
 * <ul>
 *   <li>the data is present, non-empty and ASCII (armored);</li>
 *   <li>it parses into a {@link PGPEncryptedDataList} (parsable armored
 *       message rule);</li>
 *   <li>it contains at least one public-key (asymmetric) encrypted packet
 *       (HAS_ASYMMETRIC_PACKET rule);</li>
 *   <li>it has exactly one recipient (HAS_EXACTLY_ONE_RECIPIENT rule).</li>
 * </ul>
 *
 * <p>Iron Law 1 compliance: pure Bouncy Castle, parse-only. No decryption is
 * attempted and no system GPG binary is ever invoked. The blueprint places a
 * shared OpenPGP-parse helper in the keys domain
 * ({@code MetadataKeyValidationService}); this session-domain validator is the
 * session slice's self-contained equivalent so the session vertical compiles
 * independently of the parallel keys domain.</p>
 */
@Slf4j
@Service
public class MetadataSessionKeyValidationService {

    /**
     * Assert that {@code data} is a parsable, single-recipient asymmetric
     * OpenPGP MESSAGE. Throws {@link PassboltApiException} 400 on any failure;
     * never decrypts.
     *
     * @param data the armored OpenPGP MESSAGE supplied by the client
     * @throws PassboltApiException 400 when the blob is missing, non-ASCII,
     *                              unparsable, or not a single-recipient
     *                              asymmetric message
     */
    public void assertValidEncryptedSessionKey(String data) {
        if (data == null || data.isBlank()) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "The data should not be empty.");
        }
        if (!isAscii(data)) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "The data should be a valid ASCII string.");
        }

        PGPEncryptedDataList encryptedDataList = parseEncryptedDataList(data);

        int asymmetricRecipients = 0;
        Iterator<PGPEncryptedData> it = encryptedDataList.getEncryptedDataObjects();
        while (it.hasNext()) {
            if (it.next() instanceof PGPPublicKeyEncryptedData) {
                asymmetricRecipients++;
            }
        }

        // HAS_ASYMMETRIC_PACKET: the message must contain an asymmetric packet.
        if (asymmetricRecipients == 0) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "The message must contain an asymmetric packet.");
        }
        // HAS_EXACTLY_ONE_RECIPIENT: the message must contain only one recipient.
        if (asymmetricRecipients != 1) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "The message must contain only one recipient.");
        }
    }

    /**
     * Parse the armored blob into a {@link PGPEncryptedDataList} without
     * decrypting. The first object may be the encrypted data list directly or
     * may be preceded by a marker packet (PGPUtil decoder handles dearmoring).
     */
    private PGPEncryptedDataList parseEncryptedDataList(String data) {
        try {
            byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
            InputStream decoderStream = PGPUtil.getDecoderStream(new ByteArrayInputStream(bytes));
            JcaPGPObjectFactory objectFactory = new JcaPGPObjectFactory(decoderStream);

            Object object = objectFactory.nextObject();
            if (object instanceof PGPEncryptedDataList list) {
                return list;
            }
            Object next = objectFactory.nextObject();
            if (next instanceof PGPEncryptedDataList list) {
                return list;
            }
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "The message must contain an asymmetric packet.");
        } catch (PassboltApiException e) {
            throw e;
        } catch (Exception e) {
            log.debug("Metadata session key data is not a parsable OpenPGP message", e);
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "The data could not be parsed as an OpenPGP message.");
        }
    }

    /**
     * Armored OpenPGP messages are 7-bit ASCII; reject anything else early
     * (matches the PHP {@code ->ascii('data')} validator).
     */
    private boolean isAscii(String value) {
        return StandardCharsets.US_ASCII.newEncoder().canEncode(value);
    }
}
