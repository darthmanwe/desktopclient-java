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

import javax.swing.Box;
import javax.swing.SwingUtilities;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
import java.util.Observable;
import java.util.Observer;
import java.util.Optional;

import com.alee.extended.layout.FormLayout;
import com.alee.extended.panel.GroupPanel;
import com.alee.extended.panel.GroupingType;
import com.alee.laf.button.WebButton;
import com.alee.laf.checkbox.WebCheckBox;
import com.alee.laf.label.WebLabel;
import com.alee.laf.optionpane.WebOptionPane;
import com.alee.laf.panel.WebPanel;
import com.alee.laf.separator.WebSeparator;
import com.alee.laf.text.WebTextArea;
import com.alee.laf.text.WebTextField;
import com.alee.managers.tooltip.TooltipManager;
import org.kontalk.misc.JID;
import org.kontalk.model.Contact;
import org.kontalk.util.Tr;
import org.kontalk.view.AvatarLoader.AvatarImg;
import org.kontalk.view.ComponentUtils.LabelTextField;

/**
 * Show and edit contact details.
 *
 * @author Alexander Bikadorov {@literal <bikaejkb@mail.tu-berlin.de>}
 */
final class ContactDetails extends WebPanel implements Observer {

    private static final Map<Contact, ContactDetails> CACHE = new HashMap<>();

    private final View mView;
    private final Contact mContact;
    private final ComponentUtils.EditableAvatarImage mAvatarImage;
    private final WebTextField mNameField;
    private final WebLabel mSubscrStatus;
    private final WebButton mSubscrButton;
    private final WebLabel mKeyStatus;
    private final WebLabel mFPLabel;
    private final WebButton mUpdateButton;
    private final WebTextArea mFPArea;
    private final WebCheckBox mEncryptionBox;

