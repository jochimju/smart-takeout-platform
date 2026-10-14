package com.sky.utils;

import com.alibaba.fastjson.JSONObject;
import com.sky.utils.properties.WeChatProperties;
import com.sun.net.httpserver.HttpServer;
import com.wechat.pay.contrib.apache.httpclient.util.AesUtil;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.*;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

class WeChatPaymentProtocolTest {
    @TempDir Path directory;
    KeyPair merchant,oldKey,newKey;
    WeChatProperties properties;
    PaymentVerifier verifier;
    WeChatPayUtil client;
    HttpServer server;
    AtomicReference<String> requestBody=new AtomicReference<>();
    AtomicReference<Throwable> serverFailure=new AtomicReference<>();
    boolean tamperBill;
    String bill="商户号,公众账号ID,商户订单号,微信订单号,交易状态,货币种类,订单金额\n`merchant,`app,`ORDER1,`TX1,`SUCCESS,`CNY,`1.23\n";
    KeyPair pair() throws Exception {var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);return generator.generateKeyPair();}
    String pem(String name,Object value) throws Exception {
        var path=directory.resolve(name);try(var writer=new JcaPEMWriter(Files.newBufferedWriter(path,StandardCharsets.US_ASCII))) {
            writer.writeObject(value instanceof PrivateKey key?new org.bouncycastle.util.io.pem.PemObject("PRIVATE KEY",key.getEncoded()):value);
        }return path.toString();
    }
    Object certificate(KeyPair keys,int serial,boolean expired) throws Exception {
        Instant start=Instant.now().minusSeconds(3600),end=expired?Instant.now().minusSeconds(60):Instant.now().plusSeconds(3600);
        var builder=new JcaX509v3CertificateBuilder(new X500Name("CN=fixture"),BigInteger.valueOf(serial),Date.from(start),Date.from(end),new X500Name("CN=fixture"),keys.getPublic());
        return new JcaX509CertificateConverter().getCertificate(builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(keys.getPrivate())));
    }
    @BeforeEach void setup() throws Exception {
        merchant=pair();oldKey=pair();newKey=pair();properties=new WeChatProperties();
        properties.setMchid("merchant");properties.setAppid("app");properties.setMchSerialNo("merchantserial");
        properties.setPrivateKeyFilePath(pem("merchant.pem",merchant.getPrivate()));
        properties.setApiV3Key("0123456789abcdef0123456789abcdef");properties.setNotifyUrl("https://merchant.example/notify/paySuccess");
        properties.setPlatformCertificatePaths(List.of(pem("old.pem",certificate(oldKey,11,false)),pem("new.pem",certificate(newKey,12,false))));
        properties.setPublicKeyPaths(Map.of("PUB_KEY_ID_FIXTURE",pem("public.pem",newKey.getPublic())));
        verifier=new PaymentVerifier(properties);client=new WeChatPayUtil() {
            @Override protected com.wechat.pay.contrib.apache.httpclient.WechatPayHttpClientBuilder configureClient(com.wechat.pay.contrib.apache.httpclient.WechatPayHttpClientBuilder builder) {
                var sockets=org.apache.http.config.RegistryBuilder.<org.apache.http.conn.socket.ConnectionSocketFactory>create()
                        .register("http",org.apache.http.conn.socket.PlainConnectionSocketFactory.getSocketFactory()).build();
                builder.setConnectionManager(new org.apache.http.impl.conn.PoolingHttpClientConnectionManager(sockets,
                        (org.apache.http.conn.DnsResolver)host -> new java.net.InetAddress[]{java.net.InetAddress.getByName("127.0.0.1")}));return builder;
            }
        };
        ReflectionTestUtils.setField(client,"weChatProperties",properties);ReflectionTestUtils.setField(client,"verifier",verifier);
        server=HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        properties.setApiBaseUrl("http://api.mch.weixin.qq.com:"+server.getAddress().getPort());
        server.createContext("/",exchange -> {
            try {
                String body=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);requestBody.set(body);
                String authorization=exchange.getRequestHeaders().getFirst("Authorization");Map<String,String> fields=new HashMap<>();
                var matcher=java.util.regex.Pattern.compile("(\\w+)=\"([^\"]*)\"").matcher(authorization);while(matcher.find())fields.put(matcher.group(1),matcher.group(2));
                String request=exchange.getRequestMethod()+"\n"+exchange.getRequestURI()+"\n"+fields.get("timestamp")+"\n"+fields.get("nonce_str")+"\n"+body+"\n";
                Signature verify=Signature.getInstance("SHA256withRSA");verify.initVerify(merchant.getPublic());verify.update(request.getBytes(StandardCharsets.UTF_8));
                if(!verify.verify(Base64.getDecoder().decode(fields.get("signature")))) throw new AssertionError("Merchant request signature invalid");
                String path=exchange.getRequestURI().getPath(), response;
                if(path.endsWith("/downloadurl")) response=tamperBill?bill+"tampered":bill;
                else if(path.endsWith("/tradebill")) response="{\"download_url\":\""+properties.getApiBaseUrl()+"/v3/bill/downloadurl?token=test\",\"hash_type\":\"SHA256\",\"hash_value\":\""+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bill.getBytes(StandardCharsets.UTF_8)))+"\"}";
                else if(path.endsWith("/close")) response="";
                else if(path.endsWith("/jsapi")) response="{\"prepay_id\":\"prepay-fixture\"}";
                else response="{\"out_trade_no\":\"ORDER1\",\"appid\":\"app\",\"mchid\":\"merchant\",\"trade_state\":\"NOTPAY\"}";
                String timestamp=Long.toString(Instant.now().getEpochSecond()),nonce="fixture-nonce";
                exchange.getResponseHeaders().set("Wechatpay-Timestamp",timestamp);exchange.getResponseHeaders().set("Wechatpay-Nonce",nonce);
                exchange.getResponseHeaders().set("Request-ID","fixture-request-id");
                exchange.getResponseHeaders().set("Wechatpay-Serial","PUB_KEY_ID_FIXTURE");
                exchange.getResponseHeaders().set("Wechatpay-Signature",sign(newKey,timestamp+"\n"+nonce+"\n"+response+"\n"));
                exchange.getResponseHeaders().set("Content-Type","application/json;charset=UTF-8");byte[] bytes=response.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(path.endsWith("/close")?204:200,path.endsWith("/close")?-1:bytes.length);
                if(!path.endsWith("/close")) exchange.getResponseBody().write(bytes);
            } catch(Throwable ex) {serverFailure.set(ex);exchange.sendResponseHeaders(500,-1);} finally {exchange.close();}
        });server.start();
    }
    @AfterEach void close() {server.stop(0);assertNull(serverFailure.get(),()->String.valueOf(serverFailure.get()));}
    String sign(KeyPair pair,String data) throws Exception {Signature signature=Signature.getInstance("SHA256withRSA");signature.initSign(pair.getPrivate());signature.update(data.getBytes(StandardCharsets.UTF_8));return Base64.getEncoder().encodeToString(signature.sign());}
    @Test void signedHttpPrepayQueryCloseAndBillIntegrity() throws Exception {
        var deadline=LocalDateTime.now().plusMinutes(5);JSONObject result=client.pay("ORDER1",new BigDecimal("1.23"),"外卖","openid",deadline);
        var body=JSONObject.parseObject(requestBody.get());assertEquals(123,body.getJSONObject("amount").getIntValue("total"));assertNotNull(body.getString("time_expire"));
        String message="app\n"+result.getString("timeStamp")+"\n"+result.getString("nonceStr")+"\n"+result.getString("package")+"\n";
        Signature signature=Signature.getInstance("SHA256withRSA");signature.initVerify(merchant.getPublic());signature.update(message.getBytes(StandardCharsets.UTF_8));assertTrue(signature.verify(Base64.getDecoder().decode(result.getString("paySign"))));
        assertEquals("NOTPAY",client.queryOrder("ORDER1").getString("trade_state"));client.closeOrder("ORDER1");
        assertEquals(bill,client.downloadTradeBill(LocalDate.now().minusDays(1)));tamperBill=true;
        assertThrows(SecurityException.class,()->client.downloadTradeBill(LocalDate.now().minusDays(1)));
    }
    @Test void certificateRotationPublicKeyAndTamperingAreVerified() throws Exception {
        String body="{\"event_type\":\"TRANSACTION.SUCCESS\"}",timestamp=Long.toString(Instant.now().getEpochSecond()),nonce="fixture";
        String message=timestamp+"\n"+nonce+"\n"+body+"\n";
        client.verifyNotification(body,timestamp,nonce,"b",sign(oldKey,message));
        client.verifyNotification(body,timestamp,nonce,"c",sign(newKey,message));
        client.verifyNotification(body,timestamp,nonce,"PUB_KEY_ID_FIXTURE",sign(newKey,message));
        assertThrows(SecurityException.class,()->client.verifyNotification(body+" ",timestamp,nonce,"c",sign(newKey,message)));
        assertThrows(SecurityException.class,()->client.verifyNotification(body,timestamp,nonce,"unknown",sign(newKey,message)));
        assertThrows(SecurityException.class,()->client.verifyNotification(body,Long.toString(Instant.now().minusSeconds(301).getEpochSecond()),nonce,"c",sign(newKey,message)));
        properties.setPlatformCertificatePaths(List.of(pem("expired.pem",certificate(oldKey,11,true))));
        assertThrows(SecurityException.class,()->client.verifyNotification(body,timestamp,nonce,"b",sign(oldKey,message)));
    }
    @Test void aesGcmNotificationDecryptsAndRejectsWrongKeyOrAlteredPayload() throws Exception {
        String nonce="123456789012",associated="transaction",payload="{\"out_trade_no\":\"ORDER1\"}";
        Cipher encryption=Cipher.getInstance("AES/GCM/NoPadding");encryption.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(properties.getApiV3Key().getBytes(StandardCharsets.UTF_8),"AES"),new GCMParameterSpec(128,nonce.getBytes(StandardCharsets.UTF_8)));encryption.updateAAD(associated.getBytes(StandardCharsets.UTF_8));
        byte[] encrypted=encryption.doFinal(payload.getBytes(StandardCharsets.UTF_8));String cipher=Base64.getEncoder().encodeToString(encrypted);
        var aes=new AesUtil(properties.getApiV3Key().getBytes(StandardCharsets.UTF_8));assertEquals(payload,aes.decryptToString(associated.getBytes(StandardCharsets.UTF_8),nonce.getBytes(StandardCharsets.UTF_8),cipher));
        encrypted[0]^=1;assertThrows(Exception.class,()->aes.decryptToString(associated.getBytes(StandardCharsets.UTF_8),nonce.getBytes(StandardCharsets.UTF_8),Base64.getEncoder().encodeToString(encrypted)));
        assertThrows(Exception.class,()->new AesUtil("fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.UTF_8)).decryptToString(associated.getBytes(StandardCharsets.UTF_8),nonce.getBytes(StandardCharsets.UTF_8),cipher));
    }
}
