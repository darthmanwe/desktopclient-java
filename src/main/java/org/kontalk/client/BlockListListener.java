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

package org.kontalk.client;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import org.jivesoftware.smack.StanzaListener;
import org.jivesoftware.smack.packet.Stanza;
import org.jivesoftware.smack.provider.ProviderManager;
import org.kontalk.misc.JID;
import org.kontalk.system.Control;

/**
 *
 * @author Alexander Bikadorov {@literal <bikaejkb@mail.tu-berlin.de>}
 */
final class BlockListListener implements StanzaListener {
    private static final Logger LOGGER = Logger.getLogger(BlockListListener.class.getName());

    private final Control mControl;

    static {
        ProviderManager.addIQProvider(BlockingCommand.BLOCKLIST,
                BlockingCommand.NAMESPACE,
                new BlockingCommand.Provider());
    }

    BlockListListener(Control control) {
        mControl = control;
    }

    @Override
    public void processStanza(Stanza packet) {
        BlockingCommand p = (BlockingCommand) packet;
        LOGGER.config("blocking command: "+p);

        List<String> items = p.getItems();
        if (items != null) {
            List<JID> jids = new ArrayList<>(items.size());
            for (String s : items)
                jids.add(JID.full(s));
            mControl.onBlockList(jids.toArray(new JID[0]));
        }
    }
}
