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

package org.kontalk.view;

import javax.net.ssl.SSLHandshakeException;
import javax.swing.Action;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.KeyStroke;
import javax.swing.text.DefaultEditorKit;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Font;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.cert.CertificateException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.alee.extended.filechooser.WebFileChooserField;
import com.alee.laf.menu.WebPopupMenu;
import com.alee.laf.optionpane.WebOptionPane;
import com.alee.laf.text.WebTextArea;
import com.alee.utils.filefilter.ImageFilesFilter;
import org.apache.commons.lang.StringUtils;
import org.jivesoftware.smack.SmackException;
import org.jivesoftware.smack.sasl.SASLErrorException;
import org.jxmpp.util.XmppStringUtils;
import org.kontalk.misc.JID;
import org.kontalk.misc.KonException;
import org.kontalk.model.Contact;
import org.kontalk.model.ContactList;
import org.kontalk.model.chat.Chat;
import org.kontalk.model.chat.Member;
import org.kontalk.model.chat.SingleChat;
import org.kontalk.persistence.Config;
import org.kontalk.system.AttachmentManager;
import org.kontalk.util.EncodingUtils;
import org.kontalk.util.Tr;
import org.ocpsoft.prettytime.PrettyTime;

/**
 * Various utilities used in view.
 * @author Alexander Bikadorov {@literal <bikaejkb@mail.tu-berlin.de>}
 */
final class Utils {
    private static final Logger LOGGER = Logger.getLogger(Utils.class.getName());

    private static final String IMG_DIR =  "img";

    static final DateFormat SHORT_DATE_FORMAT = new SimpleDateFormat("HH:mm");
    static final DateFormat MID_DATE_FORMAT = new SimpleDateFormat("EEE, d MMM HH:mm");
    static final DateFormat LONG_DATE_FORMAT = new SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss");
    static final PrettyTime PRETTY_TIME = new PrettyTime();

    private static final DateFormat DAY_DATE_FORMAT = new SimpleDateFormat("EEE, d MMMM");
    private static final DateFormat DAY_YEAR_DATE_FORMAT = new SimpleDateFormat("EEE, d MMMM yyyy");

    private Utils() {}

    /* fields */

    static WebFileChooserField createImageChooser(String path) {
        WebFileChooserField chooser = new WebFileChooserField();
        if (!path.isEmpty())
            chooser.setSelectedFile(new File(path));
        chooser.setMultiSelectionEnabled(false);
        chooser.setShowRemoveButton(true);
        chooser.getWebFileChooser().setFileFilter(new ImageFilesFilter());
        File file = new File(path);
        if (file.exists()) {
            chooser.setSelectedFile(file);
        }
        if (file.getParentFile() != null && file.getParentFile().exists())
            chooser.getWebFileChooser().setCurrentDirectory(file.getParentFile());
        return chooser;
    }

    static WebTextArea createFingerprintArea() {
        WebTextArea area = new WebTextArea();
        area.setEditable(false);
        area.setOpaque(false);
        area.setFontName(Font.DIALOG);
        area.setFontSizeAndStyle(13, true, false);
        return area;
    }

    static Runnable createLinkRunnable(final Path path) {
        return new Runnable () {
            @Override
            public void run () {
                File file = path.toFile();
                if (!file.exists()) {
                    LOGGER.info("file does not exist: " + file);
                    return;
                }

                Desktop dt = Desktop.getDesktop();
                try {
                    dt.open(file);
                } catch (IOException ex) {
                    LOGGER.log(Level.WARNING, "can't open path", ex);
                }
            }
        };
    }

    // NOTE: use only with text components
    static WebPopupMenu createCopyMenu(boolean modifiable) {
        WebPopupMenu menu = new WebPopupMenu();
        if (modifiable) {
            Action cut = new DefaultEditorKit.CutAction();
            cut.putValue(Action.NAME, Tr.tr("Cut"));
            cut.putValue(Action.ACCELERATOR_KEY, KeyStroke.getKeyStroke("control X"));
            menu.add(cut);
        }

        Action copy = new DefaultEditorKit.CopyAction();
        copy.putValue(Action.NAME, Tr.tr("Copy"));
        copy.putValue(Action.ACCELERATOR_KEY, KeyStroke.getKeyStroke("control C"));
        menu.add(copy);

        if (modifiable) {
            Action paste = new DefaultEditorKit.PasteAction();
            paste.putValue(Action.NAME, Tr.tr("Paste"));
            paste.putValue(Action.ACCELERATOR_KEY, KeyStroke.getKeyStroke("control V"));
            menu.add(paste);
        }

        return menu;
    }

    /* images */

    static Icon getIcon(String fileName) {
        return new ImageIcon(getImage(fileName));
    }

    static Image getImage(String fileName) {
        URL imageUrl = ClassLoader.getSystemResource(Paths.get(IMG_DIR, fileName).toString());
        if (imageUrl == null) {
            LOGGER.warning("can't find icon image resource");
            return new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
        }
        return Toolkit.getDefaultToolkit().createImage(imageUrl);
    }

