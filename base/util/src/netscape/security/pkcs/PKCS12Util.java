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
// (C) 2016 Red Hat, Inc.
// All rights reserved.
// --- END COPYRIGHT BLOCK ---
package netscape.security.pkcs;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.Principal;
import java.security.PublicKey;
import java.security.cert.CertificateException;
import java.util.logging.Logger;

import org.mozilla.jss.CryptoManager;
import org.mozilla.jss.asn1.ANY;
import org.mozilla.jss.asn1.ASN1Util;
import org.mozilla.jss.asn1.ASN1Value;
import org.mozilla.jss.asn1.BMPString;
import org.mozilla.jss.asn1.OBJECT_IDENTIFIER;
import org.mozilla.jss.asn1.OCTET_STRING;
import org.mozilla.jss.asn1.SEQUENCE;
import org.mozilla.jss.asn1.SET;
import org.mozilla.jss.crypto.Cipher;
import org.mozilla.jss.crypto.CryptoStore;
import org.mozilla.jss.crypto.CryptoToken;
import org.mozilla.jss.crypto.EncryptionAlgorithm;
import org.mozilla.jss.crypto.IVParameterSpec;
import org.mozilla.jss.crypto.InternalCertificate;
import org.mozilla.jss.crypto.KeyGenAlgorithm;
import org.mozilla.jss.crypto.KeyGenerator;
import org.mozilla.jss.crypto.KeyWrapAlgorithm;
import org.mozilla.jss.crypto.KeyWrapper;
import org.mozilla.jss.crypto.NoSuchItemOnTokenException;
import org.mozilla.jss.crypto.ObjectNotFoundException;
import org.mozilla.jss.crypto.PBEAlgorithm;
import org.mozilla.jss.crypto.PrivateKey;
import org.mozilla.jss.crypto.SymmetricKey;
import org.mozilla.jss.crypto.X509Certificate;
import org.mozilla.jss.pkcs12.AuthenticatedSafes;
import org.mozilla.jss.pkcs12.CertBag;
import org.mozilla.jss.pkcs12.PFX;
import org.mozilla.jss.pkcs12.PasswordConverter;
import org.mozilla.jss.pkcs12.SafeBag;
import org.mozilla.jss.pkix.primitive.Attribute;
import org.mozilla.jss.pkix.primitive.EncryptedPrivateKeyInfo;
import org.mozilla.jss.pkix.primitive.PrivateKeyInfo;
import org.mozilla.jss.util.Password;

import netscape.ldap.LDAPDN;
import netscape.security.x509.X509CertImpl;

public class PKCS12Util {

    private static Logger logger = Logger.getLogger(PKCS12Util.class.getName());

    boolean trustFlagsEnabled = true;

    public boolean isTrustFlagsEnabled() {
        return trustFlagsEnabled;
    }

    public void setTrustFlagsEnabled(boolean trustFlagsEnabled) {
        this.trustFlagsEnabled = trustFlagsEnabled;
    }

    public String getTrustFlags(X509Certificate cert) {

        InternalCertificate icert = (InternalCertificate) cert;

        StringBuilder sb = new StringBuilder();

        sb.append(PKCS12.encodeFlags(icert.getSSLTrust()));
        sb.append(",");
        sb.append(PKCS12.encodeFlags(icert.getEmailTrust()));
        sb.append(",");
        sb.append(PKCS12.encodeFlags(icert.getObjectSigningTrust()));

        return sb.toString();
    }

    public void setTrustFlags(X509Certificate cert, String trustFlags) throws Exception {

        InternalCertificate icert = (InternalCertificate) cert;

        String[] flags = trustFlags.split(",");
        if (flags.length < 3) throw new Exception("Invalid trust flags: " + trustFlags);

        icert.setSSLTrust(PKCS12.decodeFlags(flags[0]));
        icert.setEmailTrust(PKCS12.decodeFlags(flags[1]));
        icert.setObjectSigningTrust(PKCS12.decodeFlags(flags[2]));
    }

