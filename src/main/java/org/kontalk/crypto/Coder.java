/*
 *  Kontalk Java client
 *  Copyright (C) 2016 Kontalk Devteam <devteam@kontalk.org>
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.kontalk.crypto;

import java.io.File;
import java.util.HashMap;
import java.util.Optional;
import java.util.logging.Logger;

import org.kontalk.crypto.PGPUtils.PGPCoderKey;
import org.kontalk.model.Contact;
import org.kontalk.model.message.DecryptMessage;
import org.kontalk.model.message.MessageContent.InAttachment;
import org.kontalk.model.message.OutMessage;

/**
 * Static methods for decryption and encryption of a message.
 *
 * @author Alexander Bikadorov {@literal <bikaejkb@mail.tu-berlin.de>}
 */
public final class Coder {
    private static final Logger LOGGER = Logger.getLogger(Coder.class.getName());

    private Coder() {
    }

    /**
     * Encryption status of a message.
     * Do not modify, only add! Ordinal used in database.
     */
    public enum Encryption {NOT, ENCRYPTED, DECRYPTED}

    /**
     * Signing status of a message.
     * Do not modify, only add! Ordinal used in database.
     */
    public enum Signing {NOT, SIGNED, VERIFIED, UNKNOWN}

    /**
     * Errors that can occur during de-/encryption and verification.
     * Do not modify, only add! Ordinal used in database.
     */
    public enum Error {
        /** Some unknown error. */
        UNKNOWN_ERROR,
        /** Own personal key not found. Unused. */
        MY_KEY_UNAVAILABLE,
        /** Public key of sender not found. */
        KEY_UNAVAILABLE,
        /** My private key does not match. */
        INVALID_PRIVATE_KEY,
        /** Invalid data / parsing failed. */
        INVALID_DATA,
        /** No integrity protection found. */
        NO_INTEGRITY,
        /** Integrity check failed. */
        INVALID_INTEGRITY,
        /** Invalid data / parsing failed of signature. */
        INVALID_SIGNATURE_DATA,
        /** Signature does not match sender. */
        INVALID_SIGNATURE,
        /** Recipient user id in decrypted data does not match id in my key. */
        INVALID_RECIPIENT,
        /** Sender user id in decrypted data does not match id in sender key. */
        INVALID_SENDER,

        //INVALID_MIME,
        //INVALID_TIMESTAMP,
    }

    private static final HashMap<Contact, PGPCoderKey> KEY_MAP = new HashMap<>();

    public static Optional<PGPCoderKey> contactkey(Contact contact) {
        if (KEY_MAP.containsKey(contact)) {
            PGPCoderKey key = KEY_MAP.get(contact);
            if (key.fingerprint.equals(contact.getFingerprint()))
                return Optional.of(key);
        }

        byte[] rawKey = contact.getKey();
        if (rawKey.length != 0) {
            PGPCoderKey key = PGPUtils.readPublicKey(rawKey).orElse(null);
            if (key != null) {
                KEY_MAP.put(contact, key);
                return Optional.of(key);
            }
        }

        LOGGER.warning("key not found for contact: "+contact);
        return Optional.empty();
    }

    /**
     * Decrypt and verify the body of a message. Sets the encryption and signing
     * status of the message and errors that may occur are saved to the message.
     */
    public static boolean decryptMessage(PersonalKey myKey, DecryptMessage message) {
        return Decryptor.decryptMessage(message, myKey);
    }

    /**
     * Decrypt and verify a downloaded attachment file. Sets the encryption and
     * signing status of the message attachment and errors that may occur are
     * saved to the attachment.
     */
    public static void decryptAttachment(PersonalKey myKey, InAttachment attachment, Contact sender) {
        Decryptor.decryptAttachment(attachment, myKey, sender);
    }

    /**
     * Creates encrypted and signed message body.
     * Errors that may occur are saved to the message.
     * @return the encrypted and signed text.
     */
    public static Optional<byte[]> encryptMessage(PersonalKey myKey, OutMessage message) {
        return new Encryptor(myKey, message).encryptMessage();
    }

    public static Optional<byte[]> encryptStanza(PersonalKey myKey, OutMessage message, String xml) {
        return new Encryptor(myKey, message).encryptStanza(xml);
    }

    public static Optional<File> encryptAttachment(PersonalKey myKey, OutMessage message, File file) {
        return new Encryptor(myKey, message).encryptAttachment(file);
    }
}