    /* strings */

    static String name(Contact contact, int maxLength) {
        String name = name_(contact, maxLength);
        return !name.isEmpty() ? name :
                "("+Tr.tr("Unknown")+")";
    }

    static String displayName(Contact contact) {
        return displayName(contact, Integer.MAX_VALUE);
    }

    static String displayName(Contact contact, int maxLength) {
        return displayName(contact, contact.getJID(), maxLength);
    }

    static String displayName(Contact contact, JID jid, int maxLength) {
        String name = name_(contact, maxLength);
        return !name.isEmpty() ? name : jid(jid, maxLength);
    }

    static String displayNames(List<JID> jids, ContactList contactList, int maxJIDLength) {
        List<String> nameList = new ArrayList<>(jids.size());
        for (JID jid : jids) {
            Contact contact = contactList.get(jid).orElse(null);
            nameList.add(contact != null ?
                                 displayName(contact, jid, Integer.MAX_VALUE) :
                                 jid(jid, maxJIDLength));
        }
        return StringUtils.join(nameList, ", ");
    }

    private static String displayNames(List<Contact> contacts) {
        return displayNames(contacts, Integer.MAX_VALUE);
    }

    static String displayNames(List<Contact> contacts, int maxLength) {
        List<String> nameList = new ArrayList<>(contacts.size());
        for (Contact contact : contacts) {
            nameList.add(displayName(contact, maxLength));
        }
        return StringUtils.join(nameList, ", ");
    }

    private static String name_(Contact contact, int maxLength) {
        return contact.isDeleted() ? "("+Tr.tr("Deleted")+")" :
                contact.isMe() ? Tr.tr("You") :
                StringUtils.abbreviate(contact.getName(), maxLength);
    }

    static String jid(JID jid, int maxLength) {
        String local = jid.local(), domain = jid.domain();
        if (jid.isHash())
            local = "[" + local.substring(0, Math.min(local.length(), 6)) + "]";

        local = StringUtils.abbreviate(local, (int) ((maxLength-1) * 0.4));
        domain = StringUtils.abbreviate(domain, (int) ((maxLength-1) * 0.6));

        return XmppStringUtils.completeJidFrom(local, domain);
    }

    static String chatTitle(Chat chat) {
        if (chat.isGroupChat()) {
            String subj = chat.getSubject();
            return !subj.isEmpty() ? subj : Tr.tr("Group Chat");
        } else {
            return Utils.displayNames(chat.getAllContacts());
        }
    }

    static String chatTooltip(Chat chat) {
        if (chat instanceof SingleChat)
            return jid(((SingleChat) chat).getMember().getContact().getJID(), View.MAX_JID_LENGTH);
        int numMembers = chat.getAllMembers().size();
        return numMembers == 1 ? Tr.tr("one member") :
                String.format(Tr.tr("%1$s members"), numMembers);
    }

    static String fingerprint(String fp) {
        fp = fp.toUpperCase();
        int m = fp.length() / 2;
        return group(fp.substring(0, m)) + "\n" + group(fp.substring(m));
    }

    private static String group(String s) {
        return StringUtils.join(s.split("(?<=\\G.{" + 4 + "})"), " ");
    }

    static String role(Member.Role role) {
        switch (role) {
            case OWNER : return "[" + Tr.tr("Group Owner") + "]";
            default: return "";
        }
    }

    static String mainStatus(Contact c, boolean withLabel) {
        Contact.Subscription subStatus = c.getSubScription();
        return c.isMe() ? Tr.tr("Myself") :
                    c.isBlocked() ? Tr.tr("Blocked") :
                    c.getOnline() == Contact.Online.YES ? Tr.tr("Online") :
                    c.getOnline() == Contact.Online.ERROR ? Tr.tr("Not reachable") :
                    subStatus == Contact.Subscription.UNSUBSCRIBED ? Tr.tr("Not authorized") :
                    subStatus == Contact.Subscription.PENDING ? Tr.tr("Waiting for authorization") :
                    lastSeen(c, withLabel, true);
    }

    static String lastSeen(Contact contact, boolean withLabel, boolean pretty) {
        Date d = contact.getLastSeen().orElse(null);
        return d == null ?
                (withLabel ? Tr.tr("Not seen yet") : "") :
                (withLabel ? Tr.tr("Last seen:") + " " : "")
                        + (pretty ? Utils.PRETTY_TIME.format(d) :
                                   Utils.MID_DATE_FORMAT.format(d));
    }

