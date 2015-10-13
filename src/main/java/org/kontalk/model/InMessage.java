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

package org.kontalk.model;

import org.kontalk.misc.JID;
import java.util.Date;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Optional;
import java.util.logging.Logger;
import org.kontalk.crypto.Coder;
import org.kontalk.model.MessageContent.Attachment;
import org.kontalk.model.MessageContent.Preview;

/**
 * Model for a XMPP message that was sent to us.
 * @author Alexander Bikadorov {@literal <bikaejkb@mail.tu-berlin.de>}
 */
public final class InMessage extends KonMessage {
    private static final Logger LOGGER = Logger.getLogger(InMessage.class.getName());

    /**
     * Create a new incoming message from builder.
     * The message is not saved to database!
     */
    InMessage(KonMessage.Builder builder) {
        super(builder);
    }

    public Contact getContact() {
        assert mTransmissions.length == 1;
        return mTransmissions[0].getContact();
    }

    public JID getJID() {
        assert mTransmissions.length == 1;
        return mTransmissions[0].getJID();
    }

    public void setSigning(Coder.Signing signing) {
        mCoderStatus.setSigning(signing);
        this.save();
    }

    public void setDecryptedContent(MessageContent decryptedContent) {
        mContent.setDecryptedContent(decryptedContent);
        mCoderStatus.setDecrypted();
        this.save();
        this.changed(decryptedContent);
    }

    public void setAttachmentFileName(String fileName) {
        Attachment attachment = this.getAttachment();
        if (attachment == null)
            return;

        attachment.setFile(fileName);
        this.save();
        // only tell view if file not encrypted
        if (!attachment.getCoderStatus().isEncrypted())
            this.changed(attachment);
     }

    public void setAttachmentSigning(Coder.Signing signing) {
        Attachment attachment = this.getAttachment();
        if (attachment == null)
            return;

        attachment.getCoderStatus().setSigning(signing);
        this.save();
    }

    public void setAttachmentDownloadProgress(int p) {
        Attachment attachment = this.getAttachment();
        if (attachment == null)
            return;

        attachment.setDownloadProgress(p);
        if (p <= 0)
            this.changed(attachment);
    }

    public void setDecryptedAttachment(String filename) {
        Attachment attachment = this.getAttachment();
        if (attachment == null)
            return;

        attachment.setDecryptedFile(filename);
        this.save();
        this.changed(attachment);
    }

    public void setPreviewFilename(String filename) {
        Optional<Preview> optPreview = this.getContent().getPreview();
        if (!optPreview.isPresent()) {
            LOGGER.warning("no preview !?");
            return;
        }
        optPreview.get().setFilename(filename);
        this.save();
        this.changed(optPreview.get());
    }

    public static class Builder extends KonMessage.Builder {

        public Builder(Chat chat, Contact contact, JID from) {
            super(-1, chat, Status.IN, new Date());

            mContacts = new HashMap<>();
            mContacts.put(contact, from);
        }

        @Override
        public void content(MessageContent content) {
            super.content(content);

            boolean encrypted = !content.getEncryptedContent().isEmpty();

            mCoderStatus = new CoderStatus(
                // no decryption attempt yet
                encrypted ? Coder.Encryption.ENCRYPTED : Coder.Encryption.NOT,
                // if encrypted we don't know yet
                encrypted ? Coder.Signing.UNKNOWN : Coder.Signing.NOT,
                // no errors
                EnumSet.noneOf(Coder.Error.class)
            );
        }

        @Override
        public void coderStatus(CoderStatus c) { throw new UnsupportedOperationException(); }

        @Override
        public InMessage build() {
            return new InMessage(this);
        }
    }
}
