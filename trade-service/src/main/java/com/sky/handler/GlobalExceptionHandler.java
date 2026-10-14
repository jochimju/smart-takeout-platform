package com.sky.handler;

import com.sky.constant.MessageConstant;
import com.sky.exception.BaseException;
import com.sky.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.sql.SQLIntegrityConstraintViolationException;

/**
 * 全局异常处理器，处理项目中抛出的业务异常
 */
@RestControllerAdvice //处理器通过@RestControllerAdvice注解 “监听” 所有 Controller 层抛出的异常；
@Slf4j
public class GlobalExceptionHandler {
    @ExceptionHandler({org.springframework.jdbc.UncategorizedSQLException.class,
            org.springframework.dao.DataAccessResourceFailureException.class,
            org.springframework.dao.TransientDataAccessException.class})
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)
    public Result databaseUnavailable(org.springframework.dao.DataAccessException ex) {
        log.warn("Order database temporarily unavailable",ex);
        return Result.error("服务暂时不可用，请保留原请求编号查询或重试");
    }
    /** Expected seckill backpressure is counted, not logged as one ERROR per rejected request. */
    @ExceptionHandler(com.sky.exception.SeckillBusyException.class)
    public Result seckillBusy(com.sky.exception.SeckillBusyException ex) {
        return Result.error(ex.getMessage());
    }

    /**
     * 捕获业务异常
     * @param ex
     * @return
     */
    @ExceptionHandler
    public Result exceptionHandler(BaseException ex){
        log.error("异常信息：{}", ex.getMessage());
        return Result.error(ex.getMessage());
    }
    /**
     * 处理SQL异常
     * @param ex
     * @return
     */
    @ExceptionHandler
    public Result exceptionHandler(SQLIntegrityConstraintViolationException ex){  //重载方法
        log.error("数据库约束异常", ex);
        //Duplicate entry 'zhangsan' for key 'employee.idx_username'
        String message = ex.getMessage(); //获取异常信息
        if(message.contains("Duplicate entry")){  //判断异常信息里是否有Duplicate entry
            String[] split = message.split(" "); //通过空格来分隔，得到一个数组对象["Duplicate", "entry", "'小智'", "for", "key", +1 more]
            String username = split[2]; //username ： “'zhangsan'”
            String msg = username + MessageConstant.ALREADY_EXISTS;
            return Result.error(msg);
        }else{
            return Result.error(MessageConstant.UNKNOWN_ERROR);
        }
    }

}
