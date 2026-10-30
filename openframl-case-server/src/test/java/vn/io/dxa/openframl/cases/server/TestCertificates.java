//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.cases.server;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.concurrent.atomic.AtomicLong;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/** Tests only: a throw-away certificate authority that writes PEM files for gRPC's TLS credentials. */
final class TestCertificates {

    private static final AtomicLong SERIAL = new AtomicLong(1);

    private final Path directory;
    private final String name;
    private final KeyPair key;
    private final X509Certificate certificate;

    private TestCertificates(Path directory, String name, KeyPair key, X509Certificate certificate) {
        this.directory = directory;
        this.name = name;
        this.key = key;
        this.certificate = certificate;
    }

    static TestCertificates authority(Path directory, String name) {
        KeyPair key = keyPair();
        X509Certificate certificate = sign(new X500Name("CN=" + name), key, new X500Name("CN=" + name), key, true,
                null);
        TestCertificates ca = new TestCertificates(directory, name, key, certificate);
        ca.write(name + "-ca.pem", certificate);
        return ca;
    }

    Path caFile() {
        return directory.resolve(name + "-ca.pem");
    }

    /** Issues a certificate for {@code commonName}; returns the chain file, the key file is {@code <file>.key}. */
    Path issue(String commonName, String dnsName) {
        KeyPair subject = keyPair();
        X509Certificate issued = sign(new X500Name("CN=" + commonName), subject,
                new X500Name("CN=" + name), key, false, dnsName);
        String file = name + "-" + commonName + ".pem";
        write(file, issued);
        try {
            Files.writeString(directory.resolve(file + ".key"), "-----BEGIN PRIVATE KEY-----\n"
                    + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(subject.getPrivate().getEncoded())
                    + "\n-----END PRIVATE KEY-----\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return directory.resolve(file);
    }

    private void write(String file, X509Certificate cert) {
        try {
            Files.writeString(directory.resolve(file), "-----BEGIN CERTIFICATE-----\n"
                    + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(cert.getEncoded())
                    + "\n-----END CERTIFICATE-----\n");
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static X509Certificate sign(X500Name subject, KeyPair subjectKey, X500Name issuer, KeyPair issuerKey,
            boolean ca, String dnsName) {
        try {
            Instant now = Instant.now();
            JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(issuer,
                    BigInteger.valueOf(SERIAL.getAndIncrement()), Date.from(now.minus(Duration.ofHours(1))),
                    Date.from(now.plus(Duration.ofDays(1))), subject, subjectKey.getPublic());
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(ca));
            if (dnsName != null) {
                builder.addExtension(Extension.subjectAlternativeName, false,
                        new GeneralNames(new GeneralName(GeneralName.dNSName, dnsName)));
            }
            return new JcaX509CertificateConverter().getCertificate(builder.build(
                    new JcaContentSignerBuilder("SHA256withECDSA").build(issuerKey.getPrivate())));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static KeyPair keyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(256);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
