/*
 *
 * Copyright (c) 2013 - 2019 Lijun Liao
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.xipki.ocsp.server.store;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.math.BigInteger;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.SignatureException;
import java.security.cert.CRLException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509CRL;
import java.security.cert.X509CRLEntry;
import java.security.cert.X509Certificate;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.text.ParseException;
import java.util.Arrays;
import java.util.Date;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import javax.security.auth.x500.X500Principal;

import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1GeneralizedTime;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.ASN1Set;
import org.bouncycastle.asn1.ASN1TaggedObject;
import org.bouncycastle.asn1.DERGeneralizedTime;
import org.bouncycastle.asn1.DERIA5String;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.DERSet;
import org.bouncycastle.asn1.DERTaggedObject;
import org.bouncycastle.asn1.DERUTF8String;
import org.bouncycastle.asn1.ocsp.CrlID;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Certificate;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.TBSCertificate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xipki.datasource.DataAccessException;
import org.xipki.datasource.DataSourceWrapper;
import org.xipki.security.CertRevocationInfo;
import org.xipki.security.CrlReason;
import org.xipki.security.HashAlgo;
import org.xipki.security.ObjectIdentifiers;
import org.xipki.security.util.X509Util;
import org.xipki.util.Base64;
import org.xipki.util.IoUtil;
import org.xipki.util.LogUtil;
import org.xipki.util.Args;
import org.xipki.util.StringUtil;

/**
 * TODO.
 * @author Lijun Liao
 * @since 2.2.0
 */

class ImportCrl {

  private static final Logger LOG = LoggerFactory.getLogger(ImportCrl.class);

  private static final String SQL_UPDATE_CERT_REV
      = "UPDATE CERT SET REV=?,RR=?,RT=?,RIT=?,LUPDATE=? WHERE ID=?";

  private static final String SQL_INSERT_CERT_REV
      = "INSERT INTO CERT (ID,IID,SN,REV,RR,RT,RIT,LUPDATE) VALUES(?,?,?,?,?,?,?,?)";

  private static final String SQL_DELETE_CERT = "DELETE FROM CERT WHERE IID=? AND SN=?";

  private static final String SQL_UPDATE_CERT
      = "UPDATE CERT SET LUPDATE=?,NBEFORE=?,NAFTER=?,HASH=? WHERE ID=?";

  private static final String SQL_INSERT_CERT
      = "INSERT INTO CERT (ID,IID,SN,REV,RR,RT,RIT,LUPDATE,NBEFORE,NAFTER,HASH) "
        + "VALUES(?,?,?,?,?,?,?,?,?,?,?)";

  private static final String CORE_SQL_SELECT_ID_CERT = "ID FROM CERT WHERE IID=? AND SN=?";

  private final String sqlSelectIdCert;

  private final X509CRL crl;

  private final X509Certificate caCert;

  private final BigInteger crlNumber;

  private final DataSourceWrapper datasource;

  // The CRL number of a DeltaCRL.
  private final BigInteger baseCrlNumber;

  private final boolean isDeltaCrl;

  private final CrlID crlId;

  private final X500Name caSubject;

  private final X500Principal x500PrincipalCaSubject;

  private final byte[] caSpki;

  private final String certsDirName;

  private final CertRevocationInfo caRevInfo;

  private final HashAlgo certhashAlgo;

  private PreparedStatement psDeleteCert;
  private PreparedStatement psInsertCert;
  private PreparedStatement psInsertCertRev;
  private PreparedStatement psSelectIdCert;
  private PreparedStatement psUpdateCert;
  private PreparedStatement psUpdateCertRev;

