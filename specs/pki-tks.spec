Name:             pki-tks
Version:          9.0.11
Release:          1%{?dist}
Summary:          Certificate System - Token Key Service
URL:              http://pki.fedoraproject.org/
License:          GPLv2
Group:            System Environment/Daemons

BuildArch:        noarch

BuildRoot:        %{_tmppath}/%{name}-%{version}-%{release}-root-%(%{__id_u} -n)

# specify '_unitdir' macro for platforms that don't use 'systemd'
%if 0%{?rhel} || 0%{?fedora} < 16
%define           _unitdir /lib/systemd/system
%endif

BuildRequires:    cmake
BuildRequires:    java-devel >= 1:1.6.0
BuildRequires:    nspr-devel
BuildRequires:    nss-devel
%if 0%{?fedora} >= 16
BuildRequires:    jpackage-utils >= 0:1.7.5-10
BuildRequires:    jss >= 4.2.6-24
BuildRequires:    pki-common >= 9.0.15
BuildRequires:    pki-util >= 9.0.15
BuildRequires:    systemd-units
%else
BuildRequires:    jpackage-utils
BuildRequires:    jss >= 4.2.6-24
BuildRequires:    pki-common
BuildRequires:    pki-util
%endif

Requires:         java >= 1:1.6.0
Requires:         pki-tks-theme >= 9.0.0
%if 0%{?fedora} >= 16
Requires:         pki-common >= 9.0.15
Requires:         pki-selinux >= 9.0.15
Requires(post):   systemd-units
Requires(preun):  systemd-units
Requires(postun): systemd-units
%else
%if 0%{?fedora} >= 15
Requires:         pki-common
Requires:         pki-selinux
Requires(post):   chkconfig
Requires(preun):  chkconfig
Requires(preun):  initscripts
Requires(postun): initscripts
# Details:
#
#     * https://fedoraproject.org/wiki/Features/var-run-tmpfs
#     * https://fedoraproject.org/wiki/Tmpfiles.d_packaging_draft
#
Requires:         initscripts
%else 
Requires:         pki-common
Requires:         pki-selinux
Requires(post):   chkconfig
Requires(preun):  chkconfig
Requires(preun):  initscripts
Requires(postun): initscripts
%endif
%endif

Source0:          http://pki.fedoraproject.org/pki/sources/%{name}/%{name}-%{version}.tar.gz

%description
Certificate System (CS) is an enterprise software system designed
to manage enterprise Public Key Infrastructure (PKI) deployments.

The Token Key Service (TKS) is an optional PKI subsystem that manages the
master key(s) and the transport key(s) required to generate and distribute
keys for hardware tokens.  TKS provides the security between tokens and an
instance of Token Processing System (TPS), where the security relies upon the
relationship between the master key and the token keys.  A TPS communicates
with a TKS over SSL using client authentication.

TKS helps establish a secure channel (signed and encrypted) between the token
and the TPS, provides proof of presence of the security token during
enrollment, and supports key changeover when the master key changes on the
TKS.  Tokens with older keys will get new token keys.

Because of the sensitivity of the data that TKS manages, TKS should be set up
behind the firewall with restricted access.

For deployment purposes, a TKS requires the following components from the PKI
Core package:

  * pki-setup
  * pki-native-tools
  * pki-util
  * pki-java-tools
  * pki-common
  * pki-selinux

and can also make use of the following optional components from the PKI Core
package:

  * pki-util-javadoc
  * pki-java-tools-javadoc
  * pki-common-javadoc
  * pki-silent

Additionally, Certificate System requires ONE AND ONLY ONE of the following
"Mutually-Exclusive" PKI Theme packages:

  * dogtag-pki-theme (Dogtag Certificate System deployments)
  * redhat-pki-theme (Red Hat Certificate System deployments)


%prep


%setup -q


%clean
%{__rm} -rf %{buildroot}


%build
%{__mkdir_p} build
cd build
%cmake -DVAR_INSTALL_DIR:PATH=/var -DBUILD_PKI_TKS:BOOL=ON -DJAVA_LIB_INSTALL_DIR=%{_jnidir} -DSYSTEMD_LIB_INSTALL_DIR=%{_unitdir} ..
%{__make} VERBOSE=1 %{?_smp_mflags}


