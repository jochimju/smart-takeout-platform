package com.sky.utils;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.sky.utils.properties.WeChatProperties;
import com.wechat.pay.contrib.apache.httpclient.WechatPayHttpClientBuilder;
import com.wechat.pay.contrib.apache.httpclient.util.PemUtil;
import org.apache.commons.lang.RandomStringUtils;
import org.apache.http.HttpHeaders;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.math.BigDecimal;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * 微信支付工具类
 */
@Component
public class WeChatPayUtil {

    //微信支付下单接口地址
    public static final String JSAPI = "https://api.mch.weixin.qq.com/v3/pay/transactions/jsapi";

    //申请退款接口地址
    public static final String REFUNDS = "https://api.mch.weixin.qq.com/v3/refund/domestic/refunds";

    @Autowired
    private WeChatProperties weChatProperties;
    @Autowired private PaymentVerifier verifier;

    /**
     * 获取调用微信接口的客户端工具对象
     *
     * @return
     */
    private CloseableHttpClient getClient() {
        return getClient(true);
    }
    private CloseableHttpClient getClient(boolean verifyResponse) {
        if (weChatProperties.getMchid() == null || weChatProperties.getMchid().isBlank()
                || weChatProperties.getMchSerialNo() == null || weChatProperties.getMchSerialNo().isBlank()
                || weChatProperties.getPrivateKeyFilePath() == null || weChatProperties.getPrivateKeyFilePath().isBlank())
            throw new IllegalStateException("WeChat merchant credentials are not configured");
        try (var input = java.nio.file.Files.newInputStream(java.nio.file.Path.of(weChatProperties.getPrivateKeyFilePath()))) {
            PrivateKey key = PemUtil.loadPrivateKey(input);
            var builder = configureClient(WechatPayHttpClientBuilder.create()
                    .withMerchant(weChatProperties.getMchid(), weChatProperties.getMchSerialNo(), key)
                    .withValidator(verifyResponse ? new com.wechat.pay.contrib.apache.httpclient.auth.WechatPay2Validator(verifier) : response -> true));
            builder.disableRedirectHandling();
            return builder.build();
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("Merchant private key could not be loaded", ex);
        }
    }
    /** Test fixtures may replace DNS while retaining the SDK's official-host signing and response verification. */
    protected WechatPayHttpClientBuilder configureClient(WechatPayHttpClientBuilder builder) { return builder; }

    /**
     * 发送post方式请求
     *
     * @param url
     * @param body
     * @return
     */
    private String post(String url, String body) throws Exception {
        CloseableHttpClient httpClient = getClient();

        HttpPost httpPost = new HttpPost(endpoint(url));
        httpPost.setConfig(requestConfig());
        httpPost.addHeader(HttpHeaders.ACCEPT, ContentType.APPLICATION_JSON.toString());
        httpPost.addHeader(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.toString());
        httpPost.setEntity(new StringEntity(body, "UTF-8"));

        CloseableHttpResponse response = httpClient.execute(httpPost);
        try {
            String bodyAsString = response.getEntity() == null ? "" : EntityUtils.toString(response.getEntity(), java.nio.charset.StandardCharsets.UTF_8);
            int status = response.getStatusLine().getStatusCode();
            if (status >= 300 && status < 400) throw new java.io.IOException("Unexpected WeChat redirect: " + status);
            if (status >= 500 || status == 429) throw new java.io.IOException("WeChat transient HTTP error: " + status);
            if (status >= 400 && (bodyAsString == null || bodyAsString.isBlank())) throw new java.io.IOException("WeChat HTTP error: " + status);
            return bodyAsString == null || bodyAsString.isBlank() ? "{}" : bodyAsString;
        } finally {
            httpClient.close();
            response.close();
        }
    }

    /**
     * 发送get方式请求
     *
     * @param url
     * @return
     */
    private String get(String url) throws Exception {
        CloseableHttpClient httpClient = getClient();

        HttpGet httpGet = new HttpGet(endpoint(url));
        httpGet.setConfig(requestConfig());
        httpGet.addHeader(HttpHeaders.ACCEPT, ContentType.APPLICATION_JSON.toString());
        httpGet.addHeader(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.toString());

        CloseableHttpResponse response = httpClient.execute(httpGet);
        try {
            String bodyAsString = response.getEntity() == null ? "" : EntityUtils.toString(response.getEntity(), java.nio.charset.StandardCharsets.UTF_8);
            int status = response.getStatusLine().getStatusCode();
            if (status >= 300 && status < 400) throw new java.io.IOException("Unexpected WeChat redirect: " + status);
            if (status >= 500 || status == 429) throw new java.io.IOException("WeChat transient HTTP error: " + status);
            if (status >= 400 && (bodyAsString == null || bodyAsString.isBlank())) throw new java.io.IOException("WeChat HTTP error: " + status);
            return bodyAsString == null || bodyAsString.isBlank() ? "{}" : bodyAsString;
        } finally {
            httpClient.close();
            response.close();
        }
    }

