package com.sky.service.impl;

import com.alibaba.fastjson.JSON;
import com.sky.dto.SeckillOrderMessageDTO;
import com.sky.mapper.SeckillActivityMapper;
import com.sky.observability.SeckillTiming;
import com.sky.service.mq.OrderMessagePublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/** Durable acceptance, bounded retries and Redis compensation. No ordinary-order state lives here. */
@Service
@RequiredArgsConstructor
public class SeckillAsyncStore {
    @org.springframework.beans.factory.annotation.Autowired(required=false) private com.sky.observability.OrderCreationMetrics creationMetrics;
    public static class MissingAcceptance extends IllegalStateException {
        public MissingAcceptance(String number) { super("Seckill acceptance missing: "+number); }
    }
    private final JdbcTemplate jdbc;
    private final SeckillActivityMapper activities;
    private final OrderMessagePublisher publisher;
    @Value("${sky.seckill.processing-seconds:120}") private long processingSeconds;

    public static class AcceptanceConflict extends IllegalStateException {
        public AcceptanceConflict(String message) { super(message); }
    }

    @Transactional(rollbackFor=Exception.class)
    public void accept(SeckillOrderMessageDTO command) {
        acceptBatch(java.util.Collections.singletonList(command));
    }

    /** One handoff mutex and commit per activity batch; no inventory lock. */
    @Transactional(rollbackFor=Exception.class)
    public void acceptBatch(List<SeckillOrderMessageDTO> commands) {
        SeckillTiming.transaction();
        SeckillTiming.run("body", () -> acceptBatchBody(commands));
    }

    private void acceptBatchBody(List<SeckillOrderMessageDTO> commands) {
        if(commands==null || commands.isEmpty()) return;
        if(commands.size()>200) throw new AcceptanceConflict("Seckill bridge batch exceeds 200");
        Long activity=commands.get(0).getActivityId();
        if(activity==null) throw new AcceptanceConflict("Activity is required");
        java.util.LinkedHashMap<String,SeckillOrderMessageDTO> unique=new java.util.LinkedHashMap<>();
        java.util.Set<String> requestKeys=new java.util.HashSet<>();
        for(var command:commands) {
            if(!java.util.Objects.equals(activity,command.getActivityId()) || command.getUserId()==null
                || command.getOrderNumber()==null || command.getRequest()==null || command.getRequestHash()==null
                || command.getRequest().getRequestId()==null || command.getAcceptedAt()==null)
                throw new AcceptanceConflict("Incomplete/mixed-activity bridge batch");
            String key=command.getUserId()+":"+command.getRequest().getRequestId();
            if(unique.containsKey(command.getOrderNumber()) || !requestKeys.add(key))
                throw new AcceptanceConflict("Duplicate identity in bridge batch");
            unique.put(command.getOrderNumber(),command);
        }
        // This mutex serializes bridge replays only. Fulfillment never locks it.
        jdbc.update("insert into seckill_handoff_lock(activity_id) values(?) on duplicate key update activity_id=values(activity_id)",activity);
        // Handoff does not change inventory. Immutable metadata + unique acceptance keys suffice;
        // locking a stock bucket here would unnecessarily block fulfillment.
        var metadata=SeckillTiming.measure("activity_metadata",()->activities.metadata(activity));
        if(metadata==null) throw new AcceptanceConflict("Activity missing; retain Redis tasks");
        int buckets=metadata.getBucketCount()==null?1:metadata.getBucketCount();
        for(var command:commands) {
            if(command.inventoryBuckets()!=buckets || command.inventoryBucket()<0 || command.inventoryBucket()>=buckets ||
                    buckets!=1 && buckets!=2 && buckets!=4 && buckets!=8)
                throw new AcceptanceConflict("Invalid handoff inventory bucket");
        }
        String marks=String.join(",",java.util.Collections.nCopies(unique.size(),"?"));
        List<Object> numbers=new java.util.ArrayList<>(unique.keySet());
        List<Map<String,Object>> existing=new java.util.ArrayList<>(jdbc.queryForList("select order_number,activity_id,user_id,request_id,request_hash,bucket_id from seckill_submit_request where order_number in ("+marks+")",numbers.toArray()));
        String tuples=String.join(",",java.util.Collections.nCopies(unique.size(),"(?,?,?)"));
        List<Object> keys=new java.util.ArrayList<>();
        for(var command:unique.values()) { keys.add(command.getUserId());keys.add(activity);keys.add(command.getRequest().getRequestId()); }
        existing.addAll(jdbc.queryForList("select order_number,activity_id,user_id,request_id,request_hash,bucket_id from seckill_submit_request where (user_id,activity_id,request_id) in ("+tuples+")",keys.toArray()));
        java.util.Set<String> old=new java.util.HashSet<>();
        for(var row:existing) {
            var command=unique.get((String)row.get("order_number"));
            if(command==null || !command.getRequestHash().equals(row.get("request_hash"))
                || command.getUserId().longValue()!=((Number)row.get("user_id")).longValue()
                || activity.longValue()!=((Number)row.get("activity_id")).longValue()
                || !command.getRequest().getRequestId().equals(row.get("request_id")) || command.inventoryBucket()!=((Number)row.get("bucket_id")).intValue())
                throw new AcceptanceConflict("Conflicting durable acceptance; retain Redis tasks");
            old.add(command.getOrderNumber());
        }
        List<SeckillOrderMessageDTO> fresh=unique.values().stream().filter(command -> !old.contains(command.getOrderNumber())).toList();
        if(fresh.isEmpty()) { SeckillTiming.outcome("skipped"); return; }
        String values=String.join(",",java.util.Collections.nCopies(fresh.size(),"(?,?,?,?,?,?,?,?,?,date_add(now(3),interval 10 second))"));
        List<Object> args=new java.util.ArrayList<>();
        for(var command:fresh) {
            LocalDateTime accepted=LocalDateTime.ofInstant(Instant.ofEpochMilli(command.getAcceptedAt()),ZoneId.systemDefault());
            java.util.Collections.addAll(args,command.getOrderNumber(),activity,command.getUserId(),command.getRequest().getRequestId(),command.getRequestHash(),JSON.toJSONString(command),accepted,accepted.plusSeconds(processingSeconds),command.inventoryBucket());
        }
        SeckillTiming.measure("accept_insert", () -> jdbc.update("insert into seckill_submit_request(order_number,activity_id,user_id,request_id,request_hash,command_body,accepted_at,deadline_at,bucket_id,next_at) values "+values,args.toArray()));
        SeckillTiming.run("command_outbox", () -> publisher.seckillDeferredBatch(fresh));
        SeckillTiming.outcome("bridged");
    }