%install
%{__rm} -rf %{buildroot}
cd build
%{__make} install DESTDIR=%{buildroot} INSTALL="install -p"

%if 0%{?fedora} >= 15
# Details:
#
#     * https://fedoraproject.org/wiki/Features/var-run-tmpfs
#     * https://fedoraproject.org/wiki/Tmpfiles.d_packaging_draft
#
%{__mkdir_p} %{buildroot}%{_sysconfdir}/tmpfiles.d
# generate 'pki-tks.conf' under the 'tmpfiles.d' directory
echo "D /var/lock/pki 0755 root root -"     >  %{buildroot}%{_sysconfdir}/tmpfiles.d/pki-tks.conf
echo "D /var/lock/pki/tks 0755 root root -" >> %{buildroot}%{_sysconfdir}/tmpfiles.d/pki-tks.conf
echo "D /var/run/pki 0755 root root -"      >> %{buildroot}%{_sysconfdir}/tmpfiles.d/pki-tks.conf
echo "D /var/run/pki/tks 0755 root root -"  >> %{buildroot}%{_sysconfdir}/tmpfiles.d/pki-tks.conf
%endif

%if 0%{?fedora} >= 16
%{__rm} %{buildroot}%{_initrddir}/pki-tksd
%else
%{__rm} -rf %{buildroot}%{_sysconfdir}/systemd/system/pki-tksd.target.wants
%{__rm} -rf %{buildroot}%{_unitdir}
%endif

# tomcat6 has changed how TOMCAT_LOG is used.
# Need to adjust accordingly
# This macro will be executed in the postinstall scripts
%define fix_tomcat_log() (                                                   \
if [ -d /etc/sysconfig/pki/%i ]; then                                        \
  for F in `find /etc/sysconfig/pki/%1 -type f`; do                          \
    instance=`basename $F`                                                   \
    if [ -f /etc/sysconfig/$instance ]; then                                 \
        sed -i -e 's/catalina.out/tomcat-initd.log/' /etc/sysconfig/$instance \
    fi                                                                       \
  done                                                                       \
fi                                                                           \
)

%if 0%{?rhel} || 0%{?fedora} < 16
%post
# This adds the proper /etc/rc*.d links for the script
/sbin/chkconfig --add pki-tksd || :
%fix_tomcat_log tks

%preun
if [ $1 = 0 ] ; then
    /sbin/service pki-tksd stop >/dev/null 2>&1
    /sbin/chkconfig --del pki-tksd || :
fi

%postun
if [ "$1" -ge "1" ] ; then
    /sbin/service pki-tksd condrestart >/dev/null 2>&1 || :
fi
%else 
%post 
# Attempt to update ALL old "TKS" instances to "systemd"
if [ -d /etc/sysconfig/pki/tks ]; then
    for inst in `ls /etc/sysconfig/pki/tks`; do
        if [ ! -e "/etc/systemd/system/pki-tksd.target.wants/pki-tksd@${inst}.service" ]; then
            ln -s "/lib/systemd/system/pki-tksd@.service" \
                  "/etc/systemd/system/pki-tksd.target.wants/pki-tksd@${inst}.service"
            [ -L /var/lib/${inst}/${inst} ] && unlink /var/lib/${inst}/${inst}
            ln -s /usr/sbin/tomcat6-sysd /var/lib/${inst}/${inst}

            if [ -e /var/run/${inst}.pid ]; then
                kill -9 `cat /var/run/${inst}.pid` || :
                rm -f /var/run/${inst}.pid
                echo "pkicreate.systemd.servicename=pki-tksd@${inst}.service" >> \
                     /var/lib/${inst}/conf/CS.cfg || :
                /bin/systemctl daemon-reload >/dev/null 2>&1 || :
                /bin/systemctl restart pki-tksd@${inst}.service || :
            else 
                echo "pkicreate.systemd.servicename=pki-tksd@${inst}.service" >> \
                     /var/lib/${inst}/conf/CS.cfg || :
            fi
        fi
    done
fi
/bin/systemctl daemon-reload >/dev/null 2>&1 || :
%fix_tomcat_log tks

%preun
if [ $1 = 0 ] ; then
    /bin/systemctl --no-reload disable pki-tksd.target > /dev/null 2>&1 || :
    /bin/systemctl stop pki-tksd.target > /dev/null 2>&1 || :
fi

