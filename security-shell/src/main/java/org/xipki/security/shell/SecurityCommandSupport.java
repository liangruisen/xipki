/*
 *
 * Copyright (c) 2013 - 2017 Lijun Liao
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License version 3
 * as published by the Free Software Foundation with the addition of the
 * following permission added to Section 15 as permitted in Section 7(a):
 *
 * FOR ANY PART OF THE COVERED WORK IN WHICH THE COPYRIGHT IS OWNED BY
 * THE AUTHOR LIJUN LIAO. LIJUN LIAO DISCLAIMS THE WARRANTY OF NON INFRINGEMENT
 * OF THIRD PARTY RIGHTS.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 *
 * The interactive user interfaces in modified source and object code versions
 * of this program must display Appropriate Legal Notices, as required under
 * Section 5 of the GNU Affero General Public License.
 *
 * You can be released from the requirements of the license by purchasing
 * a commercial license. Buying such a license is mandatory as soon as you
 * develop commercial activities involving the XiPKI software without
 * disclosing the source code of your own applications.
 *
 * For more information, please contact Lijun Liao at this
 * address: lijun.liao@gmail.com
 */

package org.xipki.security.shell;

import java.util.Date;

import org.apache.karaf.shell.api.action.lifecycle.Reference;
import org.xipki.common.util.DateUtil;
import org.xipki.console.karaf.IllegalCmdParamException;
import org.xipki.console.karaf.XipkiCommandSupport;
import org.xipki.security.SecurityFactory;
import org.xipki.security.exception.P11TokenException;
import org.xipki.security.exception.XiSecurityException;
import org.xipki.security.pkcs11.P11CryptService;
import org.xipki.security.pkcs11.P11CryptServiceFactory;
import org.xipki.security.pkcs11.P11Module;
import org.xipki.security.pkcs11.P11Slot;
import org.xipki.security.pkcs11.P11SlotIdentifier;

/**
 * @author Lijun Liao
 * @since 2.0.0
 */

public abstract class SecurityCommandSupport extends XipkiCommandSupport {

    protected static final String DEFAULT_P11MODULE_NAME =
            P11CryptServiceFactory.DEFAULT_P11MODULE_NAME;

    @Reference
    protected SecurityFactory securityFactory;

    @Reference (optional = true)
    protected P11CryptServiceFactory p11CryptServiceFactory;

    protected P11Slot getSlot(final String moduleName, final int slotIndex)
            throws XiSecurityException, P11TokenException, IllegalCmdParamException {
        P11Module module = getP11Module(moduleName);
        P11SlotIdentifier slotId = module.getSlotIdForIndex(slotIndex);
        return module.getSlot(slotId);
    }

    protected P11Module getP11Module(final String moduleName)
            throws XiSecurityException, P11TokenException, IllegalCmdParamException {
        P11CryptService p11Service = p11CryptServiceFactory.getP11CryptService(moduleName);
        if (p11Service == null) {
            throw new IllegalCmdParamException("undefined module " + moduleName);
        }
        return p11Service.getModule();
    }

    protected String toUtcTimeyyyyMMddhhmmssZ(final Date date) {
        return DateUtil.toUtcTimeyyyyMMddhhmmss(date) + "Z";
    }

}