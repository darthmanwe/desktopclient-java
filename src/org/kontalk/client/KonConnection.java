/*
 * Kontalk Java client
 * Copyright (C) 2014 Kontalk Devteam <devteam@kontalk.org>

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

package org.kontalk.client;

import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.logging.Logger;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.jivesoftware.smack.ConnectionConfiguration;

import org.jivesoftware.smack.ConnectionConfiguration.SecurityMode;
import org.jivesoftware.smack.SASLAuthentication;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.XMPPException;
import org.jivesoftware.smack.packet.Presence;
//import org.kontalk.Kontalk;

//import android.util.Log;
import java.util.logging.Level;


public class KonConnection extends XMPPConnection {
    private final static Logger LOGGER = Logger.getLogger(KonConnection.class.getName());

    protected EndpointServer mServer;

    private KonConnection(EndpointServer server) throws XMPPException {
        //super(new AndroidConnectionConfiguration(server.getHost(), server.getPort()));
        super(new ConnectionConfiguration(server.getHost(), server.getPort()));

        mServer = server;
        // network name
        config.setServiceName(server.getNetwork());
        // disable reconnection
        config.setReconnectionAllowed(false);
        // enable SASL
        config.setSASLAuthenticationEnabled(true);
        // we don't need the roster
        // TODO yes, we do
        //config.setRosterLoadedAtLogin(false);
        // enable compression
        config.setCompressionEnabled(true);
        // enable encryption
        config.setSecurityMode(SecurityMode.enabled);
        // we will send a custom presence
        // TODO no, send initial default presence
        //config.setSendPresence(false);
    }

    public KonConnection(EndpointServer server, PrivateKey privateKey, X509Certificate bridgeCert) throws XMPPException {
        this(server);

        setupSSL(privateKey, bridgeCert);
    }

    private void setupSSL(PrivateKey privateKey, X509Certificate bridgeCert) {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");

            // in-memory keystore
            KeyStore keystore = KeyStore.getInstance(KeyStore.getDefaultType());
            keystore.load(null, null);
            keystore.setKeyEntry("private", privateKey, new char[0], new Certificate[] { bridgeCert });

            // key managers
            KeyManager[] km;
            KeyManagerFactory kmFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmFactory.init(keystore, new char[0]);

            km = kmFactory.getKeyManagers();

            // trust managers
            TrustManager[] tm = new TrustManager[] {
                new X509TrustManager() {
                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return null;
                    }

                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType)
                        throws CertificateException {
                    }

                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType)
                        throws CertificateException {
                    }
                }
            };
            /*
            TODO builtin keystore
            TrustManagerFactory tmFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmFactory.init((KeyStore) null);

            tm = tmFactory.getTrustManagers();
            */

            ctx.init(km, tm, null);
            config.setCustomSSLContext(ctx);
            //config.setSocketFactory(SSLSocketFactory.getDefault());

            // enable SASL EXTERNAL
            SASLAuthentication.supportSASLMechanism("EXTERNAL");
        }
        catch (Exception e) {
            LOGGER.log(Level.WARNING,"unable to setup SSL connection", e);
        }
    }

    @Override
    public void disconnect() {
        LOGGER.info("disconnecting (no presence)");
        super.disconnect();
    }

    @Override
    public synchronized void disconnect(Presence presence) {
        LOGGER.log(Level.INFO, "disconnecting ({0})", presence);
        super.disconnect(presence);
    }

}