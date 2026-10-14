package com.sky.payment;

import com.sky.utils.properties.WeChatProperties;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class PaymentConfigurationGuardTest {
    @TempDir Path directory;
    @Test void localDevelopmentNeedsNoMerchantButProdOverridesDevelopmentBypass() {
        var env=new MockEnvironment();env.setActiveProfiles("dev");var guard=new PaymentConfigurationGuard(env,new WeChatProperties());
        assertDoesNotThrow(guard::afterPropertiesSet);env.setActiveProfiles("dev","prod");assertThrows(IllegalStateException.class,guard::afterPropertiesSet);
    }
    @Test void productionRequiresCredentialsTrustMaterialAndHttpsCallback() throws Exception {
        var env=new MockEnvironment();env.setActiveProfiles("prod");var properties=new WeChatProperties();
        properties.setAppid("app");properties.setMchid("merchant");properties.setMchSerialNo("serial");
        properties.setPrivateKeyFilePath(Files.writeString(directory.resolve("merchant.pem"),"fixture").toString());
        properties.setApiV3Key("0123456789abcdef0123456789abcdef");properties.setNotifyUrl("https://merchant.example/notify/paySuccess");
        properties.setAutoUpdateCertificates(true);var guard=new PaymentConfigurationGuard(env,properties);assertDoesNotThrow(guard::afterPropertiesSet);
        properties.setNotifyUrl("http://merchant.example/notify/paySuccess");assertThrows(IllegalStateException.class,guard::afterPropertiesSet);
        properties.setNotifyUrl("https://merchant.example/notify/paySuccess");properties.setAutoUpdateCertificates(false);assertThrows(IllegalStateException.class,guard::afterPropertiesSet);
        properties.setPublicKeyId("PUB_KEY_ID_FIXTURE");properties.setPublicKeyFilePath(properties.getPrivateKeyFilePath());assertDoesNotThrow(guard::afterPropertiesSet);
        properties.setPrivateKeyFilePath(directory.resolve("missing.pem").toString());assertThrows(IllegalStateException.class,guard::afterPropertiesSet);
    }
}
