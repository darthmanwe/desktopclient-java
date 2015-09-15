/*
 *  Kontalk Java client
 *  Copyright (C) 2014 Kontalk Devteam <devteam@kontalk.org>
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

import java.util.HashMap;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.kontalk.crypto.PGPUtils.PGPCoderKey;
import org.kontalk.model.Contact;
import org.kontalk.system.AccountLoader;

/**
 * Static methods for decryption and encryption of a message.
 *
 * @author Alexander Bikadorov {@literal <bikaejkb@mail.tu-berlin.de>}
 */
public final class Coder {
    private static final Logger LOGGER = Logger.getLogger(Coder.class.getName());

    /**
     * Encryption status of a message.
     * Do not modify, only add! Ordinal used in database.
     */
    public static enum Encryption {NOT, ENCRYPTED, DECRYPTED}

    /**
     * Signing status of a message.
     * Do not modify, only add! Ordinal used in database.
     */
    public static enum Signing {NOT, SIGNED, VERIFIED, UNKNOWN}

    /**
     * Errors that can occur during de-/encryption and verification.
     * Do not modify, only add! Ordinal used in database.
     */
    public static enum Error {
        /** Some unknown error. */
        UNKNOWN_ERROR,
        /** Own personal key not found. */
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

    static PersonalKey myKeyOrNull() {
        Optional<PersonalKey> optMyKey = AccountLoader.getInstance().getPersonalKey();
        if (!optMyKey.isPresent()) {
            LOGGER.log(Level.WARNING, "can't get personal key");
            return null;
        }
        return optMyKey.get();
    }

    static PGPCoderKey contactkeyOrNull(Contact contact) {
        if (KEY_MAP.containsKey(contact)) {
            PGPCoderKey key = KEY_MAP.get(contact);
            if (key.fingerprint.equals(contact.getFingerprint()))
                return key;
        }

        byte[] rawKey = contact.getKey();
        if (rawKey.length != 0) {
            Optional<PGPCoderKey> optKey = PGPUtils.readPublicKey(rawKey);
            if (optKey.isPresent()) {
                KEY_MAP.put(contact, optKey.get());
                return optKey.get();
            }
        }

        LOGGER.warning("key not found for contact: "+contact);
        return null;
    }
}
