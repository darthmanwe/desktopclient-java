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

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.GridBagLayout;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;

import com.alee.laf.label.WebLabel;
import com.alee.laf.panel.WebPanel;
import org.kontalk.model.Contact;
import org.kontalk.model.chat.Chat;

/**
 * Content view area: show a chat or contact details
 * @author Alexander Bikadorov {@literal <bikaejkb@mail.tu-berlin.de>}
 */
final class Content extends WebPanel {

    private final View mView;
    private final ChatView mChatView;
    private Component mCurrent;

    Content(View view, ChatView chatView) {
        mView = view;
        mChatView = chatView;

        this.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                // maybe not visible, i don't care
                mChatView.requestFocusInWindow();
            }
        });

        this.show(mChatView);
    }

    void showChat(Chat chat) {
        mChatView.showChat(chat);
        if (mCurrent != mChatView)
            this.show(mChatView);
    }

    void showContact(Contact contact) {
        this.show(ContactDetails.instance(mView, contact));
    }

    void showNothing() {
        WebPanel nothing = new WebPanel();
        WebPanel topPanel = new WebPanel(new GridBagLayout());
        topPanel.setMargin(40);
        topPanel.add(new WebLabel(Utils.getIcon("kontalk-big.png")));
        nothing.add(topPanel, BorderLayout.NORTH);
        this.show(nothing);
    }

    private void show(Component comp) {
        // Swing...
        this.removeAll();
        this.add(comp, BorderLayout.CENTER);
        this.revalidate();
        this.repaint();

        mCurrent = comp;
    }

    void requestRenameFocus() {
        if (mCurrent instanceof ContactDetails) {
            ((ContactDetails) mCurrent).setRenameFocus();
        }
    }
}
