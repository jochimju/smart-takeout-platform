package com.sky.payment;

import com.sky.utils.properties.WeChatProperties;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Reject incomplete production payment configuration before serving requests. */
@Component @RequiredArgsConstructor
public class PaymentConfigurationGuard implements InitializingBean {
    private final Environment environment;
    private final WeChatProperties properties;
    @Override public void afterPropertiesSet() {
        var profiles=Arrays.asList(environment.getActiveProfiles());
        if(!profiles.contains("prod") && (profiles.contains("dev") || profiles.contains("test"))) return;
        required(properties.getAppid(),"appid");required(properties.getMchid(),"mchid");required(properties.getMchSerialNo(),"merchant certificate serial");
        readable(properties.getPrivateKeyFilePath(),"merchant private key");
        if(properties.getApiV3Key()==null || properties.getApiV3Key().getBytes(StandardCharsets.UTF_8).length!=32)
            throw new IllegalStateException("Payment API v3 key must contain 32 bytes");
        https(properties.getNotifyUrl(),"payment callback");
        if(properties.getRefundNotifyUrl()!=null && !properties.getRefundNotifyUrl().isBlank()) https(properties.getRefundNotifyUrl(),"refund callback");
        boolean trust=properties.isAutoUpdateCertificates();
        if(properties.getPublicKeyId()!=null && !properties.getPublicKeyId().isBlank()) {
            readable(properties.getPublicKeyFilePath(),"WeChat payment public key");trust=true;
        } else if(properties.getPublicKeyFilePath()!=null && !properties.getPublicKeyFilePath().isBlank())
            throw new IllegalStateException("Payment public key ID is missing");
        for(var entry:properties.getPublicKeyPaths().entrySet()) {required(entry.getKey(),"public key ID");readable(entry.getValue(),"payment public key");trust=true;}
        for(var file:properties.getPlatformCertificatePaths()) if(file!=null && !file.isBlank()) {readable(file,"platform certificate");trust=true;}
        if(properties.getWeChatPayCertFilePath()!=null && !properties.getWeChatPayCertFilePath().isBlank()) {readable(properties.getWeChatPayCertFilePath(),"platform certificate");trust=true;}
        if(!trust) throw new IllegalStateException("Payment response/callback trust material is missing");
    }
    private static void required(String value,String name) {if(value==null || value.isBlank()) throw new IllegalStateException("Payment configuration missing: "+name);}
    private static void readable(String value,String name) {required(value,name);if(!Files.isRegularFile(Path.of(value)) || !Files.isReadable(Path.of(value))) throw new IllegalStateException("Payment configuration file is not readable: "+name);}
    private static void https(String value,String name) {
        required(value,name);var uri=java.net.URI.create(value);
        if(!"https".equals(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || uri.getFragment()!=null)
            throw new IllegalStateException("Payment callback must use a valid HTTPS URL: "+name);
    }
}