  public ImportCrl(DataSourceWrapper datasource, X509CRL crl, String crlUrl,
      X509Certificate caCert, X509Certificate issuerCert, CertRevocationInfo caRevInfo,
      String certsDirName) throws ImportCrlException, DataAccessException {
    this.datasource = Args.notNull(datasource, "datasource");
    this.certhashAlgo = DbCertStatusStore.getCertHashAlgo(datasource);
    this.crl = Args.notNull(crl, "crl");
    this.caCert = Args.notNull(caCert, "caCert");
    this.x500PrincipalCaSubject = caCert.getSubjectX500Principal();
    this.caSubject = X500Name.getInstance(x500PrincipalCaSubject.getEncoded());
    try {
      this.caSpki = X509Util.extractSki(caCert);
    } catch (CertificateEncodingException ex) {
      throw new ImportCrlException("could not extract AKI of CA certificate", ex);
    }

    this.certsDirName = certsDirName;
    this.caRevInfo = caRevInfo;

    X500Principal issuer = crl.getIssuerX500Principal();

    boolean caAsCrlIssuer = true;
    if (!x500PrincipalCaSubject.equals(issuer)) {
      caAsCrlIssuer = false;
      if (issuerCert == null) {
        throw new IllegalArgumentException("issuerCert may not be null");
      }

      if (!issuerCert.getSubjectX500Principal().equals(issuer)) {
        throw new IllegalArgumentException("issuerCert and CRL do not match");
      }
    }

    // Verify the signature
    X509Certificate crlSignerCert = caAsCrlIssuer ? caCert : issuerCert;
    try {
      crl.verify(crlSignerCert.getPublicKey());
    } catch (SignatureException | NoSuchProviderException | InvalidKeyException | CRLException
        | NoSuchAlgorithmException ex) {
      throw new ImportCrlException("could not verify signature of CRL", ex);
    }

    byte[] octetString = crl.getExtensionValue(Extension.cRLNumber.getId());
    if (octetString == null) {
      throw new IllegalArgumentException("CRL without CRLNumber is not supported");
    }
    ASN1Integer asn1CrlNumber
        = ASN1Integer.getInstance(DEROctetString.getInstance(octetString).getOctets());
    this.crlNumber = asn1CrlNumber.getPositiveValue();

    octetString = crl.getExtensionValue(Extension.deltaCRLIndicator.getId());
    this.isDeltaCrl = (octetString != null);
    if (this.isDeltaCrl) {
      LOG.info("The CRL is a DeltaCRL");
      byte[] extnValue = DEROctetString.getInstance(octetString).getOctets();
      this.baseCrlNumber = ASN1Integer.getInstance(extnValue).getPositiveValue();
    } else {
      LOG.info("The CRL is a full CRL");
      this.baseCrlNumber = null;
    }

    // Construct CrlID
    ASN1EncodableVector vec = new ASN1EncodableVector();
    if (StringUtil.isNotBlank(crlUrl)) {
      vec.add(new DERTaggedObject(true, 0, new DERIA5String(crlUrl, true)));
    }

    vec.add(new DERTaggedObject(true, 1, asn1CrlNumber));
    vec.add(new DERTaggedObject(true, 2, new DERGeneralizedTime(crl.getThisUpdate())));
    this.crlId = CrlID.getInstance(new DERSequence(vec));

    this.sqlSelectIdCert = datasource.buildSelectFirstSql(1, CORE_SQL_SELECT_ID_CERT);
  }

  public boolean importCrlToOcspDb() {
    Connection conn = null;
    try {
      conn = datasource.getConnection();

      // CHECKSTYLE:SKIP
      Date startTime = new Date();
      // CHECKSTYLE:SKIP
      int caId = importCa(conn);

      psDeleteCert = datasource.prepareStatement(conn, SQL_DELETE_CERT);
      psInsertCert = datasource.prepareStatement(conn, SQL_INSERT_CERT);
      psInsertCertRev = datasource.prepareStatement(conn, SQL_INSERT_CERT_REV);
      psSelectIdCert = datasource.prepareStatement(conn, sqlSelectIdCert);
      psUpdateCert = datasource.prepareStatement(conn, SQL_UPDATE_CERT);
      psUpdateCertRev = datasource.prepareStatement(conn, SQL_UPDATE_CERT_REV);

      importEntries(conn, caId);
      deleteEntriesNotUpdatedSince(conn, startTime);

      return true;
    } catch (Throwable th) {
      LogUtil.error(LOG, th, "could not import CRL to OCSP database");
      releaseResources(psDeleteCert, null);
      releaseResources(psInsertCert, null);
      releaseResources(psInsertCertRev, null);
      releaseResources(psSelectIdCert, null);
      releaseResources(psUpdateCert, null);
      releaseResources(psUpdateCertRev, null);

      if (conn != null) {
        datasource.returnConnection(conn);
      }
    }

    return false;
  }

