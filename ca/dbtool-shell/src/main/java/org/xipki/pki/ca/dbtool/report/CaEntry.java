/*
 *
 * This file is part of the XiPKI project.
 * Copyright (c) 2014 - 2015 Lijun Liao
 * Author: Lijun Liao
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License version 3
 * as published by the Free Software Foundation with the addition of the
 * following permission added to Section 15 as permitted in Section 7(a):
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
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
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

package org.xipki.pki.ca.dbtool.report;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import org.xipki.common.util.IoUtil;
import org.xipki.common.util.ParamUtil;
import org.xipki.pki.ca.dbtool.DbToolBase;
import org.xipki.pki.ca.dbtool.xmlio.InvalidDataObjectException;

/**
 * @author Lijun Liao
 */

public class CaEntry
{
    public static final int DFLT_NUM_CERTS_IN_BUNDLE = 100000;
    public static final int STREAM_BUFFERSIZE = 1024 * 1024; // 1M

    private final int caId;
    private final FileOutputStream certsManifestOs;
    private final File caDir;
    private final File certsDir;

    private final int numProcessedBefore;
    private int numProcessed;

    private File csvFile;
    private BufferedOutputStream csvOutputStream;

    private int minIdInCsvFile;
    private int maxIdInCsvFile;
    private int numInCsvFile;

    public CaEntry(
            final int caId,
            final String caDir)
    throws IOException
    {
        ParamUtil.assertNotNull("caDir", caDir);

        this.caId = caId;
        this.caDir = new File(caDir);
        this.certsDir = new File(caDir, "certs");
        this.certsDir.mkdirs();

        this.certsManifestOs = new FileOutputStream(
                new File(caDir, "certs-manifest"), true);

        int _numProcessedBefore = 0;
        File accountFile = new File(caDir, "acount");
        if(accountFile.exists())
        {
            byte[] bytes = IoUtil.read(accountFile);
            if(bytes != null && bytes.length > 0)
            {
                _numProcessedBefore = Integer.parseInt(new String(bytes));
                if(_numProcessedBefore < 0)
                {
                    throw new IllegalArgumentException(
                            "content of file 'account' is invalid, it could not be negative");
                }
            }
        }

        this.numProcessedBefore = _numProcessedBefore;
        createNewCsvFile();
    }

    public int getCaId()
    {
        return caId;
    }

    public void addDigestEntry(DbDigestEntry reportEntry)
    throws IOException, InvalidDataObjectException
    {
        int id = reportEntry.getId();
        if(minIdInCsvFile == 0)
        {
            minIdInCsvFile = id;
        } else if(minIdInCsvFile > id)
        {
            minIdInCsvFile = id;
        }

        if(maxIdInCsvFile == 0)
        {
            maxIdInCsvFile = id;
        } else if(maxIdInCsvFile < id)
        {
            maxIdInCsvFile = id;
        }
        numInCsvFile++;

        csvOutputStream.write(reportEntry.getEncoded().getBytes());
        csvOutputStream.write('\n');

        if(numInCsvFile == DFLT_NUM_CERTS_IN_BUNDLE)
        {
            closeCurrentCsvFile();
            numInCsvFile = 0;
            minIdInCsvFile = 0;
            maxIdInCsvFile = 0;
            createNewCsvFile();
        }
        numProcessed++;
    }

    public void close()
    throws IOException
    {
        // write the account
        IoUtil.save(new File(caDir, "account"),
                Integer.toString(numProcessed + numProcessedBefore).getBytes());

        closeCurrentCsvFile();
        IoUtil.closeStream(certsManifestOs);
    }

    private void closeCurrentCsvFile()
    throws IOException
    {
        csvOutputStream.close();

        String zipFilename = DbToolBase.buildFilename("certs_", ".csv", minIdInCsvFile, maxIdInCsvFile, 100000000);
        csvFile.renameTo(new File(caDir, "certs" + File.separator + zipFilename));
        certsManifestOs.write((zipFilename + "\n").getBytes());
        certsManifestOs.flush();
    }

    private void createNewCsvFile()
    throws IOException
    {
        this.csvFile = new File(caDir.getParentFile(), "tmp-ca-" + caId + "-" + System.currentTimeMillis() + ".csv");
        csvOutputStream = new BufferedOutputStream(
                new FileOutputStream(this.csvFile), STREAM_BUFFERSIZE);
    }

}