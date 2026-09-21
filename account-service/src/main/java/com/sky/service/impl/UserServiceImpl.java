package com.sky.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.sky.constant.MessageConstant;
import com.sky.dto.UserLoginDTO;
import com.sky.entity.User;
import com.sky.exception.LoginFailedException;
import com.sky.mapper.UserMapper;
import com.sky.utils.properties.WeChatProperties;
import com.sky.service.UserService;
import com.sky.utils.HttpClientUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Service
@Slf4j
public class UserServiceImpl implements UserService {

    //微信服务接口地址
    public static final String WX_LOGIN = "https://api.weixin.qq.com/sns/jscode2session";

    @Autowired
    private WeChatProperties weChatProperties;
    @Autowired
    private UserMapper userMapper;

    /** 仅本地开发使用；生产必须为 false。 */
    @Value("${sky.wechat.mock-login-enabled:false}")
    private boolean mockLoginEnabled;

    /**
     * mock 登录使用的 openid。把它配置成已有用户的 openid，即可直接复用该用户的历史订单与地址。
     */
    @Value("${sky.wechat.mock-openid:mock-local-openid}")
    private String mockOpenid;

    /**
     * 微信登录
     * @param userLoginDTO
     * @return
     */
    public User wxLogin(UserLoginDTO userLoginDTO) {
        String openid;
        if (mockLoginEnabled) {
            log.warn("mock 登录已开启，使用固定 openid={} 登录，请勿在生产环境启用", mockOpenid);
            openid = mockOpenid;
        } else {
            openid = getOpenid(userLoginDTO.getCode());
        }

        //判断openid是否为空，如果为空表示登录失败，抛出业务异常
        if(openid == null){
            throw new LoginFailedException(MessageConstant.LOGIN_FAILED);
        }

        //判断当前用户是否为新用户
        User user = userMapper.getByOpenid(openid);

        //如果是新用户，自动完成注册
        if(user == null){
            user = User.builder()
                    .openid(openid)
                    .createTime(LocalDateTime.now())
                    .build();
            userMapper.insert(user);//后绪步骤实现
        }

        //返回这个用户对象
        return user;
    }

    /**
     * 调用微信接口服务，获取微信用户的openid
     * @param code
     * @return
     */
    private String getOpenid(String code){
        //调用微信接口服务，获得当前微信用户的openid
        Map<String, String> map = new HashMap<>();
        map.put("appid",weChatProperties.getAppid());
        map.put("secret",weChatProperties.getSecret());
        map.put("js_code",code);
        map.put("grant_type","authorization_code");
        String json = HttpClientUtil.doGet(WX_LOGIN, map);
        log.info("微信登录接口返回：{}", json);

        JSONObject jsonObject = JSON.parseObject(json);
        //微信返回errcode说明请求失败，直接抛出可读原因，避免只看到笼统的“登录失败”
        Integer errCode = jsonObject.getInteger("errcode");
        if (errCode != null && errCode != 0) {
            throw new LoginFailedException("微信登录失败：" + jsonObject.getString("errmsg"));
        }
        return jsonObject.getString("openid");
    }
}
