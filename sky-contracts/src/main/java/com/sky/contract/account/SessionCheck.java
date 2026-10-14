package com.sky.contract.account;

/** Sent only via an authenticated internal POST, never as a URL query. */
public record SessionCheck(String token, boolean admin) { }
