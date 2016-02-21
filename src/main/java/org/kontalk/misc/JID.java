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

package org.kontalk.misc;

import java.util.Objects;
import org.apache.commons.lang.StringUtils;
import org.jxmpp.jid.util.JidUtil;
import org.jxmpp.stringprep.simple.SimpleXmppStringprep;
import org.jxmpp.util.XmppStringUtils;
import org.kontalk.model.Account;

/**
 * A Jabber ID (the address of an XMPP entity). Immutable.
 *
 * @author Alexander Bikadorov {@literal <bikaejkb@mail.tu-berlin.de>}
 */
public final class JID {

    private final String mLocal;
    private final String mDomain;
    private final String mResource;

    static {
        // good to know. For working JID validation
        SimpleXmppStringprep.setup();
    }

    private JID(String local, String domain, String resource) {
        mLocal = local;
        mDomain = domain;
        mResource = resource;
    }

    public String local() {
        return mLocal;
    }

    public String domain() {
        return mDomain;
    }

    public String string() {
        return XmppStringUtils.completeJidFrom(mLocal, mDomain, mResource);
    }

    public boolean isValid() {
        if (mLocal.isEmpty() || mDomain.isEmpty())
            return false;

        // NOTE: domain check could be stronger - complaint with RFC 6122, but
        // server does not accept special characters
        return JidUtil.isValidBareJid(this.string());
    }

    public boolean isHash() {
        return mLocal.matches("[0-9a-f]{40}");
    }

    public boolean isFull() {
        return !mResource.isEmpty();
    }

    public boolean isMe() {
        return this.isValid() &&
                this.equals(Account.getInstance().getUserJID());
    }

    public JID toBare() {
        return new JID(mLocal, mDomain, "");
    }

    /**
     * Comparing only bare JIDs.
     * Case-insensitive (local and domain part, resource is case-sensitive).
     */
    @Override
    public boolean equals(Object o) {
        if (o == this)
            return true;

       if (!(o instanceof JID))
           return false;

       JID oJID = (JID) o;
       return mLocal.equalsIgnoreCase(oJID.mLocal) &&
               mDomain.equalsIgnoreCase(oJID.mDomain);
    }

    @Override
    public int hashCode() {
        int hash = 7;
        hash = 17 * hash + Objects.hashCode(this.mLocal.toLowerCase());
        hash = 17 * hash + Objects.hashCode(this.mDomain.toLowerCase());
        return hash;
    }

    @Override
    public String toString() {
        return "'"+this.string()+"'";
    }

    public static JID full(String jid) {
        jid = StringUtils.defaultString(jid);
        return new JID(XmppStringUtils.parseLocalpart(jid),
                XmppStringUtils.parseDomain(jid),
                XmppStringUtils.parseResource(jid));
    }

    public static JID bare(String jid) {
        jid = StringUtils.defaultString(jid);
        return new JID(XmppStringUtils.parseLocalpart(jid),
                XmppStringUtils.parseDomain(jid),
                "");
    }

    public static JID bare(String local, String domain) {
        return new JID(local, domain, "");
    }

    public static JID deleted(int id) {
        return new JID("", Integer.toString(id), "");
    }
}
