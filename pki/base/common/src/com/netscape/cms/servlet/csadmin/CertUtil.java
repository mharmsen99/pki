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
package com.netscape.cms.servlet.csadmin;

import java.security.*;
import java.security.cert.*;
import java.net.*;
import java.util.*;
import java.math.*;
import java.io.*;
import javax.servlet.*;
import javax.servlet.http.*;
import com.netscape.certsrv.profile.*;
import com.netscape.certsrv.property.*;
import com.netscape.certsrv.authentication.*;
import com.netscape.certsrv.request.*;
import com.netscape.certsrv.dbs.certdb.*;
import com.netscape.certsrv.ca.*;
import com.netscape.cmsutil.crypto.*;
import com.netscape.certsrv.apps.*;
import com.netscape.certsrv.base.*;
import com.netscape.cmsutil.http.*;
import com.netscape.certsrv.dbs.certdb.*;
import com.netscape.certsrv.usrgrp.*;
import netscape.security.x509.*;
import org.mozilla.jss.*;
import org.mozilla.jss.crypto.*;
import org.mozilla.jss.crypto.X509Certificate;
import org.mozilla.jss.crypto.PrivateKey;
import com.netscape.cmsutil.xml.*;
import org.mozilla.jss.crypto.KeyPairGenerator;
import netscape.security.pkcs.*;
import netscape.ldap.*;
import org.apache.velocity.context.Context;
import org.xml.sax.*;


public class CertUtil {
    static final int LINE_COUNT = 76;

    public static X509CertImpl createRemoteCert(String hostname, 
      int port, String content, HttpServletResponse response, WizardPanelBase panel)
      throws IOException {
        HttpClient httpclient = new HttpClient();
        String c = null;
        try {
            JssSSLSocketFactory factory = new JssSSLSocketFactory();

            httpclient = new HttpClient(factory);
            httpclient.connect(hostname, port);
            HttpRequest httprequest = new HttpRequest();

            httprequest.setMethod(HttpRequest.POST);
            httprequest.setURI("/ca/ee/ca/profileSubmit");
            httprequest.setHeader("user-agent", "HTTPTool/1.0");
            httprequest.setHeader("content-length", "" + content.length());
            httprequest.setHeader("content-type",
                    "application/x-www-form-urlencoded");
            httprequest.setContent(content);
            HttpResponse httpresponse = httpclient.send(httprequest);

            c = httpresponse.getContent();
        } catch (Exception e) {
            CMS.debug("CertUtil createRemoteCert: " + e.toString());
            throw new IOException(e.toString());
        }

        if (c != null) {
            try {
                ByteArrayInputStream bis = new ByteArrayInputStream(c.getBytes());
                XMLObject parser = null;

                try {
                    parser = new XMLObject(bis);
                } catch (Exception e) {
                    CMS.debug( "CertUtil::createRemoteCert() - "
                             + "Exception="+e.toString() );
                    throw new IOException( e.toString() );
                }
                String status = parser.getValue("Status");

                CMS.debug("CertUtil createRemoteCert: status=" + status);
                if (status.equals("2")) {
                    //relogin to the security domain
                    panel.reloginSecurityDomain(response);
                    return null;
                } else if (!status.equals("0")) {
                    String error = parser.getValue("Error");
                    throw new IOException(error);
                }

                String b64 = parser.getValue("b64");

                CMS.debug("CertUtil createRemoteCert: " + b64);
                b64 = CryptoUtil.normalizeCertAndReq(b64);
                byte[] b = CryptoUtil.base64Decode(b64);

                return new X509CertImpl(b);
            } catch (Exception e) {
                CMS.debug("CertUtil createRemoteCert: " + e.toString());
                throw new IOException(e.toString());
            }
        }

        return null;
    }