    public Map<String,Object> get(String number) {
        List<Map<String,Object>> rows=jdbc.queryForList("select * from seckill_submit_request where order_number=?",number);
        return rows.isEmpty()?null:rows.get(0);
    }
    public Map<String,Map<String,Object>> getBatch(List<String> numbers) {
        if(numbers.isEmpty()) return Map.of();
        if(numbers.size()>16) throw new IllegalArgumentException("Seckill preparation batch exceeds 16");
        var rows=jdbc.queryForList("select * from seckill_submit_request where order_number in ("+
                String.join(",",java.util.Collections.nCopies(numbers.size(),"?"))+")",numbers.toArray());
        var result=new java.util.HashMap<String,Map<String,Object>>();
        rows.forEach(row->result.put((String)row.get("order_number"),row));
        return result;
    }
    public Map<String,Object> lock(String number) {
        List<Map<String,Object>> rows=jdbc.queryForList("select *,deadline_at<=now(3) expired from seckill_submit_request where order_number=? for update",number);
        return rows.isEmpty()?null:rows.get(0);
    }
    public Map<String,Map<String,Object>> lockBatch(List<String> numbers) {
        if(numbers.isEmpty()) return Map.of();
        if(numbers.size()>16) throw new IllegalArgumentException("Seckill lock batch exceeds 16");
        var rows=jdbc.queryForList("select *,deadline_at<=now(3) expired from seckill_submit_request where order_number in ("+
                String.join(",",java.util.Collections.nCopies(numbers.size(),"?"))+") order by order_number for update",numbers.toArray());
        var result=new java.util.HashMap<String,Map<String,Object>>();
        rows.forEach(row->result.put((String)row.get("order_number"),row));
        return result;
    }
    public Map<String,Object> find(Long user,String request,Long activity) {
        List<Map<String,Object>> rows=activity==null
            ?jdbc.queryForList("select * from seckill_submit_request where user_id=? and request_id=? order by accepted_at desc limit 1",user,request)
            :jdbc.queryForList("select * from seckill_submit_request where user_id=? and activity_id=? and request_id=?",user,activity,request);
        return rows.isEmpty()?null:rows.get(0);
    }
    public List<Map<String,Object>> ready() {
        return jdbc.queryForList("select * from seckill_submit_request where state='ACCEPTED' and next_at<=now(3) order by next_at limit 100");
    }
    public void succeeded(SeckillOrderMessageDTO command) {
        if(creationMetrics!=null)creationMetrics.committed("seckill",java.util.Collections.singletonList(command.getAcceptedAt()));
        jdbc.update("update seckill_submit_request set state='SUCCEEDED',last_error=null where order_number=? and state='ACCEPTED'",command.getOrderNumber());
        event(command.getOrderNumber(),command.getActivityId(),command.getUserId(),command.getRequest().getRequestId(),"SUCCEEDED",null,command.inventoryBucket());
    }
    public void succeededBatch(List<SeckillOrderMessageDTO> commands) {
        if(creationMetrics!=null)creationMetrics.committed("seckill",commands.stream().map(SeckillOrderMessageDTO::getAcceptedAt).toList());
        if(commands.isEmpty())return;
        var placeholders=String.join(",",java.util.Collections.nCopies(commands.size(),"?"));
        var numbers=commands.stream().map(SeckillOrderMessageDTO::getOrderNumber).toArray();
        int changed=jdbc.update("update seckill_submit_request set state='SUCCEEDED',last_error=null where state='ACCEPTED' and order_number in ("+placeholders+")",numbers);
        if(changed!=commands.size())throw new IllegalStateException("Seckill acceptance changed during fulfillment");
        var values=String.join(",",java.util.Collections.nCopies(commands.size(),"(?,?,?,?,?,?,?)"));
        var args=new java.util.ArrayList<Object>();
        for(var command:commands)java.util.Collections.addAll(args,command.getOrderNumber(),command.getActivityId(),
                command.getUserId(),command.getRequest().getRequestId(),"SUCCEEDED",null,command.inventoryBucket());
        jdbc.update("insert into seckill_cache_event(order_number,activity_id,user_id,request_id,state,last_error,bucket_id) values "+values+
                " on duplicate key update state=values(state),last_error=values(last_error),next_at=now(3)",args.toArray());
    }
    public void event(String number,Long activity,Long user,String request,String state,String error) {
        event(number,activity,user,request,state,error,0);
    }
    public void event(String number,Long activity,Long user,String request,String state,String error,int bucket) {
        jdbc.update("insert into seckill_cache_event(order_number,activity_id,user_id,request_id,state,last_error,bucket_id) values(?,?,?,?,?,?,?) on duplicate key update state=values(state),last_error=values(last_error),next_at=now(3)",number,activity,user,request,state,error,bucket);
    }
    public List<Map<String,Object>> events() { return jdbc.queryForList("select * from seckill_cache_event where next_at<=now(3) order by next_at limit 200"); }
    public void eventRetry(String number,String state) { jdbc.update("update seckill_cache_event set next_at=date_add(now(3),interval 5 second) where order_number=? and state=?",number,state); }
    public void eventDone(String number,String state) { jdbc.update("delete from seckill_cache_event where order_number=? and state=?",number,state); }
    public void eventsDone(List<Map<String,Object>> events) {
        if(events.isEmpty())return;
        var args=new java.util.ArrayList<Object>();events.forEach(event->{args.add(event.get("order_number"));args.add(event.get("state"));});
        if(events.size()>200)throw new IllegalArgumentException("Cache completion batch exceeds 200");
        // A tuple IN predicate on (PK,state) caused ALL scans and locked unrelated events.
        // Drive unique primary-key lookups from a bounded parameter table; state still fences newer events.
        String wanted=String.join(" union all ",java.util.Collections.nCopies(events.size(),"select ? order_number,? state"));
        jdbc.update("delete e from ("+wanted+") wanted straight_join seckill_cache_event e "+
            "on e.order_number=wanted.order_number and e.state=wanted.state",args.toArray());
    }