    private ContactDetails(View view, Contact contact) {
        mView = view;
        mContact = contact;

        GroupPanel groupPanel = new GroupPanel(View.GAP_BIG, false);
        groupPanel.setMargin(View.MARGIN_BIG);

        groupPanel.add(new WebLabel(Tr.tr("Contact details")).setBoldFont());
        groupPanel.add(new WebSeparator(true, true));

        WebPanel mainPanel = new WebPanel(new FormLayout(View.GAP_DEFAULT, View.GAP_DEFAULT));

        // editable components
        mAvatarImage = new ComponentUtils.EditableAvatarImage(View.AVATAR_DETAIL_SIZE) {
            @Override
            void onImageChange(Optional<BufferedImage> optImage) {
                if (optImage.isPresent())
                    mView.getControl().setCustomContactAvatar(mContact, optImage.get());
                else
                    mView.getControl().unsetCustomContactAvatar(mContact);
            }
            @Override
            AvatarImg defaultImage() {
                return AvatarLoader.load(mContact, View.AVATAR_DETAIL_SIZE);
            }
            @Override
            boolean canRemove() {
                return mContact.hasCustomAvatarSet();
            }
            @Override
            protected void update() {
                if (mContact.hasCustomAvatarSet())
                    return;

                super.update();
            }
        };

        int size = View.AVATAR_DETAIL_SIZE + View.MARGIN_DEFAULT;
        mainPanel.add(new WebPanel(false, mAvatarImage)
                .setPreferredWidth(size).setPreferredHeight(size));
        mainPanel.add(Box.createGlue());

        mainPanel.add(new WebLabel(Tr.tr("Display Name:")));
        mNameField = new LabelTextField(View.MAX_NAME_LENGTH, 15, this) {
            @Override
            protected String labelText() {
                return mContact.getName();
            }
            @Override
            protected String editText() {
                return mContact.getName();
            }
            @Override
            protected void onFocusLost() {
                ContactDetails.this.saveName(this.getText().trim());
            }
        };
        mNameField.setFontSizeAndStyle(14, true, false);
        mainPanel.add(mNameField);

        mainPanel.add(new WebLabel("Jabber ID:"));
        LabelTextField jidField =
                new LabelTextField(View.MAX_JID_LENGTH, 20, this) {
            @Override
            protected String labelText() {
                return Utils.jid(mContact.getJID(), View.PRETTY_JID_LENGTH);
            }
            @Override
            protected String editText() {
                return mContact.getJID().string();
            }
            @Override
            protected void onFocusLost() {
                ContactDetails.this.saveJID(JID.bare(this.getText().trim()));
            }
        };
        String jidText = Tr.tr("The unique address of this contact");
        TooltipManager.addTooltip(jidField, jidText);
        mainPanel.add(jidField);

        mainPanel.add(new WebLabel(Tr.tr("Authorization:")));
        mSubscrStatus = new WebLabel();
        String subscrText = Tr.tr("Permission to view presence status and public key");
        TooltipManager.addTooltip(mSubscrStatus, subscrText);

        mSubscrButton = new WebButton(Tr.tr("Request"));
        String reqText = Tr.tr("Request status authorization from contact");
        TooltipManager.addTooltip(mSubscrButton, reqText);
        mSubscrButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                mView.getControl().sendSubscriptionRequest(mContact);
            }
        });
        mainPanel.add(new GroupPanel(GroupingType.fillFirst,
                View.GAP_DEFAULT, mSubscrStatus, mSubscrButton));

        groupPanel.add(mainPanel);

        groupPanel.add(new WebSeparator(true, true));

        WebPanel keyPanel = new WebPanel(new FormLayout(View.GAP_DEFAULT, View.GAP_DEFAULT));

        keyPanel.add(new WebLabel(Tr.tr("Public Key")+":"));
        mKeyStatus = new WebLabel();
        mUpdateButton = new WebButton(Utils.getIcon("ic_ui_reload.png"));
        String updText = Tr.tr("Update key");
        TooltipManager.addTooltip(mUpdateButton, updText);
        mUpdateButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                mView.getControl().requestKey(ContactDetails.this.mContact);
            }
        });
        keyPanel.add(new GroupPanel(GroupingType.fillFirst,
                View.GAP_DEFAULT, mKeyStatus, mUpdateButton));

        mFPLabel = new WebLabel(Tr.tr("Fingerprint:"));
        keyPanel.add(mFPLabel);
        mFPArea = Utils.createFingerprintArea();
        String fpText = Tr.tr("The unique ID of this contact's key");
        TooltipManager.addTooltip(mFPArea, fpText);
        mFPLabel.setAlignmentY(Component.TOP_ALIGNMENT);
        keyPanel.add(mFPArea);

        // set everything that can change
        this.updateOnEDT();

        groupPanel.add(keyPanel);

        mEncryptionBox = new WebCheckBox(Tr.tr("Use Encryption"));
        mEncryptionBox.setAnimated(false);
        mEncryptionBox.setSelected(mContact.getEncrypted());
        String encText = Tr.tr("Encrypt and sign all messages send to this contact");
        TooltipManager.addTooltip(mEncryptionBox, encText);
        mEncryptionBox.addChangeListener(new ChangeListener() {
            @Override
            public void stateChanged(ChangeEvent e) {
                mContact.setEncrypted(mEncryptionBox.isSelected());
            }
        });
        groupPanel.add(new GroupPanel(mEncryptionBox, Box.createGlue()));

        this.add(groupPanel, BorderLayout.WEST);

        WebPanel gradientPanel = new WebPanel(false) {
             @Override
            public void paintComponent(Graphics g) {
                super.paintComponent(g);
                int w = this.getWidth();
                int h = this.getHeight();
                BufferedImage mCached = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                Graphics2D cachedG = mCached.createGraphics();
                GradientPaint p2 = new GradientPaint(
                        0, 0, this.getBackground(),
                        w, 0, Color.LIGHT_GRAY);
                cachedG.setPaint(p2);
                cachedG.fillRect(0, 0, w, h);
                g.drawImage(mCached, 0, 0, this.getWidth(), this.getHeight(), null);
            }
        };
        this.add(gradientPanel, BorderLayout.CENTER);
    }

    void setRenameFocus() {
        mNameField.requestFocusInWindow();
    }

    @Override
    public void update(Observable o, final Object arg) {
        if (SwingUtilities.isEventDispatchThread()) {
            this.updateOnEDT();
            return;
        }
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                ContactDetails.this.updateOnEDT();
            }
        });
    }

    private void updateOnEDT() {
        // may have changed: avatar...
        mAvatarImage.update();
        // ...contact name...
        mNameField.setText(mContact.getName());
        mNameField.setInputPrompt(mContact.getName());
        Contact.Subscription subscription = mContact.getSubScription();
        String auth = Tr.tr("Unknown");
        switch(subscription) {
            case PENDING: auth = Tr.tr("Awaiting reply"); break;
            case SUBSCRIBED: auth = Tr.tr("Authorized"); break;
            case UNSUBSCRIBED: auth = Tr.tr("Not authorized"); break;
        }
        // ...subscription...
        mSubscrButton.setVisible(subscription != Contact.Subscription.SUBSCRIBED);
        mSubscrButton.setEnabled(subscription == Contact.Subscription.UNSUBSCRIBED);
        mSubscrStatus.setText(auth);
        // ...and/or key
        String hasKey = "<html>";
        if (mContact.hasKey()) {
            hasKey += Tr.tr("Available")+"</html>";
            TooltipManager.removeTooltips(mKeyStatus);
            mFPArea.setText(Utils.fingerprint(mContact.getFingerprint()));
            mFPLabel.setVisible(true);
            mFPArea.setVisible(true);
        } else {
            hasKey += "<font color='red'>"+Tr.tr("Not Available")+"</font></html>";
            String keyText = Tr.tr("The key for this contact could not yet be received");
            TooltipManager.addTooltip(mKeyStatus, keyText);
            mFPLabel.setVisible(false);
            mFPArea.setVisible(false);
        }
        mKeyStatus.setText(hasKey);
        mUpdateButton.setEnabled(mContact.isKontalkUser() &&
                subscription == Contact.Subscription.SUBSCRIBED);
    }

    private void saveName(String name) {
        if (name.equals(mContact.getName()))
            return;

        mView.getControl().changeName(mContact, name);
    }

    private void saveJID(JID jid) {
        if (!jid.isValid() || jid.equals(mContact.getJID()))
            // TODO feedback for invalid jid
            return;

        String warningText =
                Tr.tr("Changing the JID is only useful in very rare cases. Are you sure?");
        int selectedOption = WebOptionPane.showConfirmDialog(this,
                warningText,
                Tr.tr("Please Confirm"),
                WebOptionPane.OK_CANCEL_OPTION,
                WebOptionPane.WARNING_MESSAGE);
        if (selectedOption == WebOptionPane.OK_OPTION)
            mView.getControl().changeJID(mContact, jid);
    }

    static ContactDetails instance(View view, Contact contact) {
        if (!CACHE.containsKey(contact)) {
            ContactDetails newContactDetails = new ContactDetails(view, contact);
            contact.addObserver(newContactDetails);
            CACHE.put(contact, newContactDetails);
        }

        return CACHE.get(contact);
    }
}