    public static String getPKCS10(IConfigStore config, String prefix, 
            Cert certObj, Context context) throws IOException {
        String certTag = certObj.getCertTag();

        X509Key pubk = null;
        try {
            String pubKeyType = config.getString(
                        prefix + certTag + ".keytype");
            if (pubKeyType.equals("rsa")) {
              String pubKeyModulus = config.getString(
                    prefix + certTag + ".pubkey.modulus");
              String pubKeyPublicExponent = config.getString(
                    prefix + certTag + ".pubkey.exponent");
              pubk = CryptoUtil.getPublicX509Key(
                    CryptoUtil.string2byte(pubKeyModulus),
                    CryptoUtil.string2byte(pubKeyPublicExponent));
            } else if (pubKeyType.equals("ecc")) {
                  String pubKeyEncoded = config.getString(
                        prefix + certTag + ".pubkey.encoded");
               pubk = CryptoUtil.getPublicX509ECCKey(
                     CryptoUtil.string2byte(pubKeyEncoded));
            } else {
               CMS.debug( "CertRequestPanel::getPKCS10() - "
                        + "public key type is unsupported!" );
                throw new IOException( "public key type is unsupported" );
            }

            if (pubk != null) {
                CMS.debug("CertRequestPanel: got public key");
            } else {
                CMS.debug("CertRequestPanel: error getting public key null");
                throw new IOException( "public key is null" );
            }
            // get private key
            String privKeyID = config.getString(prefix + certTag + ".privkey.id");
            byte[] keyIDb = CryptoUtil.string2byte(privKeyID);

            PrivateKey privk = CryptoUtil.findPrivateKeyFromID(keyIDb);

            if (privk != null) {
                CMS.debug("CertRequestPanel: got private key");
            } else {
                CMS.debug("CertRequestPanel: error getting private key null");
            }

            // construct cert request
            String dn = config.getString(prefix + certTag + ".dn");

            PKCS10 certReq = null;
            certReq = CryptoUtil.createCertificationRequest(dn, pubk,
                    privk);
            byte[] certReqb = certReq.toByteArray();
            String certReqs = CryptoUtil.base64Encode(certReqb);

            return certReqs;
        } catch (Throwable e) {
            CMS.debug(e);
            context.put("errorString", e.toString());
            CMS.debug("CertUtil getPKCS10: " + e.toString());
            throw new IOException(e.toString());
        }
    }


/*
 * create requests so renewal can work on these initial certs
 */
    public static IRequest createLocalRequest(IRequestQueue queue, String serialNum, X509CertInfo info) throws EBaseException {
//        RequestId rid = new RequestId(serialNum);
        // just need a request, no need to get into a queue
//        IRequest r = new EnrollmentRequest(rid);
        CMS.debug("CertUtil: createLocalRequest for serial: "+ serialNum);
        IRequest req = queue.newRequest("enrollment", serialNum);
        CMS.debug("certUtil: newRequest called");
        req.setExtData("profile", "true");
        req.setExtData("requestversion", "1.0.0");
        req.setExtData("req_seq_num", "0");
        req.setExtData(IEnrollProfile.REQUEST_CERTINFO, info);
        req.setExtData(IEnrollProfile.REQUEST_EXTENSIONS,
                    new CertificateExtensions());
        req.setExtData("cert_request_type", "pkcs10");
        req.setExtData("requesttype", "enrollment");
        // note: more info needed... TBD
        // mark request as complete
        CMS.debug("certUtil: calling setRequestStatus");
        req.setRequestStatus(RequestStatus.COMPLETE);

        return req;
    }