    byte[] getEncodedKey(PrivateKey privateKey) throws Exception {

        CryptoManager cm = CryptoManager.getInstance();
        CryptoToken token = cm.getInternalKeyStorageToken();

        KeyGenerator kg = token.getKeyGenerator(KeyGenAlgorithm.DES3);
        SymmetricKey sk = kg.generate();

        KeyWrapper wrapper = token.getKeyWrapper(KeyWrapAlgorithm.DES3_CBC_PAD);
        byte[] iv = { 0x1, 0x1, 0x1, 0x1, 0x1, 0x1, 0x1, 0x1 };
        IVParameterSpec param = new IVParameterSpec(iv);
        wrapper.initWrap(sk, param);
        byte[] enckey = wrapper.wrap(privateKey);

        Cipher c = token.getCipherContext(EncryptionAlgorithm.DES3_CBC_PAD);
        c.initDecrypt(sk, param);
        return c.doFinal(enckey);
    }

    public void addKeyBag(PKCS12KeyInfo keyInfo, Password password,
            SEQUENCE encSafeContents) throws Exception {

        logger.fine("Creating key bag for " + keyInfo.subjectDN);

        PasswordConverter passConverter = new PasswordConverter();
        byte salt[] = { 0x01, 0x01, 0x01, 0x01 };

        EncryptedPrivateKeyInfo encPrivateKeyInfo = EncryptedPrivateKeyInfo.createPBE(
                PBEAlgorithm.PBE_SHA1_DES3_CBC,
                password, salt, 1, passConverter, keyInfo.privateKeyInfo);

        SET keyAttrs = createKeyBagAttrs(keyInfo);

        SafeBag safeBag = new SafeBag(SafeBag.PKCS8_SHROUDED_KEY_BAG, encPrivateKeyInfo, keyAttrs);
        encSafeContents.addElement(safeBag);
    }

    public void addCertBag(PKCS12CertInfo certInfo,
            SEQUENCE safeContents) throws Exception {

        logger.fine("Creating cert bag for " + certInfo.nickname);

        ASN1Value cert = new OCTET_STRING(certInfo.cert.getEncoded());
        CertBag certBag = new CertBag(CertBag.X509_CERT_TYPE, cert);

        SET certAttrs = createCertBagAttrs(certInfo);

        SafeBag safeBag = new SafeBag(SafeBag.CERT_BAG, certBag, certAttrs);
        safeContents.addElement(safeBag);
    }

    BigInteger createLocalKeyID(X509Certificate cert) throws Exception {

        // SHA1 hash of the X509Cert DER encoding
        byte[] certDer = cert.getEncoded();

        MessageDigest md = MessageDigest.getInstance("SHA");

        md.update(certDer);
        return new BigInteger(1, md.digest());
    }

    SET createKeyBagAttrs(PKCS12KeyInfo keyInfo) throws Exception {

        SET attrs = new SET();

        SEQUENCE subjectAttr = new SEQUENCE();
        subjectAttr.addElement(SafeBag.FRIENDLY_NAME);

        SET subjectSet = new SET();
        subjectSet.addElement(new BMPString(keyInfo.subjectDN));
        subjectAttr.addElement(subjectSet);

        attrs.addElement(subjectAttr);

        SEQUENCE localKeyAttr = new SEQUENCE();
        localKeyAttr.addElement(SafeBag.LOCAL_KEY_ID);

        SET localKeySet = new SET();
        localKeySet.addElement(new OCTET_STRING(keyInfo.id.toByteArray()));
        localKeyAttr.addElement(localKeySet);

        attrs.addElement(localKeyAttr);

        return attrs;
    }

