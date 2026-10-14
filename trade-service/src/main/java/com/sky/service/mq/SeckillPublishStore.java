package com.sky.service.mq;

import com.sky.constant.MqConstant;
import com.sky.entity.MqFailMessage;
import com.sky.observability.SeckillTiming;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Short batch transactions; a claim token fences late callbacks after lease recovery. */
@Service
@RequiredArgsConstructor
public class SeckillPublishStore {
    private final JdbcTemplate jdbc;
    /** Drive from retained timeout events, not a scan of the order table. ACK is not business completion. */
    @Transactional(rollbackFor=Exception.class)
    public void recoverTimeouts() {
        var rows=jdbc.queryForList("select id,message_body from mq_fail_message force index(idx_mq_timeout_recovery) where routing_key='seckill.timeout' and status=1 and delivery_deadline<=date_sub(now(3),interval 2 second) and update_time<=date_sub(now(),interval 30 second) order by delivery_deadline limit 100 for update skip locked");
        for(var row:rows) {
            String number=com.alibaba.fastjson.JSON.parseObject((String)row.get("message_body"),String.class);
            var order=jdbc.queryForList("select status,pay_status from orders where number=?",number);
            if(!order.isEmpty() && ((Number)order.get(0).get("status")).intValue()==1 && ((Number)order.get(0).get("pay_status")).intValue()==0)
                jdbc.update("update mq_fail_message set status=0,available_at=now(3) where id=? and status=1",row.get("id"));
            else jdbc.update("update mq_fail_message set status=4 where id=? and status=1",row.get("id"));
        }
    }
    public record Result(long id,String token,boolean timeout,boolean acknowledged) {}
    @Transactional(rollbackFor=Exception.class,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void releaseExpiredClaims() {
        var ids=jdbc.queryForList("select id from mq_fail_message where status=3 and lease_until<now() order by id limit 200 for update skip locked",Long.class);
        if(!ids.isEmpty())jdbc.update("update mq_fail_message set status=0,lease_until=null,claim_token=null where status=3 and lease_until<now() and id in ("+marks(ids.size())+")",ids.toArray());
    }

    @Transactional(rollbackFor=Exception.class,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public List<MqFailMessage> claimNormal(int size) {
        int limit=Math.min(100,Math.max(1,size));
        var rows=jdbc.query("select * from mq_fail_message where routing_key='order.submit' and status=0 and retry_count<5 and (available_at is null or available_at<=now(3)) order by id limit "+limit+" for update skip locked",new BeanPropertyRowMapper<>(MqFailMessage.class));
        if(rows.isEmpty())return rows;String token=UUID.randomUUID().toString();var args=new ArrayList<Object>();args.add(token);
        rows.forEach(row->{row.setClaimToken(token);args.add(row.getId());});
        jdbc.update("update mq_fail_message set status=3,claim_token=?,lease_until=date_add(now(),interval 2 minute) where status=0 and id in ("+marks(rows.size())+")",args.toArray());return rows;
    }

    @Transactional(rollbackFor=Exception.class,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public List<MqFailMessage> claim(boolean timeout,int size) {
        SeckillTiming.transaction();
        int limit=Math.min(200,Math.max(1,size));
        String route=timeout?MqConstant.SECKILL_TIMEOUT_ROUTING_KEY:MqConstant.SECKILL_ORDER_ROUTING_KEY;
        String eligible=timeout?"available_at<=now(3)":"retry_count<5 and (available_at is null or available_at<=now(3))";
        var rows=jdbc.query("select * from mq_fail_message force index ("+(timeout?"idx_mq_route_ready":"idx_mq_route_claim")+") where "+(timeout?"routing_key=?":"routing_key in (?,'seckill.order.bucket.0','seckill.order.bucket.1','seckill.order.bucket.2','seckill.order.bucket.3','seckill.order.bucket.4','seckill.order.bucket.5','seckill.order.bucket.6','seckill.order.bucket.7')")+" and status=0 and "+eligible+
            " order by "+(timeout?"available_at,id":"id")+" limit "+limit+" for update skip locked",new BeanPropertyRowMapper<>(MqFailMessage.class),route);
        SeckillTiming.outcome(rows.isEmpty()?"empty":"claimed");
        if(rows.isEmpty())return rows;
        String token=UUID.randomUUID().toString();var args=new ArrayList<Object>();args.add(token);
        rows.forEach(row->{row.setClaimToken(token);args.add(row.getId());});
        jdbc.update("update mq_fail_message set status=3,claim_token=?,lease_until=date_add(now(),interval 2 minute),update_time=now() where status=0 and id in ("+marks(rows.size())+")",args.toArray());
        return rows;
    }

    @Transactional(rollbackFor=Exception.class,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void complete(List<Result> results) {
        SeckillTiming.transaction();
        if(results.isEmpty())return;
        if(results.size()>200)throw new IllegalArgumentException("Completion batch exceeds 200");
        for(int kind=0;kind<3;kind++) {
            final int group=kind;
            var selected=results.stream().filter(row->(row.acknowledged()?0:row.timeout()?2:1)==group).sorted(Comparator.comparingLong(Result::id)).toList();
            if(selected.isEmpty())continue;
            String assignments=kind==0?"status=1":kind==1?
                "status=if(retry_count+1>=5,2,0),retry_count=retry_count+1,available_at=date_add(now(3),interval 5 second),fail_reason='publish unconfirmed'":
                "status=0,retry_count=least(retry_count+1,100000),available_at=date_add(now(3),interval 5 second),fail_reason='timeout publish unconfirmed'";
            var args=new ArrayList<Object>();selected.forEach(row->{args.add(row.id());args.add(row.token());});
            String wanted=String.join(" union all ",Collections.nCopies(selected.size(),"select ? id,? token"));
            jdbc.update("update ("+wanted+") wanted straight_join mq_fail_message m on m.id=wanted.id and m.claim_token=wanted.token set "+assignments+
                    ",claim_token=null,lease_until=null,update_time=now() where m.status=3",args.toArray());
        }
    }
    private static String marks(int count){return String.join(",",Collections.nCopies(count,"?"));}
}
