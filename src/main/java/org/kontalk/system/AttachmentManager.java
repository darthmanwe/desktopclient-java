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

package org.kontalk.system;

import static org.kontalk.util.MediaUtils.isImage;

import java.awt.Dimension;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.kontalk.client.Client;
import org.kontalk.client.HTTPFileClient;
import org.kontalk.crypto.Coder;
import org.kontalk.crypto.Coder.Encryption;
import org.kontalk.crypto.PGPUtils;
import org.kontalk.crypto.PersonalKey;
import org.kontalk.misc.KonException;
import org.kontalk.model.message.InMessage;
import org.kontalk.model.message.KonMessage;
import org.kontalk.model.message.MessageContent.Attachment;
import org.kontalk.model.message.MessageContent.InAttachment;
import org.kontalk.model.message.MessageContent.OutAttachment;
import org.kontalk.model.message.MessageContent.Preview;
import org.kontalk.model.message.OutMessage;
import org.kontalk.persistence.Config;
import org.kontalk.util.MediaUtils;

/**
 * Up- and download service for attachment files.
 *
 * Also takes care of de- and encrypting attachments.
 *
 * @author Alexander Bikadorov {@literal <bikaejkb@mail.tu-berlin.de>}
 */
public class AttachmentManager implements Runnable {
    private static final Logger LOGGER = Logger.getLogger(AttachmentManager.class.getName());

    public static final String ATT_DIRNAME = "attachments";
    public static final String PREVIEW_DIRNAME = "preview";

    private static final String RESIZED_IMG_MIME = "image/jpeg";
    private static final String THUMBNAIL_MIME = "image/jpeg";

    public static final Dimension THUMBNAIL_DIM = new Dimension(300, 200);
    public static final String ENCRYPT_PREFIX = "encrypted_";
    public static final int MAX_ATT_SIZE = 20 * 1024 * 1024;

    public static class Slot {
        final URI uploadURL;
        final URI downloadURL;

        public Slot() {
            this(URI.create(""), URI.create(""));
        }

        public Slot(URI uploadURI, URI downloadURL) {
            this.uploadURL = uploadURI;
            this.downloadURL = downloadURL;
        }
    }

    //private static final String ENCRYPT_MIME = "application/octet-stream";

    private final Control mControl;
    private final Client mClient;

    private final LinkedBlockingQueue<Task> mQueue = new LinkedBlockingQueue<>();
    private final Path mAttachmentDir;
    private final Path mPreviewDir;

    private static class Task {

        private Task() {}

        static final class UploadTask extends Task {
            final OutMessage message;

            UploadTask(OutMessage message) {
                this.message = message;
            }
        }

        static final class DownloadTask extends Task {
            final InMessage message;

            DownloadTask(InMessage message) {
                this.message = message;
            }
        }
    }

    private AttachmentManager(Control control, Client client, Path baseDir) {
        mControl = control;
        mClient = client;
        mAttachmentDir = baseDir.resolve(ATT_DIRNAME);
        if (mAttachmentDir.toFile().mkdir())
            LOGGER.info("created attachment directory");

        mPreviewDir = baseDir.resolve(PREVIEW_DIRNAME);
        if (mPreviewDir.toFile().mkdir())
            LOGGER.info("created preview directory");
    }

    static AttachmentManager create(Control control, Client client, Path appDir) {
        AttachmentManager manager = new AttachmentManager(control, client, appDir);

        Thread thread = new Thread(manager, "Attachment Transfer");
        thread.setDaemon(true);
        thread.start();

        return manager;
    }

    void queueUpload(OutMessage message) {
        boolean added = mQueue.offer(new Task.UploadTask(message));
        if (!added) {
            LOGGER.warning("can't add upload message to queue");
        }
    }

    void queueDownload(InMessage message) {
        boolean added = mQueue.offer(new Task.DownloadTask(message));
        if (!added) {
            LOGGER.warning("can't add download message to queue");
        }
    }