    SET createCertBagAttrs(PKCS12CertInfo certInfo) throws Exception {

        SET attrs = new SET();

        SEQUENCE nicknameAttr = new SEQUENCE();
        nicknameAttr.addElement(SafeBag.FRIENDLY_NAME);

        SET nicknameSet = new SET();
        nicknameSet.addElement(new BMPString(certInfo.nickname));
        nicknameAttr.addElement(nicknameSet);

        attrs.addElement(nicknameAttr);

        SEQUENCE localKeyAttr = new SEQUENCE();
        localKeyAttr.addElement(SafeBag.LOCAL_KEY_ID);

        SET localKeySet = new SET();
        localKeySet.addElement(new OCTET_STRING(certInfo.keyID.toByteArray()));
        localKeyAttr.addElement(localKeySet);

        attrs.addElement(localKeyAttr);

        if (certInfo.trustFlags != null && trustFlagsEnabled) {
            SEQUENCE trustFlagsAttr = new SEQUENCE();
            trustFlagsAttr.addElement(PKCS12.CERT_TRUST_FLAGS_OID);

            SET trustFlagsSet = new SET();
            trustFlagsSet.addElement(new BMPString(certInfo.trustFlags));
            trustFlagsAttr.addElement(trustFlagsSet);

            attrs.addElement(trustFlagsAttr);
        }

        return attrs;
    }

    public void loadFromNSS(PKCS12 pkcs12) throws Exception {

        logger.info("Loading all certificate and keys from NSS database");

        CryptoManager cm = CryptoManager.getInstance();
        CryptoToken token = cm.getInternalKeyStorageToken();
        CryptoStore store = token.getCryptoStore();

        // load all certs
        for (X509Certificate cert : store.getCertificates()) {
            loadCertFromNSS(pkcs12, cert, true); // load cert with private key
        }
    }

    public void loadFromNSS(PKCS12 pkcs12, String nickname, boolean includeCert, boolean includeKey, boolean includeChain) throws Exception {

        CryptoManager cm = CryptoManager.getInstance();

        X509Certificate cert = cm.findCertByNickname(nickname);

        if (includeCert) {
            loadCertFromNSS(pkcs12, cert, includeKey);
        }

        if (includeChain) {
            loadCertChainFromNSS(pkcs12, cert);
        }
    }

    public void loadCertFromNSS(PKCS12 pkcs12, X509Certificate cert, boolean includeKey) throws Exception {

        String nickname = cert.getNickname();
        logger.info("Loading certificate \"" + nickname + "\" from NSS database");

        CryptoManager cm = CryptoManager.getInstance();

        BigInteger keyID = createLocalKeyID(cert);

        PKCS12CertInfo certInfo = new PKCS12CertInfo();
        certInfo.keyID = keyID;
        certInfo.nickname = nickname;
        certInfo.cert = new X509CertImpl(cert.getEncoded());
        certInfo.trustFlags = getTrustFlags(cert);
        pkcs12.addCertInfo(certInfo);

        if (!includeKey) return;

        logger.info("Loading private key for certificate \"" + nickname + "\" from NSS database");

        try {
            PrivateKey privateKey = cm.findPrivKeyByCert(cert);
            logger.fine("Certificate \"" + nickname + "\" has private key");

            PKCS12KeyInfo keyInfo = new PKCS12KeyInfo();
            keyInfo.id = keyID;
            keyInfo.subjectDN = cert.getSubjectDN().toString();

            byte[] privateData = getEncodedKey(privateKey);
            keyInfo.privateKeyInfo = (PrivateKeyInfo)
                    ASN1Util.decode(PrivateKeyInfo.getTemplate(), privateData);

            pkcs12.addKeyInfo(keyInfo);

        } catch (ObjectNotFoundException e) {
            logger.fine("Certificate \"" + nickname + "\" has no private key");
        }
    }