%postun
/bin/systemctl daemon-reload >/dev/null 2>&1 || :
if [ "$1" -ge "1" ] ; then
    /bin/systemctl try-restart pki-tksd.target >/dev/null 2>&1 || :
fi
%endif


%files
%defattr(-,root,root,-)
%doc base/tks/LICENSE
%if 0%{?fedora} >= 16
%dir %{_sysconfdir}/systemd/system/pki-tksd.target.wants
%{_unitdir}/pki-tksd@.service
%{_unitdir}/pki-tksd.target
%else 
%{_initrddir}/pki-tksd
%endif
%{_javadir}/pki/pki-tks-%{version}.jar
%{_javadir}/pki/pki-tks.jar
%dir %{_datadir}/pki/tks
%{_datadir}/pki/tks/conf/
%{_datadir}/pki/tks/setup/
%{_datadir}/pki/tks/webapps/
%dir %{_localstatedir}/lock/pki/tks
%dir %{_localstatedir}/run/pki/tks
%if 0%{?fedora} >= 15
# Details:
#
#     * https://fedoraproject.org/wiki/Features/var-run-tmpfs
#     * https://fedoraproject.org/wiki/Tmpfiles.d_packaging_draft
#
%config(noreplace) %{_sysconfdir}/tmpfiles.d/pki-tks.conf
%endif


%changelog
* Tue Dec 11 2012 Andrew Wnuk<awnuk@redhat.com> 9.0.11-1
- Bugzilla Bug #861467 - Directory authenticated user certificate enrollments
  fail when anonymous access disabled.

* Tue Apr 10 2012 Christina Fu <cfu@redhat.com> 9.0.10-2
- Bugzilla Bug #745278 - [RFE] ECC encryption keys cannot be archived

* Fri Mar 16 2012 Ade Lee <alee@redhat.com> 9.0.10-1
- BZ 802396 - Change location of TOMCAT_LOG to match tomcat6 changes

* Fri Mar  9 2012 Matthew Harmsen <mharmsen@redhat.com> 9.0.9-1
- Bugzilla Bug #796006 - Get DOGTAG_9_BRANCH GIT repository in-sync
  with DOGTAG_9_BRANCH SVN repository . . .
- Bugzilla Bug #787806 - RSA should be default selection for transport
  key till "ECC phase 4" is implemented

* Wed Feb 22 2012 Matthew Harmsen <mharmsen@redhat.com> 9.0.8-2
- Add '-DSYSTEMD_LIB_INSTALL_DIR' override flag to 'cmake' to address changes
  in fundamental path structure in Fedora 17

* Fri Oct 28 2011 Matthew Harmsen <mharmsen@redhat.com> 9.0.8-1
- Bugzilla Bug #749945 - Installation error reported during CA, DRM,
  OCSP, and TKS package installation . . .

* Thu Sep 22 2011 Jack Magne <jmagne@redhat.com> 9.0.7-1
- Bugzilla Bug #730146 - SSL handshake picks non-FIPS ciphers in FIPS mode (cfu)
- Bugzilla Bug #730162 - TPS/TKS token enrollment failure in FIPS mode
  (hsm+NSS).  (jmagne)
- Bugzilla Bug #734590 - Refactor JNI libraries for Fedora 16+ . . . (mharmsen)
- Bugzilla Bug #699809 - Convert CS to use systemd (alee)

* Mon Sep 12 2011 Matthew Harmsen <mharmsen@redhat.com> 9.0.6-1
- Bugzilla Bug #734590 - Refactor JNI libraries for Fedora 16+ . . .
- Bugzilla Bug #699809 - Convert CS to use systemd (alee)

* Tue Sep 6 2011 Ade Lee <alee@redhat.com> 9.0.5-1
- Bugzilla Bug #699809 - Convert CS to use systemd

* Tue Aug 23 2011 Ade Lee <alee@redhat.com> 9.0.4-1
- Bugzilla Bug #712931 - CS requires too many ports
  to be open in the FW

* Thu Jul 14 2011 Matthew Harmsen <mharmsen@redhat.com> 9.0.3-1
- Bugzilla Bug #693815 - /var/log/tomcat6/catalina.out owned by pkiuser
  (jdennis)
- Bugzilla Bug #699837 - service command is not fully backwards
  compatible with Dogtag pki subsystems (mharmsen)