  private int importCa(Connection conn) throws DataAccessException, ImportCrlException {
    byte[] encodedCaCert;
    try {
      encodedCaCert = caCert.getEncoded();
    } catch (CertificateEncodingException ex) {
      throw new ImportCrlException("could not encode CA certificate");
    }
    String fpCaCert = HashAlgo.SHA1.base64Hash(encodedCaCert);

    Integer issuerId = null;
    CrlInfo crlInfo = null;

    PreparedStatement ps = null;
    ResultSet rs = null;
    String sql = null;
    try {
      sql = "SELECT ID,CRL_INFO FROM ISSUER WHERE S1C=?";
      ps = datasource.prepareStatement(conn, sql);
      ps.setString(1, fpCaCert);
      rs = ps.executeQuery();
      if (rs.next()) {
        issuerId = rs.getInt("ID");
        String str = rs.getString("CRL_INFO");
        if (str == null) {
          throw new ImportCrlException(
            "RequestIssuer for the given CA of CRL exists, but not imported from CRL");
        }
        crlInfo = new CrlInfo(str);
      }
    } catch (SQLException ex) {
      throw datasource.translate(sql, ex);
    } finally {
      releaseResources(ps, rs);
    }

    boolean addNew = (issuerId == null);
    if (addNew) {
      if (isDeltaCrl) {
        throw new ImportCrlException("Given CRL is a deltaCRL for the full CRL with number "
            + baseCrlNumber + ", please import this full CRL first.");
      } else {
        crlInfo = new CrlInfo(crlNumber, null, crl.getThisUpdate(), crl.getNextUpdate(), crlId);
      }
    } else {
      if (crlNumber.compareTo(crlInfo.getCrlNumber()) < 0) {
        // It is permitted if the CRL number equals to the one in Database,
        // which enables the resume of importing process if error occurred.
        throw new ImportCrlException("Given CRL is not newer than existing CRL.");
      }

      if (isDeltaCrl) {
        BigInteger lastFullCrlNumber = crlInfo.getBaseCrlNumber();
        if (lastFullCrlNumber == null) {
          lastFullCrlNumber = crlInfo.getCrlNumber();
        }

        if (!baseCrlNumber.equals(lastFullCrlNumber)) {
          throw new ImportCrlException("Given CRL is a deltaCRL for the full CRL with number "
              + crlNumber + ", please import this full CRL first.");
        }
      }

      crlInfo.setCrlNumber(crlNumber);
      crlInfo.setBaseCrlNumber(isDeltaCrl ? baseCrlNumber : null);
      crlInfo.setThisUpdate(crl.getThisUpdate());
      crlInfo.setNextUpdate(crl.getNextUpdate());
    }

    ps = null;
    rs = null;
    sql = null;
    try {
      // issuer exists
      int offset = 1;
      if (addNew) {
        int maxId = (int) datasource.getMax(conn, "ISSUER", "ID");
        issuerId = maxId + 1;

        sql = "INSERT INTO ISSUER (ID,SUBJECT,NBEFORE,NAFTER,S1C,CERT,REV_INFO,CRL_INFO)"
            + " VALUES(?,?,?,?,?,?,?,?)";
        ps = datasource.prepareStatement(conn, sql);
        String subject = X509Util.getRfc4519Name(caCert.getSubjectX500Principal());

        ps.setInt(offset++, issuerId);
        ps.setString(offset++, subject);
        ps.setLong(offset++, caCert.getNotBefore().getTime() / 1000);
        ps.setLong(offset++, caCert.getNotAfter().getTime() / 1000);
        ps.setString(offset++, fpCaCert);
        ps.setString(offset++, Base64.encodeToString(encodedCaCert));
      } else {
        sql = "UPDATE ISSUER SET REV_INFO=?,CRL_INFO=? WHERE ID=?";
        ps = datasource.prepareStatement(conn, sql);
      }

      ps.setString(offset++, (caRevInfo == null) ? null : caRevInfo.getEncoded());

      // CRL info
      try {
        ps.setString(offset++, crlInfo.getEncoded());
      } catch (IOException ex) {
        throw new ImportCrlException("could not encode the Crlinfo", ex);
      }

      if (!addNew) {
        ps.setInt(offset++, issuerId.intValue());
      }

      ps.executeUpdate();
      return issuerId.intValue();
    } catch (SQLException ex) {
      throw datasource.translate(sql, ex);
    } finally {
      releaseResources(ps, rs);
    }
  }