    public void loadCertChainFromNSS(PKCS12 pkcs12, X509Certificate cert) throws Exception {

        logger.info("Loading certificate chain for \"" + cert.getNickname() + "\"");

        CryptoManager cm = CryptoManager.getInstance();
        X509Certificate[] certChain = cm.buildCertificateChain(cert);

        // load parent certificates only
        for (int i = 1; i < certChain.length; i++) {
            X509Certificate c = certChain[i];
            loadCertFromNSS(pkcs12, c, false); // do not include private key
        }
    }

    public void storeIntoFile(PKCS12 pkcs12, String filename, Password password) throws Exception {

        logger.info("Storing data into PKCS #12 file");

        SEQUENCE safeContents = new SEQUENCE();

        for (PKCS12CertInfo certInfo : pkcs12.getCertInfos()) {
            addCertBag(certInfo, safeContents);
        }

        SEQUENCE encSafeContents = new SEQUENCE();

        for (PKCS12KeyInfo keyInfo : pkcs12.getKeyInfos()) {
            addKeyBag(keyInfo, password, encSafeContents);
        }

        AuthenticatedSafes authSafes = new AuthenticatedSafes();
        authSafes.addSafeContents(safeContents);
        authSafes.addSafeContents(encSafeContents);

        PFX pfx = new PFX(authSafes);
        pfx.computeMacData(password, null, 5);

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        pfx.encode(bos);
        byte[] data = bos.toByteArray();

        try (FileOutputStream fos = new FileOutputStream(filename)) {
            fos.write(data);
        }
    }

    public PKCS12KeyInfo getKeyInfo(SafeBag bag, Password password) throws Exception {

        PKCS12KeyInfo keyInfo = new PKCS12KeyInfo();

        // get private key info
        EncryptedPrivateKeyInfo encPrivateKeyInfo = (EncryptedPrivateKeyInfo) bag.getInterpretedBagContent();
        keyInfo.privateKeyInfo = encPrivateKeyInfo.decrypt(password, new PasswordConverter());

        // get key attributes
        SET bagAttrs = bag.getBagAttributes();

        for (int i = 0; i < bagAttrs.size(); i++) {

            Attribute attr = (Attribute) bagAttrs.elementAt(i);
            OBJECT_IDENTIFIER oid = attr.getType();

            if (oid.equals(SafeBag.FRIENDLY_NAME)) {

                SET values = attr.getValues();
                ANY value = (ANY) values.elementAt(0);

                ByteArrayInputStream bis = new ByteArrayInputStream(value.getEncoded());
                BMPString subjectDN = (BMPString) new BMPString.Template().decode(bis);

                keyInfo.subjectDN = subjectDN.toString();
                logger.fine("Subject DN: " + keyInfo.subjectDN);

            } else if (oid.equals(SafeBag.LOCAL_KEY_ID)) {

                SET values = attr.getValues();
                ANY value = (ANY) values.elementAt(0);

                ByteArrayInputStream bis = new ByteArrayInputStream(value.getEncoded());
                OCTET_STRING keyID = (OCTET_STRING) new OCTET_STRING.Template().decode(bis);

                keyInfo.id = new BigInteger(1, keyID.toByteArray());
                logger.fine("Key ID: " + keyInfo.id.toString(16));
            }
        }

        logger.fine("Found private key " + keyInfo.subjectDN);

        return keyInfo;
    }

