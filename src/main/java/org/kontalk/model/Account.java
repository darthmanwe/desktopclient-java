/*
 * Kontalk Java client
 * Copyright (C) 2016 Kontalk Devteam <devteam@kontalk.org>

 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.kontalk.model;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.security.NoSuchProviderException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.io.IOUtils;
import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.openpgp.PGPException;
import org.kontalk.misc.KonException;
import org.kontalk.crypto.PGPUtils;
import org.kontalk.crypto.PersonalKey;
import org.kontalk.crypto.X509Bridge;
import org.kontalk.persistence.Config;
import org.kontalk.util.EncodingUtils;

/**
 * The user account.
 *
 * @author Alexander Bikadorov {@literal <bikaejkb@mail.tu-berlin.de>}
 */
public final class Account {
    private static final Logger LOGGER = Logger.getLogger(Account.class.getName());

    private static final String PRIVATE_KEY_FILENAME = "kontalk-private.asc";
    private static final String BRIDGE_CERT_FILENAME = "kontalk-login.crt";

    private final Path mKeyDir;
    private final Config mConf;

    private PersonalKey mKey = null;

    Account(Path keyDir, Config config) {
        mKeyDir = keyDir;
        mConf = config;
    }

    public Optional<PersonalKey> getPersonalKey() {
        return Optional.ofNullable(mKey);
    }

    public PersonalKey load(char[] password) throws KonException {
        // read key files
        byte[] privateKeyData = this.readFile(PRIVATE_KEY_FILENAME, true);
        byte[] bridgeCertData = this.readFile(BRIDGE_CERT_FILENAME, false);

        // load key
        try {
            mKey = PersonalKey.load(privateKeyData,
                    password,
                    bridgeCertData);
        } catch (PGPException | IOException | CertificateException | NoSuchProviderException ex) {
            LOGGER.log(Level.WARNING, "can't load personal key", ex);
            throw new KonException(KonException.Error.LOAD_KEY, ex);
        }
        return mKey;
    }

    public void setAccount(byte[] privateKeyData, char[] password) throws KonException {
        // try to load key
        PersonalKey key;
        try {
            key = PersonalKey.load(privateKeyData, password);
        } catch (PGPException | IOException | CertificateException |
                NoSuchProviderException ex) {
            LOGGER.log(Level.WARNING, "can't import personal key", ex);
            throw new KonException(KonException.Error.IMPORT_KEY, ex);
        }

        // key seems valid. Save to config dir
        byte[] bridgeCertData;
        try {
            bridgeCertData = X509Bridge.encode(key.getBridgeCertificate());
        } catch (CertificateEncodingException | IOException ex) {
            LOGGER.log(Level.WARNING, "can't encode bridge certificaate");
            throw new KonException(KonException.Error.IMPORT_KEY, ex);
        }
        this.writeBytesToFile(bridgeCertData, BRIDGE_CERT_FILENAME, false);
        this.writePrivateKey(privateKeyData, password, new char[0]);

        // success! use the new key
        mKey = key;

        // parse JID from user ID in key, could be wrong, e.g. an email address
        // overwritten when connecting to server
        String address = PGPUtils.parseUID(key.getUserId())[2];
        Config.getInstance().setProperty(Config.ACC_JID, address);

        LOGGER.info("new account, temporary JID: "+address);
    }

    public char[] getPassword() {
        return Config.getInstance().getString(Config.ACC_PASS).toCharArray();
    }

    public void setPassword(char[] oldPassword, char[] newPassword) throws KonException {
        byte[] privateKeyData = this.readFile(PRIVATE_KEY_FILENAME, true);
        this.writePrivateKey(privateKeyData, oldPassword, newPassword);
    }

    private void writePrivateKey(byte[] privateKeyData,
            char[] oldPassword,
            char[] newPassword)
            throws KonException {
        // old password
        if (oldPassword.length < 1)
            oldPassword = mConf.getString(Config.ACC_PASS).toCharArray();

        // new password
        boolean unset = newPassword.length == 0;
        if (unset)
            newPassword = EncodingUtils.randomString(40).toCharArray();

        // write new
        try {
            privateKeyData = PGPUtils.copySecretKeyRingWithNewPassword(privateKeyData,
                    oldPassword, newPassword).getEncoded();
        } catch (IOException | PGPException ex) {
            LOGGER.log(Level.WARNING, "can't change password", ex);
            throw new KonException(KonException.Error.CHANGE_PASS, ex);
        }
        this.writeBytesToFile(privateKeyData, PRIVATE_KEY_FILENAME, true);

        // new saved password
        String savedPass = unset ? new String(newPassword) : "";
        mConf.setProperty(Config.ACC_PASS, savedPass);
    }

    public boolean isPresent() {
        return this.fileExists(PRIVATE_KEY_FILENAME) &&
                this.fileExists(BRIDGE_CERT_FILENAME);
    }

    public boolean isPasswordProtected() {
        // using configuration option to determine this
        return mConf.getString(Config.ACC_PASS).isEmpty();
    }

    private byte[] readFile(String filename, boolean disarm) throws KonException {
        byte[] bytes;
        try (InputStream input = new FileInputStream(new File(mKeyDir.toString(), filename))) {
            bytes = disarm ? PGPUtils.mayDisarm(input) : IOUtils.toByteArray(input);
        } catch (IOException ex) {
            LOGGER.log(Level.WARNING, "can't read key file", ex);
            throw new KonException(KonException.Error.READ_FILE, ex);
        }
        return bytes;
    }

    private void writeBytesToFile(byte[] bytes, String filename, boolean armored) throws KonException {
        try {
            OutputStream outStream = new FileOutputStream(new File(mKeyDir.toString(), filename));
            if (armored)
                outStream = new ArmoredOutputStream(outStream);
            outStream.write(bytes);
            outStream.close();
        } catch (IOException ex) {
            LOGGER.log(Level.WARNING, "can't write key file", ex);
            throw new KonException(KonException.Error.WRITE_FILE, ex);
        }
    }

    private boolean fileExists(String filename) {
        return new File(mKeyDir.toString(), filename).isFile();
    }
}