    private void uploadAsync(OutMessage message) {
        OutAttachment attachment = message.getContent().getOutAttachment().orElse(null);
        if (attachment == null) {
            LOGGER.warning("no attachment in message to upload");
            return;
        }

        if (!mClient.isConnected()) {
            LOGGER.info("can't upload, not connected");
            return;
        }

        File original;
        File file = original = attachment.getFilePath().toFile();
        String uploadName;
        try {
            uploadName = URLEncoder.encode(file.getName(), "UTF-8");
        } catch (UnsupportedEncodingException ex) {
            LOGGER.log(Level.WARNING, "can't encode file name", ex);
            return;
        }
        String mime = attachment.getMimeType();

        // maybe resize image for smaller payload
        if(isImage(mime)) {
            int maxImgSize = Config.getInstance().getInt(Config.NET_MAX_IMG_SIZE);
            if (maxImgSize > 0) {
                BufferedImage img = MediaUtils.readImage(file).orElse(null);
                if (img == null) {
                    LOGGER.warning("can't load image");
                    return;
                }
                if (img.getWidth() * img.getHeight() > maxImgSize) {
                    // image needs to be resized
                    BufferedImage resized = MediaUtils.scale(img, maxImgSize);
                    try {
                        file = File.createTempFile("kontalk_resized_img_att", ".dat");
                    } catch (IOException ex) {
                        LOGGER.log(Level.WARNING, "can't create temporary file", ex);
                        return;
                    }
                    mime = RESIZED_IMG_MIME;
                    boolean succ = MediaUtils.writeImage(resized,
                            MediaUtils.extensionForMIME(mime),
                            file);
                    if (!succ)
                        return;
                }
            }
        }

        // if text will be encrypted, always encrypt attachment too
        boolean encrypt = message.getCoderStatus().getEncryption() == Encryption.DECRYPTED;
        if (encrypt) {
            PersonalKey myKey = mControl.myKey().orElse(null);
            File encryptFile = myKey == null ?
                    null :
                    Coder.encryptAttachment(myKey, message, file).orElse(null);
            if (!file.equals(original))
                delete(file);
            if (encryptFile == null)
                return;
            file = encryptFile;
            // Note: continue using original MIME type, Android client needs it
            //mime = ENCRYPT_MIME;
        }

        HTTPFileClient client = this.clientOrNull();
        if (client == null)
            return;

        long length = file.length();
        Slot uploadSlot = mClient.getUploadSlot(uploadName, length, mime);
        if (uploadSlot.uploadURL.toString().isEmpty() ||
                uploadSlot.downloadURL.toString().isEmpty()) {
            LOGGER.warning("empty slot: "+attachment);
            return;
        }

        try {
            client.upload(file, uploadSlot.uploadURL, mime, encrypt);
        } catch (KonException ex) {
            LOGGER.warning("upload failed, attachment: "+attachment);
            message.setStatus(KonMessage.Status.ERROR);
            mControl.onException(ex);
            return;
        }

        if (!file.equals(original))
            delete(file);

        attachment.setUploaded(uploadSlot.downloadURL, mime, length);

        LOGGER.info("upload successful, URL="+uploadSlot.downloadURL);

        // make sure not to loop
        if (attachment.hasURL())
            mControl.sendMessage(message);
    }

    private void downloadAsync(final InMessage message) {
        InAttachment attachment = message.getContent().getInAttachment().orElse(null);
        if (attachment == null) {
            LOGGER.warning("no attachment in message to download");
            return;
        }

        HTTPFileClient client = this.clientOrNull();
        if (client == null)
            return;

        HTTPFileClient.ProgressListener listener = new HTTPFileClient.ProgressListener() {
            @Override
            public void updateProgress(int p) {
                attachment.setDownloadProgress(p);
            }
        };

        Path path;
        try {
            path = client.download(attachment.getURL(), mAttachmentDir, listener);
        } catch (KonException ex) {
            LOGGER.warning("download failed, URL="+attachment.getURL());
            mControl.onException(ex);
            return;
        }

        if (path.toString().isEmpty()) {
            LOGGER.warning("file path is empty");
            return;
        }

        boolean encrypted = PGPUtils.isEncryptedFile(path);
        if (encrypted) {
            path = MediaUtils.renameFile(path,
                    AttachmentManager.ENCRYPT_PREFIX + path.getFileName().toString());
        }

        LOGGER.info("successful, saved to file: "+path);
        attachment.setFile(path.getFileName().toString(), encrypted);

        if (encrypted) {
            // decrypt file
            mControl.myKey().ifPresent(mk ->
                    Coder.decryptAttachment(mk, attachment, message.getContact()));
        }

        // create preview if not in message
        if (!message.getContent().getPreview().isPresent())
            this.mayCreateImagePreview(message);
    }