    public static X509CertImpl createLocalCert(IConfigStore config, X509Key x509key,
            String prefix, String certTag, String type, Context context) throws IOException {

        CMS.debug("Creating local certificate... certTag=" + certTag);
        String profile = null;

        try {
            profile = config.getString(prefix + certTag + ".profile");
        } catch (Exception e) {}

        X509CertImpl cert = null;
        ICertificateAuthority ca = null;
        ICertificateRepository cr = null;

        try {
            String dn = config.getString(prefix + certTag + ".dn");
            Date date = new Date();

            X509CertInfo info = null;

            ca = (ICertificateAuthority) CMS.getSubsystem(
                    ICertificateAuthority.ID);
            cr = (ICertificateRepository) ca.getCertificateRepository();
            BigInteger serialNo = cr.getNextSerialNumber();
            if (type.equals("selfsign")) {
                CMS.debug("Creating local certificate... issuerdn=" + dn);
                CMS.debug("Creating local certificate... dn=" + dn);
                info = CryptoUtil.createX509CertInfo(x509key, serialNo.intValue(), dn, dn, date,
                        date);
            } else { 
                String issuerdn = config.getString("preop.cert.signing.dn", "");
                CMS.debug("Creating local certificate... issuerdn=" + issuerdn);
                CMS.debug("Creating local certificate... dn=" + dn);

                info = CryptoUtil.createX509CertInfo(x509key,
                        serialNo.intValue(), issuerdn, dn, date, date);
            }
            CMS.debug("Cert Template: " + info.toString());

            // cfu - create request to enable renewal
            try {
                IRequestQueue queue = ca.getRequestQueue();
                if (queue != null) {
                    IRequest req = createLocalRequest(queue, serialNo.toString(), info);
                    // set profileId - diff place than regular place
                    // consider stuffing a regular one here
                    CMS.debug("CertUtil profile name= "+profile);
                    int idx = profile.lastIndexOf('.');
//                    String[] profileName = profile.split(".");
//                    if (profileName.length == 0) {
                    if (idx == -1) {
                        CMS.debug("CertUtil profileName contains no .");
                        req.setExtData("profileid", profile);
                    } else {
                        String name = profile.substring(0, idx);
                        req.setExtData("profileid", name);
                    }
                    req.setExtData("req_key", x509key.toString());

                    CMS.debug("certUtil: before updateRequest");

                    // store request record in db
                    queue.updateRequest(req);
                } else {
                    CMS.debug("certUtil: requestQueue null");
                }
            } catch (Exception e) {
                CMS.debug("Creating local request exception:"+e.toString());
            }

            String instanceRoot = config.getString("instanceRoot");

            CertInfoProfile processor = new CertInfoProfile(
                    instanceRoot + "/conf/" + profile);

            processor.populate(info);

            String caPriKeyID = config.getString(
                    prefix + "signing" + ".privkey.id");
            org.mozilla.jss.crypto.PrivateKey caPrik = CryptoUtil.findPrivateKeyFromID(
                    CryptoUtil.string2byte(caPriKeyID));

            if( caPrik == null ) {
                CMS.debug( "CertUtil::createSelfSignedCert() - "
                         + "CA private key is null!" );
                throw new IOException( "CA private key is null" );
            } else {
                CMS.debug("CertUtil createSelfSignedCert: got CA private key");
            }

            String keyAlgo = x509key.getAlgorithm();
            CMS.debug("key algorithm is " + keyAlgo);
            String caSigningKeyType = 
                 config.getString("preop.cert.signing.keytype","rsa");
            CMS.debug("CA Signing Key type " + caSigningKeyType);
            if (caSigningKeyType.equals("ecc")) {
              CMS.debug("Signing ECC certificate");
              cert = CryptoUtil.signECCCert(caPrik, info);
            } else {
              CMS.debug("Signing RSA certificate");
              cert = CryptoUtil.signCert(caPrik, info,
                    SignatureAlgorithm.RSASignatureWithSHA1Digest);
            }

            if (cert != null) {
                CMS.debug("CertUtil createSelfSignedCert: got cert signed");
            }
        } catch (Exception e) {
            CMS.debug(e);
            CMS.debug("NamePanel configCert() exception caught:" + e.toString());
        }

        if (cr == null) {
            context.put("errorString",
                    "Ceritifcate Authority is not ready to serve.");
            throw new IOException("Ceritifcate Authority is not ready to serve.");
        }
        ICertRecord record = (ICertRecord) cr.createCertRecord(
                cert.getSerialNumber(), cert, null);

        try {
            cr.addCertificateRecord(record);
            CMS.debug(
                    "NamePanel configCert: finished adding certificate record.");
        } catch (Exception e) {
            CMS.debug(
                    "NamePanel configCert: failed to add certificate record. Exception: "
                            + e.toString());
            try {
                cr.deleteCertificateRecord(record.getSerialNumber());
                cr.addCertificateRecord(record);
            } catch (Exception ee) {
                CMS.debug("NamePanel update: Exception: " + ee.toString());
            }
        }

        return cert;
    }

