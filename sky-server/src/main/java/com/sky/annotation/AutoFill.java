package com.sky.annotation;

import com.sky.enumeration.OperationType;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 自定义注解，用于标识某个方法需要进行功能字段自动填充处理
 */
@Target(ElementType.METHOD) //指定当前这个注解会加在什么位置 METHOD（该注解只能用于方法）
@Retention(RetentionPolicy.RUNTIME) //表示这个注解会在运行时保留，可以通过反射机制访问到该注解
public @interface AutoFill {   //注解本质上是一种特殊的接口
    //指定数据库操作类型是：UPDATE INSERT
    OperationType value();  //value() 方法返回一个 OperationType 类型的值
}