- Bugzilla Bug #649910 - Console: an auditor or agent can be added to an
  administrator group. (jmagne)
- Bugzilla Bug #669226 - Remove Legacy Build System (mharmsen)
- Updated release of 'jss'

* Tue Apr 26 2011 Matthew Harmsen <mharmsen@redhat.com> 9.0.2-1
- Bugzilla Bug #693815 - /var/log/tomcat6/catalina.out owned by pkiuser
- Bugzilla Bug #699837 - service command is not fully backwards compatible
  with Dogtag pki subsystems

* Fri Mar 25 2011 Matthew Harmsen <mharmsen@redhat.com> 9.0.1-1
- Bugzilla Bug #690950 - Update Dogtag Packages for Fedora 15 (beta)
- Bugzilla Bug #683581 - CA configuration with ECC(Default
  EC curve-nistp521) CA fails with 'signing operation failed'
- Bugzilla Bug #684381 - CS.cfg specifies incorrect type of comments
- Require "jss >= 4.2.6-15" as a build and runtime requirement

* Wed Dec 1 2010 Matthew Harmsen <mharmsen@redhat.com> 9.0.0-1
- Updated Dogtag 1.3.x --> Dogtag 2.0.0 --> Dogtag 9.0.0
- Bugzilla Bug #620925 - CC: auditor needs to be able to download audit logs
  in the java subsystems
- Bugzilla Bug #583825 - CC: Obsolete servlets to be removed from web.xml
  as part of CC interface review
- Bugzilla Bug #583823 - CC: Auditing issues found as result of
  CC - interface review
- Bugzilla Bug #558100 - host challenge of the Secure Channel needs to be
  generated on TKS instead of TPS.
- Bugzilla Bug #630121 - OCSP responder lacking option to delete or disable
  a CA that it serves
- Bugzilla Bug #504061 - ECC: unable to install subsystems - phase 1
- Bugzilla Bug #637330 - CC feature: Key Management - provide signature
  verification functions (JAVA subsystems)
- Bugzilla Bug #555927 - rhcs80 - AgentRequestFilter servlet and
  port fowarding for agent services
- Bugzilla Bug #631179 - Administrator is not allowed to remove
  ocsp signing certificate using console
- Bugzilla Bug #638242 - Installation Wizard: at SizePanel, fix selection of
  signature algorithm; and for ECC curves
- Bugzilla Bug #529945 - (Instructions and sample only) CS 8.0 GA release --
  DRM and TKS do not seem to have CRL checking enabled
- Bugzilla Bug #609641 - CC: need procedure (and possibly tools) to help
  correctly set up CC environment
- Bugzilla Bug #651916 - kra and ocsp are using incorrect ports
  to talk to CA and complete configuration in DonePanel
- Bugzilla Bug #651977 - turn off ssl2 for java servers (server.xml)
- Bugzilla Bug #489385 - references to rhpki
- Bugzilla Bug #649910 - Console: an auditor or agent can be added to
  an administrator group.
- Bugzilla Bug #632425 - Port to tomcat6
- Bugzilla Bug #638377 - Generate PKI UI components which exclude
  a GUI interface
- Bugzilla Bug #653576 - tomcat5 does not always run filters on servlets
  as expected
- Bugzilla Bug #642357 - CC Feature- Self-Test plugins only check for
  validity
- Bugzilla Bug #643206 - New CMake based build system for Dogtag
- Bugzilla Bug #499494 - change CA defaults to SHA2
- Bugzilla Bug #649343 - Publishing queue should recover from CA crash.
- Bugzilla Bug #491183 - rhcs rfe - add rfc 4523 support for pkiUser and
  pkiCA, obsolete 2252 and 2256
- Bugzilla Bug #223346 - Two conflicting ACL list definitions in source
  repository
- Bugzilla Bug #663546 - Disable the functionalities that are not exposed
  in the console
- Bugzilla Bug #656733 - Standardize jar install location and jar names
- Bugzilla Bug #661142 - Verification should fail when
  a revoked certificate is added
- Bugzilla Bug #662127 - CC doc Error: SignedAuditLog expiration time
  interface is no longer available through console
- Bugzilla Bug #531137 - RHCS 7.1 - Running out of Java Heap Memory During
  CRL Generation
- Bugzilla Bug #672111 - CC doc: certServer.usrgrp.administration missing
  information