    @Transactional(rollbackFor=Exception.class)
    public void republishOrExpire(String number) {
        Map<String,Object> snapshot=get(number);
        if(snapshot==null) return;
        SeckillInventory.lock(activities,JSON.parseObject((String)snapshot.get("command_body"),SeckillOrderMessageDTO.class));
        Map<String,Object> row=lock(number);
        if(!"ACCEPTED".equals(row.get("state"))) return;
        if(((Number)row.get("expired")).intValue()==1) {
            jdbc.update("update seckill_submit_request set state='FAILED',last_error='秒杀建单等待超时' where order_number=?",number);
            event(number,((Number)row.get("activity_id")).longValue(),((Number)row.get("user_id")).longValue(),(String)row.get("request_id"),"FAILED","秒杀建单等待超时，请重新抢购",((Number)row.get("bucket_id")).intValue());
        } else if(jdbc.update("update seckill_submit_request set next_at=date_add(now(3),interval 10 second) where order_number=? and next_at<=now(3)",number)==1) {
            publisher.seckillDeferred(JSON.parseObject((String)row.get("command_body"),SeckillOrderMessageDTO.class));
        }
    }

    /** Same lock order as creation: activity -> acceptance. A late MQ message cannot revive FAILED. */
    @Transactional(rollbackFor=Exception.class)
    public void failed(String number,Exception failure,boolean permanent) {
        Map<String,Object> snapshot=get(number);
        if(snapshot==null) throw new MissingAcceptance(number);
        SeckillInventory.lock(activities,JSON.parseObject((String)snapshot.get("command_body"),SeckillOrderMessageDTO.class));
        Map<String,Object> row=lock(number);
        if(!"ACCEPTED".equals(row.get("state"))) return;
        String detail=String.valueOf(failure.getMessage());
        if(detail.length()>500) detail=detail.substring(0,500);
        int attempts=((Number)row.get("attempts")).intValue()+1;
        boolean expired=((Number)row.get("expired")).intValue()==1;
        if(permanent || expired || attempts>=8) {
            jdbc.update("update seckill_submit_request set state='FAILED',attempts=?,last_error=? where order_number=?",attempts,detail,number);
            event(number,((Number)row.get("activity_id")).longValue(),((Number)row.get("user_id")).longValue(),(String)row.get("request_id"),"FAILED",detail,((Number)row.get("bucket_id")).intValue());
        } else jdbc.update("update seckill_submit_request set attempts=?,last_error=?,next_at=date_add(now(3),interval 5 second) where order_number=?",attempts,detail,number);
    }
}
