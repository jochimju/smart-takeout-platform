package com.sky.service.impl;
import com.sky.exception.BaseException;
import com.sky.exception.RemoteServiceUnavailableException;
public final class OrderFailurePolicy {
 private OrderFailurePolicy(){}
 public static boolean permanent(Throwable failure) {return failure instanceof BaseException && !(failure instanceof RemoteServiceUnavailableException);}
}
