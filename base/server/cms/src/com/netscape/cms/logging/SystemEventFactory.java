// --- BEGIN COPYRIGHT BLOCK ---
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; version 2 of the License.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License along
// with this program; if not, write to the Free Software Foundation, Inc.,
// 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
//
// (C) 2007 Red Hat, Inc.
// All rights reserved.
// --- END COPYRIGHT BLOCK ---
package com.netscape.cms.logging;

import java.util.Properties;

import com.netscape.certsrv.logging.ILogEvent;
import com.netscape.certsrv.logging.ILogger;
import com.netscape.certsrv.logging.LogCategory;
import com.netscape.certsrv.logging.LogSource;
import com.netscape.certsrv.logging.SystemEvent;

/**
 * A log event object for handling system messages
 * <P>
 *
 * @author mikep
 * @author mzhao
 * @version $Revision$, $Date$
 */
public class SystemEventFactory extends LogFactory {

    /**
     * Constructs a system event factory.
     */
    public SystemEventFactory() {
    }

    /**
     * Creates an log event.
     *
     * @param evtClass the event type
     * @param prop the resource bundle
     * @param source the subsystem ID who creates the log event
     * @param level the severity of the log event
     * @param multiline the log message has more than one line or not
     * @param msg the detail message of the log
     * @param params the parameters in the detail log message
     */
    public ILogEvent create(LogCategory evtClass, Properties prop, LogSource source,
            int level, boolean multiline, String msg, Object params[]) {
        if (evtClass != ILogger.EV_SYSTEM)
            return null;
        SystemEvent event = new SystemEvent(msg, params);

        event.setLevel(level);
        event.setSource(source);
        event.setMultiline(multiline);
        setProperties(prop, event);
        return event;
    }
}
