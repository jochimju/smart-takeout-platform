package com.sky.client;

import com.sky.contract.account.AccountAddressView;
import com.sky.contract.account.AccountUserView;
import com.sky.exception.RemoteServiceUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

/** Keeps authorization fail-closed and never fabricates account data. */
@Component
@Slf4j
public class AccountClientFallbackFactory implements FallbackFactory<AccountClient> {
    @Override
    public AccountClient create(Throwable cause) {
        log.warn("account-service Feign fallback activated", cause);
        return new AccountClient() {
            @Override public AccountUserView user(Long id) { throw unavailable(); }
            @Override public AccountAddressView address(Long userId, Long id) { throw unavailable(); }
            @Override public Integer countUsers(String begin, String end) { throw unavailable(); }
            @Override public Boolean permitted(Long employeeId, String method, String path) { return false; }
        };
    }

    private RemoteServiceUnavailableException unavailable() {
        return new RemoteServiceUnavailableException("用户服务暂时不可用，请稍后重试");
    }
}
