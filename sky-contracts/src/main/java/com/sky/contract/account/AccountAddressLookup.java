package com.sky.contract.account;

/** An address is only returned when it belongs to this user. */
public record AccountAddressLookup(Long userId, Long addressId) {}
