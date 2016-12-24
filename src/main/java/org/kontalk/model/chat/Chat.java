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

package org.kontalk.model.chat;

import java.awt.Color;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Observable;
import java.util.Observer;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.lang.ObjectUtils;
import org.jivesoftware.smackx.chatstates.ChatState;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.kontalk.misc.Searchable;
import org.kontalk.model.Contact;
import org.kontalk.model.Model;
import org.kontalk.model.message.KonMessage;
import org.kontalk.persistence.Database;

/**
 * A model for a conversation thread consisting of an ordered list of messages.
 *
 * Changes of contacts in this chat are forwarded.
 *
 * @author Alexander Bikadorov {@literal <bikaejkb@mail.tu-berlin.de>}
 */
public abstract class Chat extends Observable implements Observer, Searchable {
    private static final Logger LOGGER = Logger.getLogger(Chat.class.getName());

    public enum ViewChange {
        READ, NEW_MESSAGE, VIEW_SETTINGS, CONTACT, MEMBER_STATE, SUBJECT, MEMBERS
    }

    public static final String TABLE = "threads";
    public static final String COL_XMPPID = "xmpp_id";
    public static final String COL_GD = "gid";
    public static final String COL_SUBJ = "subject";
    public static final String COL_READ = "read";
    public static final String COL_VIEW_SET = "view_settings";
    public static final String SCHEMA = "( " +
            Database.SQL_ID +
            // optional XMPP chat ID
            COL_XMPPID+" TEXT UNIQUE, " +
            // optional subject
            COL_SUBJ+" TEXT, " +
            // boolean, contains unread messages?
            COL_READ+" INTEGER NOT NULL, " +
            // view settings in JSON format
            COL_VIEW_SET+" TEXT NOT NULL, " +
            // optional group id in JSON format
            COL_GD+" TEXT " +
            ")";

    final int mID;
    private final ChatMessages mMessages;

    private boolean mRead;
    private boolean mDeleted = false;

    private ViewSettings mViewSettings;

    Chat(String xmppID, String subject, GroupMetaData gData) {
        mMessages = new ChatMessages();
        mRead = true;
        mViewSettings = ViewSettings.createDefault();

        // insert
        List<Object> values = Arrays.asList(
                Database.setString(xmppID),
                Database.setString(subject),
                mRead,
                mViewSettings.toJSONString(),
                Database.setString(gData == null ? "" : gData.toJSON()));
        mID = Model.database().execInsert(TABLE, values);
        if (mID < 1) {
            LOGGER.warning("could not insert chat");
        }
    }

    // used when loading from database
    Chat(int id, boolean read, String jsonViewSettings) {
        mID = id;
        mMessages = new ChatMessages();
        mRead = read;
        mViewSettings = new ViewSettings(jsonViewSettings);
    }

    private void loadMessages(Database db, Map<Integer, Contact> contactMap) {
        mMessages.load(db, this, contactMap);
    }

    public ChatMessages getMessages() {
        return mMessages;
    }

    public boolean addMessage(KonMessage message) {
        assert message.getChat() == this;

        boolean added = mMessages.add(message);
        if (added) {
            if (message.isInMessage() && mRead) {
                mRead = false;
                this.save();
                this.changed(ViewChange.READ);
            }
            this.changed(ViewChange.NEW_MESSAGE);
        }
        return added;
    }

    public int getID() {
        return mID;
    }

    public boolean isRead() {
        return mRead;
    }

    public void setRead() {
        if (mRead)
            return;

        mRead = true;
        this.save();
        this.changed(ViewChange.READ);
    }

    public ViewSettings getViewSettings() {
        return mViewSettings;
    }

    public void setViewSettings(ViewSettings settings) {
        if (settings.equals(mViewSettings))
            return;

        mViewSettings = settings;
        this.save();
        this.changed(ViewChange.VIEW_SETTINGS);
    }

    public boolean isGroupChat() {
        return (this instanceof GroupChat);
    }

    public abstract List<Member> getAllMembers();

    /** Get all contacts (including deleted, blocked and user contact). */
    public abstract List<Contact> getAllContacts();

    /** Get valid receiver contacts (without deleted and blocked). */
    public abstract List<Contact> getValidContacts();

    /** XMPP thread ID (empty string if not set). */
    public abstract String getXMPPID();

    /** Subject/title (empty string if not set). */
    public abstract String getSubject();

    /**
     * Return if new outgoing messages in chat will be encrypted.
     * True if encryption is turned on for at least one valid chat contact.
     */
    public abstract boolean isSendEncrypted();

    /**
     * Return if new outgoing messages could be send encrypted.
     * True if all valid  chat contacts have a key.
     */
    public abstract boolean canSendEncrypted();

    /** Return if new valid outgoing message could be send. */
    public abstract boolean isValid();

    public abstract boolean isAdministratable();

    public abstract void setChatState(Contact contact, ChatState chatState);

    abstract void save();