    public static void addUserCertificate(X509CertImpl cert) {
        IConfigStore cs = CMS.getConfigStore();
        int num=0;
        try {
            num = cs.getInteger("preop.subsystem.count", 0);
        } catch (Exception e) {
        }
        IUGSubsystem system = (IUGSubsystem) (CMS.getSubsystem(IUGSubsystem.ID));
        String id = "user"+num;

        try { 
          String sysType = cs.getString("cs.type", "");
          String machineName = cs.getString("machineName", "");
          String securePort = cs.getString("service.securePort", "");
          id = sysType + "-" + machineName + "-" + securePort;
        } catch (Exception e1) {
          // ignore
        }

        num++;
        cs.putInteger("preop.subsystem.count", num);
        cs.putInteger("subsystem.count", num);

        try {
            cs.commit(false);
        } catch (Exception e) {
        }

        IUser user = null;
        X509CertImpl[] certs = new X509CertImpl[1];
        CMS.debug("CertUtil addUserCertificate starts");
        try {
            user = system.createUser(id);
            user.setFullName(id);
            user.setEmail("");
            user.setPassword("");
            user.setUserType("agentType");
            user.setState("1");
            user.setPhone("");
            certs[0] = cert;
            user.setX509Certificates(certs);
            system.addUser(user);
            CMS.debug("CertUtil addUserCertificate: successfully add the user");
        } catch (LDAPException e) {
            CMS.debug("CertUtil addUserCertificate" + e.toString());
            if (e.getLDAPResultCode() != LDAPException.ENTRY_ALREADY_EXISTS) {
                try {
                    user = system.getUser(id);
                    user.setX509Certificates(certs);
                } catch (Exception ee) {
                    CMS.debug("CertUtil addUserCertificate: successfully find the user");
                }
            }
        } catch (Exception e) {
            CMS.debug("CertUtil addUserCertificate addUser " + e.toString());
        }

        try {
            system.addUserCert(user);
            CMS.debug("CertUtil addUserCertificate: successfully add the user certificate");
        } catch (Exception e) {
            CMS.debug("CertUtil addUserCertificate exception="+e.toString());
        }

        IGroup group = null;
        String groupName = "Subsystem Group";

        try {
            group = system.getGroupFromName(groupName);
            if (!group.isMember(id)) {
                group.addMemberName(id);
                system.modifyGroup(group);
                CMS.debug("CertUtil addUserCertificate: update: successfully added the user to the group.");
            }
        } catch (Exception e) {
            CMS.debug("CertUtil addUserCertificate update: modifyGroup " + e.toString());
        }
    }

    /*
     * formats a cert fingerprints
     */
    public static String fingerPrintFormat(String content) {
        if (content == null || content.length() == 0) {
            return "";
        }

        StringBuffer result = new StringBuffer();
        result.append("Fingerprints:\n");
        int index = 0;

        while (content.length() >= LINE_COUNT) {
            result.append(content.substring(0, LINE_COUNT));
            result.append("\n");
            content = content.substring(LINE_COUNT);
        }
        if (content.length() > 0)
            result.append(content);
            result.append("\n");

        return result.toString();
    }

    public static boolean privateKeyExistsOnToken(String certTag,
      String tokenname, String nickname) {
        IConfigStore cs = CMS.getConfigStore();
        String givenid = "";
        try {
            givenid = cs.getString("preop.cert."+certTag+".privkey.id");
        } catch (Exception e) {
            CMS.debug("CertUtil privateKeyExistsOnToken: we did not generate private key yet.");
            return false;
        }

        String fullnickname = nickname;

        boolean hardware = false;
        if (!tokenname.equals("internal") && !tokenname.equals("Internal Key Storage Token")) {
            hardware = true;
            fullnickname = tokenname+":"+nickname;
        }

        X509Certificate cert = null;
        CryptoManager cm = null;
        try {
            cm = CryptoManager.getInstance();
            cert = cm.findCertByNickname(fullnickname);
        } catch (Exception e) {
            CMS.debug("CertUtil privateKeyExistsOnToken: nickname="+fullnickname+" Exception:"+e.toString());
            return false;
        }

        PrivateKey privKey = null;
        try {
            privKey = cm.findPrivKeyByCert(cert);
        } catch (Exception e) {
            CMS.debug("CertUtil privateKeyExistsOnToken: cant find private key ("+fullnickname+") exception: "+e.toString());
            return false;
        }

        if (privKey == null) {
            CMS.debug("CertUtil privateKeyExistsOnToken: cant find private key ("+fullnickname+")");
            return false;
        } else {
            String str = "";
            try {
                str = CryptoUtil.byte2string(privKey.getUniqueID());
            } catch (Exception e) {
            CMS.debug("CertUtil privateKeyExistsOnToken: encode string Exception: "+e.toString());
            }

            if (str.equals(givenid)) {
                CMS.debug("CertUtil privateKeyExistsOnToken: find the private key on the token.");
                return true;
            }
        }

        return false;
    }
}
