package com.sky.utils;

import com.sky.utils.properties.WeChatProperties;
import com.wechat.pay.contrib.apache.httpclient.auth.*;
import com.wechat.pay.contrib.apache.httpclient.cert.CertificatesManager;
import com.wechat.pay.contrib.apache.httpclient.util.PemUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;

/** Select the trusted signing key by serial/id; unknown keys always fail closed. */
@Component
@RequiredArgsConstructor
public class PaymentVerifier implements Verifier {
    private final WeChatProperties properties;
    private volatile Verifier automatic;
    @Override public boolean verify(String serial, byte[] message, String signature) {
        try {
            if (serial == null || signature == null || signature.startsWith("WECHATPAY/SIGNTEST/")) return false;
            String path = properties.getPublicKeyPaths().get(serial);
            if(serial.equals(properties.getPublicKeyId()) && properties.getPublicKeyFilePath()!=null && !properties.getPublicKeyFilePath().isBlank()) path=properties.getPublicKeyFilePath();
            PublicKey key = null;
            if (path != null) {
                String pem = Files.readString(Path.of(path), StandardCharsets.US_ASCII)
                        .replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "").replaceAll("\\s", "");
                key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(pem)));
            } else {
                for (String certificatePath : certificatePaths()) {
                    try (var input = Files.newInputStream(Path.of(certificatePath))) {
                        var certificate = PemUtil.loadCertificate(input);
                        if (certificate.getSerialNumber().toString(16).equalsIgnoreCase(serial)) {
                            certificate.checkValidity(); key = certificate.getPublicKey(); break;
                        }
                    }
                }
            }
            if (key == null) return properties.isAutoUpdateCertificates() && automatic().verify(serial, message, signature);
            Signature verifier = Signature.getInstance("SHA256withRSA"); verifier.initVerify(key); verifier.update(message);
            return verifier.verify(Base64.getDecoder().decode(signature));
        } catch (Exception ex) { return false; }
    }
    private List<String> certificatePaths() {
        var paths = new ArrayList<>(properties.getPlatformCertificatePaths());
        if (properties.getWeChatPayCertFilePath() != null && !properties.getWeChatPayCertFilePath().isBlank())
            paths.add(properties.getWeChatPayCertFilePath());
        return paths.stream().filter(path -> path!=null && !path.isBlank()).distinct().toList();
    }
    private synchronized Verifier automatic() throws Exception {
        if (automatic == null) {
            try (var input = Files.newInputStream(Path.of(properties.getPrivateKeyFilePath()))) {
                var credentials = new WechatPay2Credentials(properties.getMchid(),
                        new PrivateKeySigner(properties.getMchSerialNo(), PemUtil.loadPrivateKey(input)));
                var manager = CertificatesManager.getInstance();
                manager.putMerchant(properties.getMchid(), credentials, properties.getApiV3Key().getBytes(StandardCharsets.UTF_8));
                automatic = manager.getVerifier(properties.getMchid());
            }
        }
        return automatic;
    }
    @Override public X509Certificate getValidCertificate() {
        try {
            for (String path : certificatePaths()) try (var input = Files.newInputStream(Path.of(path))) {
                var certificate = PemUtil.loadCertificate(input); certificate.checkValidity(); return certificate;
            }
            return properties.isAutoUpdateCertificates() ? automatic().getValidCertificate() : null;
        } catch (Exception ex) { throw new IllegalStateException("No valid payment verification certificate", ex); }
    }
}
