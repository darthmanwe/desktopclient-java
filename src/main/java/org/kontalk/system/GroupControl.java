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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.kontalk.misc.JID;
import org.kontalk.model.Contact;
import org.kontalk.model.Model;
import org.kontalk.model.chat.GroupChat;
import org.kontalk.model.chat.GroupChat.KonGroupChat;
import org.kontalk.model.chat.GroupMetaData;
import org.kontalk.model.chat.GroupMetaData.KonGroupData;
import org.kontalk.model.chat.Member;
import org.kontalk.model.chat.ProtoMember;
import org.kontalk.model.message.MessageContent;
import org.kontalk.model.message.MessageContent.GroupCommand;
import org.kontalk.util.EncodingUtils;

/**
 * Control logic for group chat management.
 *
 * @author Alexander Bikadorov {@literal <bikaejkb@mail.tu-berlin.de>}
 */
final class GroupControl {
    private static final Logger LOGGER = Logger.getLogger(GroupControl.class.getName());

    private final Control mControl;
    private final Model mModel;

    GroupControl(Control control, Model model) {
        mControl = control;
        mModel = model;
    }

    abstract class ChatControl<C extends GroupChat> {

        final C mChat;

        private ChatControl(C chat) {
            mChat = chat;
        }

        abstract void onCreate();

        abstract void onSetSubject(String subject);

        abstract void onLeave();

        abstract boolean beforeDelete();

        //
        abstract void onInMessage(GroupCommand command, Contact sender);
    }

    final class KonChatControl extends ChatControl<KonGroupChat> {

        private KonChatControl(KonGroupChat chat) {
            super(chat);
        }

        @Override
        void onCreate() {
            // send create group command
            List<JID> jids = mChat.getValidContacts().stream()
                    .map(Contact::getJID)
                    .collect(Collectors.toList());

            mControl.createAndSendMessage(mChat,
                    MessageContent.groupCommand(
                            MessageContent.GroupCommand.create(
                                    jids,
                                    mChat.getSubject())
                    )
            );
        }

        @Override
        public void onSetSubject(String subject) {
            if (!mChat.isAdministratable()) {
                LOGGER.warning("not admin");
                return;
            }

            GroupCommand command = GroupCommand.set(subject);
            mControl.createAndSendMessage(mChat,
                    MessageContent.groupCommand(command));
            applyMyCommand(command);
        }

        // NOTE: after we left, group members are actually unknown cause we
        // don't get any change notifications anymmore.
        @Override
        void onLeave() {
            GroupCommand command = GroupCommand.leave();
            mControl.createAndSendMessage(mChat,
                    MessageContent.groupCommand(command));

            // NOTE: ignoring if message was sent/received or not
            applyMyCommand(command);
        }

        @Override
        public boolean beforeDelete() {
            if (!mChat.isValid())
                return true;
            // TODO if encryption is forced for one member but there is no key,
            // chat cannot be deleted

            GroupCommand command = GroupCommand.leave();
            // NOTE: group chats are not deleted remotely, were just leaving them
            boolean succ = mControl.createAndSendMessage(mChat,
                    MessageContent.groupCommand(command));
            if (!succ) {
                // message wasn't send (e.g. not connected), apply leave and
                // TODO delete chat when command was sent
                applyMyCommand(command);
            }
            return succ;
        }

        private void applyMyCommand(GroupCommand command) {
            Contact me = mModel.contacts().getMe().orElse(null);
            if (me == null) {
                LOGGER.warning("no me");
                return;
            }
            onInMessage(command, me);
        }

        @Override
        void onInMessage(GroupCommand command, Contact sender) {
            // TODO ignore message if it contains unexpected group command (?)

            // NOTE: chat was selected/created by GID so we can be sure message
            // and chat GIDs match
            KonGroupData gid = mChat.getGroupData();
            MessageContent.GroupCommand.OP op = command.getOperation();

            // validation check
            if (op != GroupCommand.OP.LEAVE) {
                // sender must be owner
                if (!gid.owner.equals(sender.getJID())) {
                    LOGGER.warning("sender not owner");
                    return;
                }
            }

            // apply group command
            List<ProtoMember> added = new ArrayList<>();
            List<ProtoMember> removed = new ArrayList<>();
            String subject = "";

            switch(command.getOperation()) {
                case CREATE:
                    //assert mMemberSet.size() == 1;
                    //assert mMemberSet.contains(new Member(sender));
                    added.addAll(JIDsToMembers(command.getAdded()));
                    if (added.stream().noneMatch(m -> m.getContact().isMe()))
                        LOGGER.warning("user not included in new chat");

                    subject = command.getSubject();
                    break;
                case LEAVE:
                    removed.add(new ProtoMember(sender));
                    break;
                case SET:
                    added.addAll(JIDsToMembers(command.getAdded()));
                    if (command.isAddingMe())
                        added.addAll(JIDsToMembers(command.getUnchanged()));
                    for (JID jid : command.getRemoved()) {
                        Contact contact = mModel.contacts().get(jid).orElse(null);
                        if (contact == null) {
                            LOGGER.warning("can't get removed contact, jid="+jid);
                            continue;
                        }
                        removed.add(new ProtoMember(contact));
                    }
                    subject = command.getSubject();
                    break;
                default:
                    LOGGER.warning("unhandled operation: "+command.getOperation());
            }

            mChat.applyGroupChanges(added, removed, subject);
        }
    }

    private List<ProtoMember> JIDsToMembers(List<JID> jids) {
        List<ProtoMember> members = new ArrayList<>();
        for (JID jid: jids) {
            // add contacts if necessary
            // TODO design problem here: we need at least the public keys, but user
            // might dont wanna have group members in contact list
            Contact contact = mControl.getOrCreateContact(jid).orElse(null);
            if (contact == null) {
                LOGGER.warning("can't get contact, jid: "+jid);
                continue;
            }
            members.add(new ProtoMember(contact));
        }
        return members;
    }

    ChatControl getInstanceFor(GroupChat chat) {
        if (chat instanceof KonGroupChat)
            return new KonChatControl((KonGroupChat) chat);
        throw new IllegalArgumentException("Not implemented for " + chat);
    }

    Optional<GroupChat> getGroupChat(GroupMetaData metaData, Contact sender,
                                     Optional<GroupCommand> command) {
        // get old...
        GroupChat chat = mModel.chats().get(metaData).orElse(null);
        if (chat != null) {
            if (!chat.getAllContacts().contains(sender)) {
                LOGGER.warning("chat does not include sender: "+chat);
                // TODO we should ask owner to confirm member list
                return Optional.empty();
            }
            return Optional.of(chat);
        }

        // ...or create new
        if (!(metaData instanceof KonGroupData)) {
            return Optional.empty(); // creation only for Kontalk Groups
        }
        KonGroupData konData = (KonGroupData) metaData;

        if (!konData.owner.equals(sender.getJID())) {
            LOGGER.warning("sender is not owner for new group chat: "+metaData);
            return Optional.empty();
        }

        if (!command.isPresent() || !command.get().isAddingMe()) {
            LOGGER.warning("ignoring unexpected message of unknown group");
            return Optional.empty();
        }

        return Optional.of(
                mModel.chats().create(
                        Collections.singletonList(new ProtoMember(sender, Member.Role.OWNER)),
                        metaData));
    }

    static KonGroupData newKonGroupData(JID myJID) {
        return new KonGroupData(myJID, EncodingUtils.randomString(8));
    }
}
