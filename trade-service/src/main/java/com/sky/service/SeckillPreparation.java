package com.sky.service;

import java.util.List;
import java.util.Map;

/** Individual validation failures must not prevent valid commands in the same MQ delivery. */
public record SeckillPreparation(List<PreparedSeckillOrder> ready, Map<String,Exception> failures) {}
