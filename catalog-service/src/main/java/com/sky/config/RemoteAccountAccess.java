package com.sky.config;

import com.sky.client.AccountClient;
import com.sky.contract.account.SessionCheck;
import com.sky.security.AccountAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RemoteAccountAccess implements AccountAccess {
    private final AccountClient accounts;
    @Override public boolean valid(String token, boolean admin) {
        return Boolean.TRUE.equals(accounts.valid(new SessionCheck(token, admin)));
    }
    @Override public boolean permitted(Long employeeId, String method, String path) {
        return Boolean.TRUE.equals(accounts.permitted(employeeId, method, path));
    }
}
