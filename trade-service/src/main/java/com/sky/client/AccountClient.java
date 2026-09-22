package com.sky.client;

import com.sky.contract.account.AccountAddressView;
import com.sky.contract.account.AccountUserView;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "account-service", configuration = AccountFeignConfiguration.class,
        fallbackFactory = AccountClientFallbackFactory.class)
public interface AccountClient {
    @GetMapping("/internal/account/users/{id}")
    AccountUserView user(@PathVariable("id") Long id);

    @GetMapping("/internal/account/addresses/{userId}/{id}")
    AccountAddressView address(@PathVariable("userId") Long userId, @PathVariable("id") Long id);

    @GetMapping("/internal/account/users/count")
    Integer countUsers(@RequestParam("begin") String begin, @RequestParam("end") String end);

    @GetMapping("/internal/account/permissions/check")
    Boolean permitted(@RequestParam("employeeId") Long employeeId,
                      @RequestParam("method") String method,
                      @RequestParam("path") String path);
}
