package com.sky.security;

/** Fail closed if the authoritative account service is unavailable. */
public interface AccountAccess {
    boolean valid(String token, boolean admin);
    boolean permitted(Long employeeId, String method, String path);
}