    public PKCS12CertInfo getCertInfo(SafeBag bag) throws Exception {

        PKCS12CertInfo certInfo = new PKCS12CertInfo();

        CertBag certBag = (CertBag) bag.getInterpretedBagContent();

        OCTET_STRING certStr = (OCTET_STRING) certBag.getInterpretedCert();
        byte[] x509cert = certStr.toByteArray();

        certInfo.cert = new X509CertImpl(x509cert);
        logger.fine("Found certificate " + certInfo.cert.getSubjectDN());

        SET bagAttrs = bag.getBagAttributes();
        if (bagAttrs == null) return certInfo;

        for (int i = 0; i < bagAttrs.size(); i++) {

            Attribute attr = (Attribute) bagAttrs.elementAt(i);
            OBJECT_IDENTIFIER oid = attr.getType();

            if (oid.equals(SafeBag.FRIENDLY_NAME)) {

                SET values = attr.getValues();
                ANY value = (ANY) values.elementAt(0);

                ByteArrayInputStream bis = new ByteArrayInputStream(value.getEncoded());
                BMPString nickname = (BMPString) (new BMPString.Template()).decode(bis);

                certInfo.nickname = nickname.toString();
                logger.fine("Nickname: " + certInfo.nickname);


            } else if (oid.equals(SafeBag.LOCAL_KEY_ID)) {

                SET values = attr.getValues();
                ANY value = (ANY) values.elementAt(0);

                ByteArrayInputStream bis = new ByteArrayInputStream(value.getEncoded());
                OCTET_STRING keyID = (OCTET_STRING) new OCTET_STRING.Template().decode(bis);

                certInfo.keyID = new BigInteger(1, keyID.toByteArray());
                logger.fine("Key ID: " + certInfo.keyID.toString(16));

            } else if (oid.equals(PKCS12.CERT_TRUST_FLAGS_OID) && trustFlagsEnabled) {

                SET values = attr.getValues();
                ANY value = (ANY) values.elementAt(0);

                ByteArrayInputStream is = new ByteArrayInputStream(value.getEncoded());
                BMPString trustFlags = (BMPString) (new BMPString.Template()).decode(is);

                certInfo.trustFlags = trustFlags.toString();
                logger.fine("Trust flags: " + certInfo.trustFlags);
            }
        }

        return certInfo;
    }

    public void getKeyInfos(PKCS12 pkcs12, PFX pfx, Password password) throws Exception {

        logger.fine("Getting private keys");

        AuthenticatedSafes safes = pfx.getAuthSafes();

        for (int i = 0; i < safes.getSize(); i++) {

            SEQUENCE contents = safes.getSafeContentsAt(password, i);

            for (int j = 0; j < contents.size(); j++) {

                SafeBag bag = (SafeBag) contents.elementAt(j);
                OBJECT_IDENTIFIER oid = bag.getBagType();

                if (!oid.equals(SafeBag.PKCS8_SHROUDED_KEY_BAG)) continue;

                PKCS12KeyInfo keyInfo = getKeyInfo(bag, password);
                pkcs12.addKeyInfo(keyInfo);
            }
        }
    }

    public void getCertInfos(PKCS12 pkcs12, PFX pfx, Password password) throws Exception {

        logger.fine("Getting certificates");

        AuthenticatedSafes safes = pfx.getAuthSafes();

        for (int i = 0; i < safes.getSize(); i++) {

            SEQUENCE contents = safes.getSafeContentsAt(password, i);

            for (int j = 0; j < contents.size(); j++) {

                SafeBag bag = (SafeBag) contents.elementAt(j);
                OBJECT_IDENTIFIER oid = bag.getBagType();

                if (!oid.equals(SafeBag.CERT_BAG)) continue;

                PKCS12CertInfo certInfo = getCertInfo(bag);
                pkcs12.addCertInfo(certInfo);
            }
        }
    }

    public PKCS12 loadFromFile(String filename, Password password) throws Exception {

        logger.info("Loading PKCS #12 file");

        Path path = Paths.get(filename);
        byte[] b = Files.readAllBytes(path);

        ByteArrayInputStream bis = new ByteArrayInputStream(b);

        PFX pfx = (PFX) (new PFX.Template()).decode(bis);

        PKCS12 pkcs12 = new PKCS12();

        StringBuffer reason = new StringBuffer();
        boolean valid = pfx.verifyAuthSafes(password, reason);

        if (!valid) {
            throw new Exception("Invalid PKCS #12 password: " + reason);
        }

        getKeyInfos(pkcs12, pfx, password);
        getCertInfos(pkcs12, pfx, password);

        return pkcs12;
    }