    /**
     * jsapi下单
     *
     * @param orderNum    商户订单号
     * @param total       总金额
     * @param description 商品描述
     * @param openid      微信用户的openid
     * @return
     */
    private String jsapi(String orderNum, BigDecimal total, String description, String openid, java.time.LocalDateTime deadline) throws Exception {
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("appid", weChatProperties.getAppid());
        jsonObject.put("mchid", weChatProperties.getMchid());
        jsonObject.put("description", description);
        jsonObject.put("out_trade_no", orderNum);
        jsonObject.put("notify_url", weChatProperties.getNotifyUrl());
        if (deadline != null) jsonObject.put("time_expire", deadline.atZone(java.time.ZoneId.of("Asia/Shanghai")).toOffsetDateTime().toString());

        JSONObject amount = new JSONObject();
        amount.put("total", cents(total));
        amount.put("currency", "CNY");

        jsonObject.put("amount", amount);

        JSONObject payer = new JSONObject();
        payer.put("openid", openid);

        jsonObject.put("payer", payer);

        String body = jsonObject.toJSONString();
        return post(JSAPI, body);
    }

    /**
     * 小程序支付
     *
     * @param orderNum    商户订单号
     * @param total       金额，单位 元
     * @param description 商品描述
     * @param openid      微信用户的openid
     * @return
     */
    public JSONObject pay(String orderNum, BigDecimal total, String description, String openid) throws Exception {
        return pay(orderNum, total, description, openid, null);
    }

    public JSONObject pay(String orderNum, BigDecimal total, String description, String openid, java.time.LocalDateTime deadline) throws Exception {
        String bodyAsString = jsapi(orderNum, total, description, openid, deadline);
        //解析返回结果
        JSONObject jsonObject = JSON.parseObject(bodyAsString);


        String prepayId = jsonObject.getString("prepay_id");
        if (prepayId != null) {
            String timeStamp = String.valueOf(System.currentTimeMillis() / 1000);
            String nonceStr = java.util.UUID.randomUUID().toString().replace("-", "");
            ArrayList<Object> list = new ArrayList<>();
            list.add(weChatProperties.getAppid());
            list.add(timeStamp);
            list.add(nonceStr);
            list.add("prepay_id=" + prepayId);
            //二次签名，调起支付需要重新签名
            StringBuilder stringBuilder = new StringBuilder();
            for (Object o : list) {
                stringBuilder.append(o).append("\n");
            }
            String signMessage = stringBuilder.toString();
            byte[] message = signMessage.getBytes(java.nio.charset.StandardCharsets.UTF_8);

            Signature signature = Signature.getInstance("SHA256withRSA");
            try (var input = java.nio.file.Files.newInputStream(java.nio.file.Path.of(weChatProperties.getPrivateKeyFilePath()))) {
                signature.initSign(PemUtil.loadPrivateKey(input));
            }
            signature.update(message);
            String packageSign = Base64.getEncoder().encodeToString(signature.sign());

            //构造数据给微信小程序，用于调起微信支付
            JSONObject jo = new JSONObject();
            jo.put("timeStamp", timeStamp);
            jo.put("nonceStr", nonceStr);
            jo.put("package", "prepay_id=" + prepayId);
            jo.put("signType", "RSA");
            jo.put("paySign", packageSign);

            return jo;
        }
        return jsonObject;
    }

    /**
     * 申请退款
     *
     * @param outTradeNo    商户订单号
     * @param outRefundNo   商户退款单号
     * @param refund        退款金额
     * @param total         原订单金额
     * @return
     */
    public String refund(String outTradeNo, String outRefundNo, BigDecimal refund, BigDecimal total) throws Exception {
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("out_trade_no", outTradeNo);
        jsonObject.put("out_refund_no", outRefundNo);

        JSONObject amount = new JSONObject();
        amount.put("refund", cents(refund));
        amount.put("total", cents(total));
        amount.put("currency", "CNY");

        jsonObject.put("amount", amount);
        if (weChatProperties.getRefundNotifyUrl()!=null && !weChatProperties.getRefundNotifyUrl().isBlank())
            jsonObject.put("notify_url", weChatProperties.getRefundNotifyUrl());

        String body = jsonObject.toJSONString();

        //调用申请退款接口
        return post(REFUNDS, body);
    }