    void savePreview(Preview preview, int messageID) {
        this.writePreview(preview.getData(), messageID, preview.getMimeType());
    }

    void mayCreateImagePreview(KonMessage message) {
        Attachment att = message.getContent().getAttachment().orElse(null);
        if (att == null) {
            LOGGER.warning("no attachment in message: "+message);
            return;
        }
        Path path = att.getFilePath();

        String mime = StringUtils.defaultIfEmpty(att.getMimeType(),
                // guess from file
                MediaUtils.mimeForFile(path));

        if (!isImage(mime))
            return;

        BufferedImage image = MediaUtils.readImage(path);
        // the attachment image could be smaller than the thumbnail - nobody cares
//        if (image.getWidth() <= THUMBNAIL_DIM.width && image.getHeight() <= THUMBNAIL_DIM.height)
//           return;

        Image thumb = MediaUtils.scaleAsync(image,
                THUMBNAIL_DIM.width ,
                THUMBNAIL_DIM.height);

        String format = MediaUtils.extensionForMIME(THUMBNAIL_MIME);

        byte[] bytes = MediaUtils.imageToByteArray(thumb, format);
        if (bytes.length <= 0)
            return;

        this.writePreview(bytes, message.getID(), THUMBNAIL_MIME);
        Preview preview = new Preview(bytes, THUMBNAIL_MIME);
        LOGGER.info("created: "+preview);

        message.setPreview(preview);
    }

    Path getAttachmentDir() {
        return mAttachmentDir;
    }

    private void writePreview(byte[] data, int messageID, String mimeType) {
        String filename = previewFilename(messageID, mimeType);

        File newFile = mPreviewDir.resolve(filename).toFile();
        try {
            FileUtils.writeByteArrayToFile(newFile, data);
        } catch (IOException ex) {
            LOGGER.log(Level.WARNING, "can't save preview file", ex);
            return;
        }

        LOGGER.config("to file: "+newFile);
    }

    public static String previewFilename(int messageID, String mimeType) {
        return Integer.toString(messageID) + "_bob." + MediaUtils.extensionForMIME(mimeType);
    }

    private HTTPFileClient clientOrNull(){
        PersonalKey key = mControl.myKey().orElse(null);
        if (key == null)
            return null;

        return new HTTPFileClient(key.getServerLoginKey(),
                key.getBridgeCertificate(),
                Config.getInstance().getBoolean(Config.SERV_CERT_VALIDATION));
    }

    @Override
    public void run() {
        while (true) {
            Task t;
            try {
                // blocking
                t = mQueue.take();
            } catch (InterruptedException ex) {
                LOGGER.log(Level.WARNING, "interrupted while waiting ", ex);
                return;
            }
            if (t instanceof Task.UploadTask) {
                this.uploadAsync(((Task.UploadTask) t).message);
            } else if (t instanceof Task.DownloadTask) {
                this.downloadAsync(((Task.DownloadTask) t).message);
            }
        }
    }

    /**
     * Create a new attachment for a given file denoted by its path.
     */
    static OutAttachment createAttachmentOrNull(Path path) {
        if (!Files.isReadable(path)) {
            LOGGER.warning("file not readable: "+path);
            return null;
        }

        String mimeType = MediaUtils.mimeForFile(path);
        if (mimeType.isEmpty()) {
            LOGGER.warning("no mime type for file: "+path);
            return null;
        }

        return new OutAttachment(path, mimeType);
    }

    private static void delete(File f) {
        if (!f.delete()) {
            LOGGER.warning("can not delete file: " + f);
        }
    }
}