    public PKCS12 loadFromFile(String filename) throws Exception {
        return loadFromFile(filename, null);
    }

    public PrivateKey.Type getPrivateKeyType(PublicKey publicKey) {
        if (publicKey.getAlgorithm().equals("EC")) {
            return PrivateKey.Type.EC;
        }
        return PrivateKey.Type.RSA;
    }

    public PKCS12CertInfo getCertBySubjectDN(PKCS12 pkcs12, String subjectDN)
            throws CertificateException {

        for (PKCS12CertInfo certInfo : pkcs12.getCertInfos()) {
            Principal certSubjectDN = certInfo.cert.getSubjectDN();
            if (LDAPDN.equals(certSubjectDN.toString(), subjectDN)) return certInfo;
        }

        return null;
    }

    public void importKey(
            PKCS12 pkcs12,
            PKCS12KeyInfo keyInfo) throws Exception {

        logger.fine("Importing private key " + keyInfo.subjectDN);

        PrivateKeyInfo privateKeyInfo = keyInfo.privateKeyInfo;

        // encode private key
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        privateKeyInfo.encode(bos);
        byte[] privateKey = bos.toByteArray();

        PKCS12CertInfo certInfo = getCertBySubjectDN(pkcs12, keyInfo.subjectDN);
        if (certInfo == null) {
            logger.fine("Private key nas no certificate, ignore");
            return;
        }

        CryptoManager cm = CryptoManager.getInstance();
        CryptoToken token = cm.getInternalKeyStorageToken();
        CryptoStore store = token.getCryptoStore();

        X509Certificate cert = cm.importCACertPackage(certInfo.cert.getEncoded());

        // get public key
        PublicKey publicKey = cert.getPublicKey();

        // delete the cert again
        try {
            store.deleteCert(cert);
        } catch (NoSuchItemOnTokenException e) {
            // this is OK
        }

        // encrypt private key
        KeyGenerator kg = token.getKeyGenerator(KeyGenAlgorithm.DES3);
        SymmetricKey sk = kg.generate();
        byte iv[] = { 0x1, 0x1, 0x1, 0x1, 0x1, 0x1, 0x1, 0x1 };
        IVParameterSpec param = new IVParameterSpec(iv);
        Cipher c = token.getCipherContext(EncryptionAlgorithm.DES3_CBC_PAD);
        c.initEncrypt(sk, param);
        byte[] encpkey = c.doFinal(privateKey);

        // unwrap private key to load into database
        KeyWrapper wrapper = token.getKeyWrapper(KeyWrapAlgorithm.DES3_CBC_PAD);
        wrapper.initUnwrap(sk, param);
        wrapper.unwrapPrivate(encpkey, getPrivateKeyType(publicKey), publicKey);
    }

    public void importKeys(PKCS12 pkcs12) throws Exception {

        for (PKCS12KeyInfo keyInfo : pkcs12.getKeyInfos()) {
            importKey(pkcs12, keyInfo);
        }
    }

    public void importCert(PKCS12CertInfo certInfo) throws Exception {

        logger.fine("Importing certificate " + certInfo.nickname);

        CryptoManager cm = CryptoManager.getInstance();

        X509Certificate cert = cm.importUserCACertPackage(
                certInfo.cert.getEncoded(), certInfo.nickname);

        if (certInfo.trustFlags != null && trustFlagsEnabled)
            setTrustFlags(cert, certInfo.trustFlags);
    }

    public void importCerts(PKCS12 pkcs12) throws Exception {

        for (PKCS12CertInfo certInfo : pkcs12.getCertInfos()) {
            importCert(certInfo);
        }
    }

    public void storeIntoNSS(PKCS12 pkcs12, Password password) throws Exception {

        logger.info("Storing data into NSS database");

        importKeys(pkcs12);
        importCerts(pkcs12);
    }
}