    // not saving members here
    void save(String subject) {
        Map<String, Object> set = new HashMap<>();
        set.put(COL_SUBJ, Database.setString(subject));
        set.put(COL_READ, mRead);
        set.put(COL_VIEW_SET, mViewSettings.toJSONString());

        Database db = Model.database();
        db.execUpdate(TABLE, set, mID);
    }

    void delete() {
        // messages
        boolean succ = mMessages.getAll().stream().allMatch(KonMessage::delete);
        if (!succ)
            return;

        // members
        Database db = Model.database();
        succ = this.getAllMembers().stream().allMatch(m -> m.delete(db));
        if (!succ)
            return;

        // chat itself
        db.execDelete(TABLE, mID);

        // all done, commit deletions
        succ = db.commit();
        if (!succ)
            return;

        mDeleted = true;
    }

    public boolean isDeleted()  {
        return mDeleted;
    }

    void changed(ViewChange change) {
        this.setChanged();
        this.notifyObservers(change);
    }

    @Override
    public void update(Observable o, Object arg) {
        this.changed(ViewChange.CONTACT);
    }

    @Override
    public boolean contains(String search) {
            for (Contact contact: this.getAllContacts()) {
                if (contact.contains(search))
                    return true;
            }
            return this.getSubject().toLowerCase().contains(search);
    }

    static Optional<Chat> load(Database db, ResultSet rs, Map<Integer, Contact> contactMap)
            throws SQLException {
        int id = rs.getInt("_id");

        String jsonGD = Database.getString(rs, Chat.COL_GD);
        GroupMetaData gData = jsonGD.isEmpty() ?
                null :
                GroupMetaData.fromJSONOrNull(jsonGD);

        String xmppID = Database.getString(rs, Chat.COL_XMPPID);

        // get members of chat
        List<Member> members = Member.load(db, id, contactMap);

        String subject = Database.getString(rs, Chat.COL_SUBJ);

        boolean read = rs.getBoolean(Chat.COL_READ);

        String jsonViewSettings = Database.getString(rs,
                Chat.COL_VIEW_SET);

        Chat chat;
        if (gData != null) {
            chat = GroupChat.create(id, members, gData, subject, read, jsonViewSettings);
        } else {
            if (members.size() != 1) {
                LOGGER.warning("not one contact for single chat, id="+id);
                return Optional.empty();
            }
            chat = new SingleChat(id, members.get(0), xmppID, read, jsonViewSettings);
        }

        chat.loadMessages(db, contactMap);
        return Optional.of(chat);
    }

    public static class ViewSettings {
        private static final String JSON_BG_COLOR = "bg_color";
        private static final String JSON_IMAGE_PATH = "img";

        // background color, if set
        private final Color mColor;
        // custom image, if set
        private final String mImagePath;

        private ViewSettings(String json) {
            Object obj = JSONValue.parse(json);
            Color color;
            String imagePath;
            try {
                Map<?, ?> map = (Map) obj;
                color = map.containsKey(JSON_BG_COLOR) ?
                    new Color(((Long) map.get(JSON_BG_COLOR)).intValue()) :
                    null;
                imagePath = map.containsKey(JSON_IMAGE_PATH) ?
                    (String) map.get(JSON_IMAGE_PATH) :
                    "";
            } catch (NullPointerException | ClassCastException ex) {
                LOGGER.log(Level.WARNING, "can't parse JSON view settings", ex);
                color = null;
                imagePath = "";
            }
            mColor = color;
            mImagePath = imagePath;
        }

        public static ViewSettings createDefault() {
            return new ViewSettings(null, "");
        }

        public static ViewSettings fromColor(Color color) {
            return new ViewSettings(color, "");
        }

        public static ViewSettings fromImagePath(String imagePath) {
            return new ViewSettings(null, imagePath);
        }

        private ViewSettings(Color c, String p) {
            mColor = c;
            mImagePath = p;
        }

        public Optional<Color> getBGColor() {
            return Optional.ofNullable(mColor);
        }

        public String getImagePath() {
            return mImagePath;
        }

        // using legacy lib, raw types extend Object
        @SuppressWarnings("unchecked")
        String toJSONString() {
            JSONObject json = new JSONObject();
            if (mColor != null)
                json.put(JSON_BG_COLOR, mColor.getRGB());
            if (!mImagePath.isEmpty())
                json.put(JSON_IMAGE_PATH, mImagePath);
            return json.toJSONString();
        }

        @Override
        public final boolean equals(Object o) {
            if (o == this)
                return true;

            if (!(o instanceof ViewSettings))
                return false;

            ViewSettings ovs = (ViewSettings) o;

            return ObjectUtils.equals(mColor, ovs.mColor) &&
                    mImagePath.equals(ovs.mImagePath);
        }

        @Override
        public int hashCode() {
            int hash = 7;
            hash = 37 * hash + Objects.hashCode(this.mColor);
            hash = 37 * hash + Objects.hashCode(this.mImagePath);
            return hash;
        }

        @Override
        public String toString() {
            return "VS:color="+mColor+",imgPath="+mImagePath;
        }
    }
}