- Bugzilla Bug #583825 - CC: Obsolete servlets to be removed from web.xml
  as part of the CC interface review
- Bugzilla Bug #656665 - Please Update Spec File to use 'ghost' on files
  in /var/run and /var/lock
- Bugzilla Bug #674917 - Restore identification of Tomcat-based PKI subsystem
  instances

* Wed Aug 04 2010 Matthew Harmsen <mharmsen@redhat.com> 1.3.3-1
- Bugzilla Bug #606556 - Add known session key test to TKS self test set
- Bugzilla Bug #608086 - CC: CA, OCSP, and DRM need to add more audit calls
- Bugzilla Bug #527593 - More robust signature digest alg, like SHA256
  instead of SHA1 for ECC
- Bugzilla Bug #528236 - rhcs80 web conf wizard - cannot specify CA signing
  algorithm
- Bugzilla Bug #533510 - tps exception, cannot start when signed audit true
- Bugzilla Bug #529280 - TPS returns HTTP data without ending in 0rn
  per RFC 2616
- Bugzilla Bug #498299 - Should not be able to change the status manually
  on a token marked as permanently lost or destroyed
- Bugzilla Bug #554892 - configurable frequency signed audit
- Bugzilla Bug #500700 - tps log rotation
- Bugzilla Bug #562893 - tps shutdown if audit logs full
- Bugzilla Bug #557346 - Name Constraints Extension cant be marked critical
- Bugzilla Bug #556152 - ACL changes to CA and OCSP
- Bugzilla Bug #556167 - ACL changes to CA and OCSP
- Bugzilla Bug #581004 - add more audit logging to the TPS
- Bugzilla Bug #566517 - CC: Add client auth to OCSP publishing, and move
  to a client-auth port
- Bugzilla Bug #565842 - Clone config throws errors - fix key_algorithm
- Bugzilla Bug #581017 - enabling log signing from tps ui pages causes tps
  crash
- Bugzilla Bug #581004 - add more audit logs
- Bugzilla Bug #595871 - CC: TKS needed audit message changes
- Bugzilla Bug #598752 - Common Criteria: TKS ACL analysis result.
- Bugzilla Bug #598666 - Common Criteria: incorrect ACLs for signedAudit
- Bugzilla Bug #504905 - Smart card renewal should load old encryption cert
  on the token.
- Bugzilla Bug #499292 - TPS - Enrollments where keys are recovered need
  to do both GenerateNewKey and RecoverLast operation for encryption key.
- Bugzilla Bug #498299 - fix case where no transitions available
- Bugzilla Bug #595391 - session domain table to be moved to ldap
- Bugzilla Bug #598643 - Common Criteria: incorrect ACLs (non-existing groups)
- Bugzilla Bug #504359 - pkiconsole - Administrator Group's Description
  References Fedora

* Mon Apr 26 2010 Ade Lee <alee@redhat.com> 1.3.2-1
- Bugzilla Bug 584917- Can not access CA Configuration Web UI
  after CA installation

* Tue Feb 16 2010 Matthew Harmsen <mharmsen@redhat.com> 1.3.1-2
- Bugzilla Bug #566059 - Add 'pki-console' as a runtime dependency
  for CA, KRA, OCSP, and TKS . . .

* Mon Feb 08 2010 Matthew Harmsen <mharmsen@redhat.com> 1.3.1-1
- Bugzilla Bug #562986 - Supply convenience symlink(s) for backwards
  compatibility (rename jar files as appropriate)

* Fri Jan 15 2010 Kevin Wright <kwright@redhat.com> 1.3.0-4
- Removed BuildRequires: dogtag-pki-tks-ui

* Fri Jan 08 2010 Matthew Harmsen <mharmsen@redhat.com> 1.3.0-3
- Corrected "|| :" scriptlet logic (see Bugzilla Bug #475895)
- Bugzilla Bug #553075 - Apply "registry" logic to pki-tks . . .
- Bugzilla Bug #553847 - New Package for Dogtag PKI: pki-tks

* Mon Dec 14 2009 Kevin Wright <kwright@redhat.com> 1.3.0-2
- Removed 'with exceptions' from License

* Fri Oct 16 2009 Ade Lee <alee@redhat.com> 1.3.0-1
- Bugzilla Bug #X - Packaging for Fedora Dogtag