  private void importEntries(Connection conn, int caId)
      throws DataAccessException, ImportCrlException {
    AtomicLong maxId = new AtomicLong(datasource.getMax(conn, "CERT", "ID"));

    // import the revoked information
    Set<? extends X509CRLEntry> revokedCertList = crl.getRevokedCertificates();
    if (revokedCertList != null) {
      for (X509CRLEntry c : revokedCertList) {
        X500Principal issuer = c.getCertificateIssuer();
        BigInteger serial = c.getSerialNumber();

        if (issuer != null) {
          if (!x500PrincipalCaSubject.equals(issuer)) {
            throw new ImportCrlException("invalid CRLEntry for certificate number " + serial);
          }
        }

        Date rt = c.getRevocationDate();
        Date rit = null;
        byte[] extnValue = c.getExtensionValue(Extension.invalidityDate.getId());
        if (extnValue != null) {
          extnValue = extractCoreValue(extnValue);
          ASN1GeneralizedTime genTime = DERGeneralizedTime.getInstance(extnValue);
          try {
            rit = genTime.getDate();
          } catch (ParseException ex) {
            throw new ImportCrlException(ex.getMessage(), ex);
          }

          if (rt.equals(rit)) {
            rit = null;
          }
        }

        CrlReason reason = CrlReason.fromReason(c.getRevocationReason());

        String sql = null;
        try {
          if (reason == CrlReason.REMOVE_FROM_CRL) {
            if (!isDeltaCrl) {
              LOG.warn("ignore CRL entry with reason removeFromCRL in non-Delta CRL");
            }

            // delete the entry
            sql = SQL_DELETE_CERT;
            psDeleteCert.setInt(1, caId);
            psDeleteCert.setString(2, serial.toString(16));
            psDeleteCert.executeUpdate();
            continue;
          }

          Long id = getId(caId, serial);
          PreparedStatement ps;
          int offset = 1;

          if (id == null) {
            sql = SQL_INSERT_CERT_REV;
            id = maxId.incrementAndGet();
            ps = psInsertCertRev;
            ps.setLong(offset++, id);
            ps.setInt(offset++, caId);
            ps.setString(offset++, serial.toString(16));
          } else {
            sql = SQL_UPDATE_CERT_REV;
            ps = psUpdateCertRev;
          }

          ps.setInt(offset++, 1);
          ps.setInt(offset++, reason.getCode());
          ps.setLong(offset++, rt.getTime() / 1000);
          if (rit != null) {
            ps.setLong(offset++, rit.getTime() / 1000);
          } else {
            ps.setNull(offset++, Types.BIGINT);
          }
          ps.setLong(offset++, System.currentTimeMillis() / 1000);

          if (ps == psUpdateCertRev) {
            ps.setLong(offset++, id);
          }

          ps.executeUpdate();
        } catch (SQLException ex) {
          throw datasource.translate(sql, ex);
        }
      }
    }

    // import the certificates

    // extract the certificate
    byte[] extnValue =
        crl.getExtensionValue(ObjectIdentifiers.Xipki.id_xipki_ext_crlCertset.getId());
    if (extnValue != null) {
      extnValue = extractCoreValue(extnValue);
      ASN1Set asn1Set = DERSet.getInstance(extnValue);
      final int n = asn1Set.size();

      for (int i = 0; i < n; i++) {
        ASN1Encodable asn1 = asn1Set.getObjectAt(i);
        ASN1Sequence seq = ASN1Sequence.getInstance(asn1);
        BigInteger serialNumber = ASN1Integer.getInstance(seq.getObjectAt(0)).getValue();

        Certificate cert = null;
        String profileName = null;

        final int size = seq.size();
        for (int j = 1; j < size; j++) {
          ASN1TaggedObject taggedObj = DERTaggedObject.getInstance(seq.getObjectAt(j));
          int tagNo = taggedObj.getTagNo();
          switch (tagNo) {
            case 0:
              cert = Certificate.getInstance(taggedObj.getObject());
              break;
            case 1:
              profileName = DERUTF8String.getInstance(taggedObj.getObject()).getString();
              break;
            default:
              break;
          }
        }

        if (cert == null) {
          continue;
        }

        if (!caSubject.equals(cert.getIssuer())) {
          LOG.warn("issuer not match (serial={}) in CRL Extension Xipki-CertSet, ignore it",
              LogUtil.formatCsn(serialNumber));
          continue;
        }

        if (!serialNumber.equals(cert.getSerialNumber().getValue())) {
          LOG.warn("serialNumber not match (serial={}) in CRL Extension Xipki-CertSet, ignore it",
              LogUtil.formatCsn(serialNumber));
          continue;
        }

        String certLogId = "(issuer='" + cert.getIssuer()
            + "', serialNumber=" + cert.getSerialNumber() + ")";
        addCertificate(maxId, caId, cert, profileName, certLogId);
      }
    } else {
      // cert dirs
      File certsDir = new File(certsDirName);

      if (!certsDir.exists()) {
        LOG.warn("the folder {} does not exist, ignore it", certsDirName);
        return;
      }

      if (!certsDir.isDirectory()) {
        LOG.warn("the path {} does not point to a folder, ignore it", certsDirName);
        return;
      }

      if (!certsDir.canRead()) {
        LOG.warn("the folder {} may not be read, ignore it", certsDirName);
        return;
      }

      File[] certFiles = certsDir.listFiles(new FilenameFilter() {
        @Override
        public boolean accept(File dir, String name) {
          return name.endsWith(".der") || name.endsWith(".crt");
        }
      });

      if (certFiles == null || certFiles.length == 0) {
        return;
      }

      for (File certFile : certFiles) {
        Certificate cert;

        try {
          byte[] encoded = IoUtil.read(certFile);
          cert = Certificate.getInstance(encoded);
        } catch (IllegalArgumentException | IOException ex) {
          LOG.warn("could not parse certificate {}, ignore it", certFile.getPath());
          continue;
        }

        String certLogId = "(file " + certFile.getName() + ")";
        addCertificate(maxId, caId, cert, null, certLogId);
      }
    }

  }

