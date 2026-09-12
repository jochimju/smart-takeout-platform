package com.sky.service.impl;

import com.sky.entity.Orders;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.*;

@Repository
@RequiredArgsConstructor
public class OrderReliabilityStore {
    private final JdbcTemplate jdbc;
    public void scheduleTimeout(Orders order) {
        jdbc.update("insert into order_reliability_job(id,order_number,kind,due_at,next_at) values(?,?,'TIMEOUT',?,now())",
            "timeout:"+order.getNumber(),order.getNumber(),order.getExpireTime());
    }
    public void refund(Orders order, boolean mock) {
        jdbc.update("insert into order_reliability_job(id,order_number,kind,due_at,next_at,amount,mock_payment) "+
            "values(?,?,'REFUND',now(),now(),?,?) on duplicate key update id=id",
            "refund:"+order.getNumber(),order.getNumber(),order.getAmount(),mock);
    }
    public void done(String id) {
        jdbc.update("update order_reliability_job set state='DONE',lease_token=null,lease_until=null,updated_at=now() where id=?",id);
    }
    public void early(String number, LocalDateTime due) {
        jdbc.update("update order_reliability_job set state='READY',next_at=?,lease_token=null,lease_until=null,updated_at=now() "+
            "where id=? and state not in ('DONE','FAILED')",due,"timeout:"+number);
    }
    public List<Map<String,Object>> ready() {
        return jdbc.queryForList("select * from order_reliability_job where (state='READY' and next_at<=now()) "+
            "or (state='RUNNING' and lease_until<=now()) order by next_at limit 20");
    }
    public String claim(String id) {
        String token=UUID.randomUUID().toString();
        return jdbc.update("update order_reliability_job set state='RUNNING',lease_token=?,lease_until=?,updated_at=now() "+
            "where id=? and ((state='READY' and next_at<=now()) or (state='RUNNING' and lease_until<=now()))",
            token,LocalDateTime.now().plusMinutes(2),id)==1 ? token : null;
    }
    public void waitUntil(String id,String token,LocalDateTime next) {
        jdbc.update("update order_reliability_job set state='READY',next_at=?,lease_token=null,lease_until=null,updated_at=now() "+
            "where id=? and state='RUNNING' and lease_token=?",next,id,token);
    }
    public void failed(String id,String token,Exception error) {
        String message=error.toString();
        if(message.length()>1000) message=message.substring(0,1000);
        jdbc.update("update order_reliability_job set state=if(attempts>=9,'FAILED','READY'),attempts=attempts+1,"+
            "next_at=?,last_error=?,lease_token=null,lease_until=null,updated_at=now() "+
            "where id=? and state not in ('DONE','FAILED') and (? is null or lease_token=?)",
            LocalDateTime.now().plusSeconds(30),message,id,token,token);
    }
    public boolean hasTimeout(String number) {
        return jdbc.queryForObject("select count(*) from order_reliability_job where id=?",Integer.class,"timeout:"+number)>0;
    }
    public List<Map<String,Object>> failures() {
        return jdbc.queryForList("select id,order_number,kind,attempts,last_error,updated_at from order_reliability_job "+
            "where state='FAILED' order by updated_at desc limit 100");
    }
    public int replay(String id) {
        return jdbc.update("update order_reliability_job set state='READY',attempts=0,next_at=now(),"+
            "lease_token=null,lease_until=null,updated_at=now() where id=? and state='FAILED'",id);
    }

    public boolean isFailed(String number) {
        return jdbc.queryForObject("select count(*) from order_reliability_job where id=? and state='FAILED'",
            Integer.class,"timeout:"+number)>0;
    }
}