    static String getErrorText(KonException ex) {
        String eol = " " + EncodingUtils.EOL;
        String errorText;
        switch (ex.getError()) {
            case IMPORT_ARCHIVE:
                errorText = Tr.tr("Can't open key archive.");
                break;
            case IMPORT_READ_FILE:
                errorText = Tr.tr("Can't load all keyfiles from archive.");
                break;
            case IMPORT_KEY:
                errorText = Tr.tr("Can't create personal key from key files.") + " ";
                if (ex.getCauseClass().equals(IOException.class)) {
                    errorText += eol + Tr.tr("Is the public key file valid?");
                }
                if (ex.getCauseClass().equals(CertificateException.class)) {
                    errorText += eol + Tr.tr("Are all key files valid?");
                }
                break;
            case CHANGE_PASS:
                errorText = Tr.tr("Can't change password. Internal error(!?)");
                break;
            case WRITE_FILE:
                errorText = Tr.tr("Can't write key files to configuration directory.");
                break;
            case READ_FILE:
            case LOAD_KEY:
                errorText = "";
                switch (ex.getError()) {
                    case READ_FILE:
                        errorText = Tr.tr("Can't read key files from configuration directory.");
                        break;
                    case LOAD_KEY:
                        errorText = Tr.tr("Can't load key files from configuration directory.");
                        break;
                }
                errorText += eol + Tr.tr("Please reimport your personal key.");
                break;
            case LOAD_KEY_DECRYPT:
                errorText = Tr.tr("Can't decrypt key. Is the passphrase correct?");
                break;
            case CLIENT_CONNECT:
                errorText = Tr.tr("Can't connect to server.");

                if (ex.getCauseClass().equals(SmackException.ConnectionException.class)) {
                    errorText += eol + Tr.tr("Is the server address correct?");
                } else if (ex.getCauseClass().equals(SSLHandshakeException.class)) {
                    errorText += eol + Tr.tr("The server rejects the personal key.");
                } else if (ex.getCauseClass().equals(SmackException.NoResponseException.class)) {
                    errorText += eol + Tr.tr("The server does not respond.");
                } else {
                    Throwable cause = ex.getCause();
                    if (cause != null) {
                        Throwable causeCause = cause.getCause();
                        if (causeCause != null &&
                                causeCause.getClass().equals(SSLHandshakeException.class)) {
                            errorText += eol + Tr.tr("The server certificate could not be validated.");
                        }
                    }
                }
                break;
            case CLIENT_LOGIN:
                errorText = Tr.tr("Can't login to server.");
                if (ex.getCauseClass().equals(SASLErrorException.class)) {
                    errorText += eol + Tr.tr("The server rejects the account. Is the specified server correct and the account valid?");
                }
                break;
            case CLIENT_ERROR:
                errorText = Tr.tr("Connection to server closed on error.");
                // TODO more details
                break;
            case DOWNLOAD_CREATE:
            case DOWNLOAD_EXECUTE:
            case DOWNLOAD_RESPONSE:
            case DOWNLOAD_WRITE:
                errorText = Tr.tr("Downloading file failed");
                // TODO more details
                break;
            case UPLOAD_CREATE:
            case UPLOAD_EXECUTE:
            case UPLOAD_RESPONSE:
                errorText = Tr.tr("Uploading file failed");
                // TODO more details
                break;
            default:
                errorText = Tr.tr("Unusual error:")+" "+ex.getError();
        }
        return errorText;
    }

    /* misc */

    static boolean confirmDeletion(Component parent, String text) {
        int selectedOption = WebOptionPane.showConfirmDialog(parent,
                text,
                Tr.tr("Please Confirm"),
                WebOptionPane.OK_CANCEL_OPTION,
                WebOptionPane.WARNING_MESSAGE);
        return selectedOption == WebOptionPane.OK_OPTION;
    }

    static Set<Contact> allContacts(ContactList contactList, boolean blocked) {
        boolean showMe = Config.getInstance().getBoolean(Config.VIEW_USER_CONTACT);

        return contactList.getAll(showMe, blocked);
    }

    static List<Contact> contactList(Chat chat) {
        List<Contact> contacts = new ArrayList<>(chat.getAllContacts());
        contacts.sort(Utils::compareContacts);
        return contacts;
    }

    static List<Member> memberList(Chat chat) {
        List<Member> members = new ArrayList<>(chat.getAllMembers());
        members.sort(new Comparator<Member>() {
            @Override
            public int compare(Member m1, Member m2) {
                return Utils.compareContacts(m1.getContact(), m2.getContact());
            }
        });
        return members;
    }

    static int compareContacts(Contact c1, Contact c2) {
        if (c1.isMe()) return +1;
        if (c2.isMe()) return -1;

        String s1 = StringUtils.defaultIfEmpty(c1.getName(), c1.getJID().asUnescapedString());
        String s2 = StringUtils.defaultIfEmpty(c2.getName(), c2.getJID().asUnescapedString());
        return s1.compareToIgnoreCase(s2);
    }

    static String getDateSeparatorText(Date date) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(date);
        boolean sameYear = cal.get(Calendar.YEAR) == Calendar.getInstance().get(Calendar.YEAR);
        DateFormat format = sameYear ? DAY_DATE_FORMAT : DAY_YEAR_DATE_FORMAT;
        return format.format(date);
    }

    static boolean isAllowedAttachmentFile(File file) {
        return file.length() <= AttachmentManager.MAX_ATT_SIZE;
    }
}
