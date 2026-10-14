package com.sky.exception;

/** Expected pre-admission backpressure; no inventory was reserved. */
public class SeckillBusyException extends OrderBusinessException {
    public SeckillBusyException() {super("秒杀建单繁忙，请稍后重试");}
}
