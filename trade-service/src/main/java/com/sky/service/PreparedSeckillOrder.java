package com.sky.service;

import com.sky.dto.SeckillOrderMessageDTO;
import com.sky.contract.catalog.ProductQuote;
import com.sky.contract.account.AccountAddressView;

/** Canonical durable command plus remote reads; DB eligibility is rechecked under locks. */
public record PreparedSeckillOrder(SeckillOrderMessageDTO command,ProductQuote quote,AccountAddressView address) {}
