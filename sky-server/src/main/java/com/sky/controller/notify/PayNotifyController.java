package com.sky.controller.notify;

import com.alibaba.druid.support.json.JSONUtils;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.sky.utils.properties.WeChatProperties;
import com.sky.service.OrderService;
import com.wechat.pay.contrib.apache.httpclient.util.AesUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.entity.ContentType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;

/**
 * 支付回调相关接口
 */
@RestController
@RequestMapping("/notify")
@Slf4j
public class PayNotifyController {
    @Autowired
    private OrderService orderService;
    @Autowired
    private com.sky.service.RedPacketService redPacketService;
    @Autowired private com.sky.utils.WeChatPayUtil payment;
    @Autowired
    private WeChatProperties weChatProperties;

    /**
     * 支付成功回调
     *
     * @param request
     */
    @RequestMapping("/paySuccess")
    public void paySuccessNotify(HttpServletRequest request, HttpServletResponse response) throws Exception {
        String body=org.springframework.util.StreamUtils.copyToString(request.getInputStream(),StandardCharsets.UTF_8);
        payment.verifyNotification(body,request.getHeader("Wechatpay-Timestamp"),
            request.getHeader("Wechatpay-Nonce"),request.getHeader("Wechatpay-Serial"),request.getHeader("Wechatpay-Signature"));
        JSONObject receipt=JSON.parseObject(decryptData(body));
        if(!weChatProperties.getMchid().equals(receipt.getString("mchid")) ||
            !weChatProperties.getAppid().equals(receipt.getString("appid")) ||
            !"SUCCESS".equals(receipt.getString("trade_state")))
            throw new SecurityException("payment receipt identity or state mismatch");
        JSONObject amount=receipt.getJSONObject("amount");
        if(amount==null || !"CNY".equals(amount.getString("currency")) || amount.getBigDecimal("total")==null)
            throw new SecurityException("payment amount missing or currency mismatch");
        String outTradeNo = receipt.getString("out_trade_no");
        java.math.BigDecimal paidAmount = amount.getBigDecimal("total").movePointLeft(2);
        if (!redPacketService.paySuccess(outTradeNo, receipt.getString("transaction_id"), paidAmount)) {
            orderService.paySuccess(outTradeNo, receipt.getString("transaction_id"), paidAmount);
        }
        responseToWeixin(response);
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(Exception.class)
    public void failedNotification(Exception error,HttpServletResponse response) throws java.io.IOException {
        log.warn("Payment callback rejected",error);
        response.setStatus(500);
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"FAIL\",\"message\":\"notification processing failed\"}");
    }

    /**
     * 读取数据
     *
     * @param request
     * @return
     * @throws Exception
     */
    private String readData(HttpServletRequest request) throws Exception {
        BufferedReader reader = request.getReader();
        StringBuilder result = new StringBuilder();
        String line = null;
        while ((line = reader.readLine()) != null) {
            if (result.length() > 0) {
                result.append("\n");
            }
            result.append(line);
        }
        return result.toString();
    }

    /**
     * 数据解密
     *
     * @param body
     * @return
     * @throws Exception
     */
    private String decryptData(String body) throws Exception {
        JSONObject resultObject = JSON.parseObject(body);
        JSONObject resource = resultObject.getJSONObject("resource");
        String ciphertext = resource.getString("ciphertext");
        String nonce = resource.getString("nonce");
        String associatedData = resource.getString("associated_data");

        AesUtil aesUtil = new AesUtil(weChatProperties.getApiV3Key().getBytes(StandardCharsets.UTF_8));
        //密文解密
        String plainText = aesUtil.decryptToString(associatedData.getBytes(StandardCharsets.UTF_8),
                nonce.getBytes(StandardCharsets.UTF_8),
                ciphertext);

        return plainText;
    }

    /**
     * 给微信响应
     * @param response
     */
    private void responseToWeixin(HttpServletResponse response) throws Exception{
        response.setStatus(200);
        HashMap<Object, Object> map = new HashMap<>();
        map.put("code", "SUCCESS");
        map.put("message", "SUCCESS");
        response.setHeader("Content-type", ContentType.APPLICATION_JSON.toString());
        response.getOutputStream().write(JSONUtils.toJSONString(map).getBytes(StandardCharsets.UTF_8));
        response.flushBuffer();
    }
}
