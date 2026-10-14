package com.sky.utils.properties;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "sky.wechat")
@Data
public class WeChatProperties {

    private String appid; //小程序的appid
    private String secret; //小程序的秘钥
    private String mchid; //商户号
    private String mchSerialNo; //商户API证书的证书序列号
    private String privateKeyFilePath; //商户私钥文件
    private String apiV3Key; //证书解密的密钥
    private String weChatPayCertFilePath; //平台证书
    private String notifyUrl; //支付成功的回调地址
    private String refundNotifyUrl; //退款成功的回调地址
    private String apiBaseUrl = "https://api.mch.weixin.qq.com";
    private java.util.List<String> platformCertificatePaths = new java.util.ArrayList<>();
    /** Public key id -> PEM path. Supports certificate/public-key overlap during rotation. */
    private java.util.Map<String,String> publicKeyPaths = new java.util.HashMap<>();
    private boolean autoUpdateCertificates;
    private String publicKeyId;
    private String publicKeyFilePath;

}
