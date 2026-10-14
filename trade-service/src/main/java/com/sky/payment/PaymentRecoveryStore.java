package com.sky.payment;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository @RequiredArgsConstructor
public class PaymentRecoveryStore {
    private final JdbcTemplate jdbc;
    /** Commit intent before the network call, including unknown outcomes caused by process death. */
    public void begin(String number, BigDecimal amount, LocalDateTime deadline) {
        jdbc.update("insert into payment_recovery_job(id,order_number,kind,amount,deadline,next_at) values(?,?,'CHECK',?,?,date_add(now(),interval 5 second)) on duplicate key update id=id",
                "check:"+number,number,amount,deadline);
    }
    /** Called inside cancellation transaction; only actual recorded real-payment attempts need a channel close. */
    public static void close(JdbcTemplate jdbc, String number) {
        jdbc.update("insert into payment_recovery_job(id,order_number,kind,amount,deadline) select concat('close:',order_number),order_number,'CLOSE',amount,deadline from payment_recovery_job where id=? on duplicate key update id=values(id)", "check:"+number);
    }
    public List<Map<String,Object>> ready() {
        return jdbc.queryForList("select * from payment_recovery_job where (state='READY' and next_at<=now()) or (state='RUNNING' and lease_until<=now()) order by next_at limit 20");
    }
    public String claim(String id) {
        String token=UUID.randomUUID().toString();
        return jdbc.update("update payment_recovery_job set state='RUNNING',lease_token=?,lease_until=date_add(now(),interval 2 minute),updated_at=now() where id=? and ((state='READY' and next_at<=now()) or (state='RUNNING' and lease_until<=now()))", token,id)==1?token:null;
    }
    public void done(String id,String token) {
        jdbc.update("update payment_recovery_job set state='DONE',lease_token=null,lease_until=null,updated_at=now() where id=? and state='RUNNING' and lease_token=?",id,token);
    }
    public void waitUntil(String id,String token,LocalDateTime next) {
        jdbc.update("update payment_recovery_job set state='READY',next_at=?,lease_token=null,lease_until=null,updated_at=now() where id=? and state='RUNNING' and lease_token=?",next,id,token);
    }
    public void failed(String id,String token,Exception error) {
        String message=error.getClass().getSimpleName()+": "+error.getMessage();
        jdbc.update("update payment_recovery_job set state=if(attempts>=9,'FAILED','READY'),attempts=attempts+1,last_error=?,next_at=date_add(now(),interval 30 second),lease_token=null,lease_until=null,updated_at=now() where id=? and state='RUNNING' and lease_token=?",message.substring(0,Math.min(message.length(),1000)),id,token);
    }
    public List<Map<String,Object>> failures() { return jdbc.queryForList("select id,order_number,kind,attempts,last_error,updated_at from payment_recovery_job where state='FAILED' order by updated_at desc limit 100"); }
    public int replay(String id) { return jdbc.update("update payment_recovery_job set state='READY',attempts=0,next_at=now(),last_error=null,lease_token=null,lease_until=null where id=? and state='FAILED'",id); }
}
