package com.sky.client;

import com.sky.contract.account.AccountAddressView;
import com.sky.contract.account.AccountAddressLookup;
import com.sky.contract.account.AccountUserView;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "account-service", configuration = AccountFeignConfiguration.class,
        fallbackFactory = AccountClientFallbackFactory.class)
public interface AccountClient {
    @GetMapping("/internal/account/users/{id}")
    AccountUserView user(@PathVariable("id") Long id);

    /** 建单消费者远程查询收货地址；账户服务按用户归属过滤，返回地址快照字段。 */
    @GetMapping("/internal/account/addresses/{userId}/{id}")
    AccountAddressView address(@PathVariable("userId") Long userId, @PathVariable("id") Long id);

    @PostMapping("/internal/account/addresses/batch")
    java.util.List<AccountAddressView> addresses(@RequestBody java.util.List<AccountAddressLookup> lookups);

    @GetMapping("/internal/account/users/count")
    Integer countUsers(@RequestParam("begin") String begin, @RequestParam("end") String end);

    @GetMapping("/internal/account/permissions/check")
    Boolean permitted(@RequestParam("employeeId") Long employeeId,
                      @RequestParam("method") String method,
                      @RequestParam("path") String path);

    @org.springframework.web.bind.annotation.PostMapping("/internal/account/sessions/check")
    Boolean valid(@org.springframework.web.bind.annotation.RequestBody com.sky.contract.account.SessionCheck check);
}