  private static byte[] extractCoreValue(byte[] encodedExtensionValue) {
    return ASN1OctetString.getInstance(encodedExtensionValue).getOctets();
  }

  private Long getId(int caId, BigInteger serialNumber) throws DataAccessException {
    ResultSet rs = null;
    try {
      psSelectIdCert.setInt(1, caId);
      psSelectIdCert.setString(2, serialNumber.toString(16));
      rs = psSelectIdCert.executeQuery();
      if (!rs.next()) {
        return null;
      }
      return rs.getLong("ID");
    } catch (SQLException ex) {
      throw datasource.translate(sqlSelectIdCert, ex);
    } finally {
      releaseResources(null, rs);
    }
  }

  private void addCertificate(AtomicLong maxId, int caId, Certificate cert, String profileName,
      String certLogId) throws DataAccessException, ImportCrlException {
    // not issued by the given issuer
    if (!caSubject.equals(cert.getIssuer())) {
      LOG.warn("certificate {} is not issued by the given CA, ignore it", certLogId);
      return;
    }

    // we don't use the binary read from file, since it may contains redundant ending bytes.
    byte[] encodedCert;
    try {
      encodedCert = cert.getEncoded();
    } catch (IOException ex) {
      throw new ImportCrlException("could not encode certificate {}" + certLogId, ex);
    }
    String b64CertHash = certhashAlgo.base64Hash(encodedCert);

    if (caSpki != null) {
      byte[] aki = null;
      try {
        aki = X509Util.extractAki(cert);
      } catch (CertificateEncodingException ex) {
        LogUtil.error(LOG, ex,
            "invalid AuthorityKeyIdentifier of certificate {}" + certLogId + ", ignore it");
        return;
      }

      if (aki == null || !Arrays.equals(caSpki, aki)) {
        LOG.warn("certificate {} is not issued by the given CA, ignore it", certLogId);
        return;
      }
    } // end if

    LOG.info("Importing certificate {}", certLogId);
    Long id = getId(caId, cert.getSerialNumber().getPositiveValue());
    boolean tblCertIdExists = (id != null);

    PreparedStatement ps;
    String sql;
    // first update the table CERT
    if (tblCertIdExists) {
      sql = SQL_UPDATE_CERT;
      ps = psUpdateCert;
    } else {
      sql = SQL_INSERT_CERT;
      ps = psInsertCert;
      id = maxId.incrementAndGet();
    }

    try {
      int offset = 1;
      if (sql == SQL_INSERT_CERT) {
        ps.setLong(offset++, id);
        // ISSUER ID IID
        ps.setInt(offset++, caId);
        // serial number SN
        ps.setString(offset++, cert.getSerialNumber().getPositiveValue().toString(16));
        // whether revoked REV
        ps.setInt(offset++, 0);
        // revocation reason RR
        ps.setNull(offset++, Types.SMALLINT);
        // revocation time RT
        ps.setNull(offset++, Types.BIGINT);
        ps.setNull(offset++, Types.BIGINT);
      }

      // last update LUPDATE
      ps.setLong(offset++, System.currentTimeMillis() / 1000);

      TBSCertificate tbsCert = cert.getTBSCertificate();
      // not before NBEFORE
      ps.setLong(offset++, tbsCert.getStartDate().getDate().getTime() / 1000);
      // not after NAFTER
      ps.setLong(offset++, tbsCert.getEndDate().getDate().getTime() / 1000);
      ps.setString(offset++, b64CertHash);

      if (sql == SQL_UPDATE_CERT) {
        ps.setLong(offset++, id);
      }

      ps.executeUpdate();
    } catch (SQLException ex) {
      throw datasource.translate(sql, ex);
    }

    LOG.info("Imported  certificate {}", certLogId);
  }

  private void deleteEntriesNotUpdatedSince(Connection conn, Date time)
      throws DataAccessException {
    // remove the unmodified entries
    String sql = "DELETE FROM CERT WHERE LUPDATE<" + time.getTime() / 1000;
    Statement stmt = datasource.createStatement(conn);
    try {
      stmt.executeUpdate(sql);
    } catch (SQLException ex) {
      throw datasource.translate(sql, ex);
    } finally {
      releaseResources(stmt, null);
    }
  }

  private void releaseResources(Statement ps, ResultSet rs) {
    datasource.releaseResources(ps, rs, false);
  }

}