    public String queryRefund(String refundNumber) throws Exception {
        if(!refundNumber.matches("[a-zA-Z0-9_-]{1,64}")) throw new IllegalArgumentException("invalid refund number");
        return get(REFUNDS+"/"+refundNumber);
    }

    public void verifyNotification(String body,String timestamp,String nonce,String serial,String signature) throws Exception {
        if(timestamp==null || nonce==null || serial==null || signature==null)
            throw new SecurityException("payment signature headers missing");
        long seconds=Long.parseLong(timestamp);
        if(Math.abs(java.time.Instant.now().getEpochSecond()-seconds)>300)
            throw new SecurityException("payment notification timestamp expired");
        if (!verifier.verify(serial, (timestamp+"\n"+nonce+"\n"+body+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8), signature))
            throw new SecurityException("invalid payment notification signature");
    }

    public JSONObject queryOrder(String number) throws Exception {
        checkNumber(number);
        return JSON.parseObject(get("https://api.mch.weixin.qq.com/v3/pay/transactions/out-trade-no/" + number
                + "?mchid=" + java.net.URLEncoder.encode(weChatProperties.getMchid(), java.nio.charset.StandardCharsets.UTF_8)));
    }
    public JSONObject closeOrder(String number) throws Exception {
        checkNumber(number); JSONObject body = new JSONObject(); body.put("mchid", weChatProperties.getMchid());
        return JSON.parseObject(post("https://api.mch.weixin.qq.com/v3/pay/transactions/out-trade-no/"+number+"/close", body.toJSONString()));
    }
    private void checkNumber(String number) {
        if (number == null || !number.matches("[a-zA-Z0-9_-]{1,64}")) throw new IllegalArgumentException("invalid merchant order number");
    }
    private String endpoint(String url) {
        return url.replace("https://api.mch.weixin.qq.com", weChatProperties.getApiBaseUrl().replaceAll("/$", ""));
    }
    private int cents(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) throw new IllegalArgumentException("payment amount must be positive");
        return amount.movePointRight(2).intValueExact();
    }

    /** Metadata response is signed; the raw bill endpoint uses the signed metadata hash as its integrity proof. */
    public String downloadTradeBill(java.time.LocalDate date) throws Exception {
        JSONObject metadata=JSON.parseObject(get("https://api.mch.weixin.qq.com/v3/bill/tradebill?bill_date="+date+"&bill_type=ALL"));
        if(metadata.getString("code")!=null) throw new java.io.IOException("Trade bill unavailable: "+metadata.getString("code"));
        java.net.URI url=java.net.URI.create(metadata.getString("download_url"));
        java.net.URI expected=java.net.URI.create(weChatProperties.getApiBaseUrl());
        if(!java.util.Objects.equals(url.getScheme(),expected.getScheme()) || !java.util.Objects.equals(url.getHost(),expected.getHost())
                || url.getPort()!=expected.getPort() || !"/v3/bill/downloadurl".equals(url.getPath())) throw new SecurityException("Unexpected trade bill download endpoint");
        String algorithm=metadata.getString("hash_type");
        if(!java.util.Set.of("SHA1","SHA256").contains(algorithm)) throw new SecurityException("Unsupported trade bill hash");
        byte[] bytes;
        HttpGet request=new HttpGet(url);request.setConfig(requestConfig());
        try(var client=getClient(false);var response=client.execute(request)) {
            if(response.getStatusLine().getStatusCode()!=200 || response.getEntity()==null) throw new java.io.IOException("Trade bill download failed");
            try(var input=response.getEntity().getContent()) { bytes=input.readNBytes(8*1024*1024+1); }
        }
        if(bytes.length>8*1024*1024) throw new java.io.IOException("Trade bill exceeds size limit");
        String actual=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance(algorithm).digest(bytes));
        if(!actual.equalsIgnoreCase(metadata.getString("hash_value"))) throw new SecurityException("Trade bill integrity mismatch");
        if(bytes.length>=2 && bytes[0]==(byte)0x1f && bytes[1]==(byte)0x8b) {
            try(var gzip=new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(bytes))) { bytes=gzip.readNBytes(8*1024*1024+1); }
            if(bytes.length>8*1024*1024) throw new java.io.IOException("Expanded bill exceeds size limit");
        }
        return new String(bytes,java.nio.charset.StandardCharsets.UTF_8);
    }

    private org.apache.http.client.config.RequestConfig requestConfig() {
        return org.apache.http.client.config.RequestConfig.custom().setConnectTimeout(5000)
            .setConnectionRequestTimeout(5000).setSocketTimeout(10000).build();
    }
}
