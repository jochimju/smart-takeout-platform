package com.sky.service.impl;

import com.alibaba.fastjson.JSON;
import com.sky.exception.OrderBusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.*;

/** Preserve idempotency tombstones and unfinished work. No blind failed-order resurrection. */
@Service @RequiredArgsConstructor
public class TradeRuntimeOperations {
 private final JdbcTemplate jdbc;private final SeckillCache cache;
 public Map<String,Long> backlog() {
  var result=new LinkedHashMap<String,Long>();
  result.put("publishFailed",count("select count(*) from mq_fail_message where status=2"));
  result.put("expiredLeases",count("select count(*) from mq_fail_message where status=3 and lease_until<now()"));
  result.put("acceptedOverdue",count("select count(*) from seckill_submit_request where state='ACCEPTED' and deadline_at<now()")+count("select count(*) from order_submit_request where state='ACCEPTED' and deadline_at<now()"));
  result.put("cacheCompensationStale",count("select count(*) from seckill_cache_event where created_at<date_sub(now(),interval 30 second)"));
  result.put("reliabilityFailed",count("select count(*) from order_reliability_job where state='FAILED'"));
  result.put("paymentRecoveryFailed",count("select count(*) from payment_recovery_job where state='FAILED'"));
  result.put("unretiredActivities",count("select count(*) from seckill_activity where runtime_retired=0"));return result;
 }
 private long count(String sql){return jdbc.queryForObject(sql,Long.class);}
 @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
 public int archive(int retentionDays) {
  if(retentionDays<7||retentionDays>365)throw new OrderBusinessException("归档保留期必须为7至365天");
  var rows=jdbc.queryForList("select m.* from mq_fail_message m where m.update_time<date_sub(now(),interval ? day) and (m.status=4 or (m.status=1 and (exists(select 1 from seckill_submit_request r where r.order_number=JSON_UNQUOTE(JSON_EXTRACT(if(JSON_VALID(m.message_body),m.message_body,'{}'),'$.orderNumber')) and r.state in ('SUCCEEDED','FAILED')) or exists(select 1 from order_submit_request r where r.order_number=JSON_UNQUOTE(JSON_EXTRACT(if(JSON_VALID(m.message_body),m.message_body,'{}'),'$.orderNumber')) and r.state in ('SUCCEEDED','FAILED'))))) order by m.id limit 200 for update skip locked",retentionDays);
  for(var row:rows) {
   jdbc.update("insert into trade_runtime_archive(source,event_key,payload) values('MQ',?,?)",row.get("id").toString(),JSON.toJSONString(row));
   jdbc.update("delete from mq_fail_message where id=?",row.get("id"));
  }
  return rows.size();
 }
 @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
 public boolean retire(Long activityId) {
  var row=jdbc.queryForMap("select * from seckill_activity where id=? for update",activityId);
  if(((Number)row.get("runtime_retired")).intValue()==1)return false;
  if(((Number)row.get("status")).intValue()!=0)throw new OrderBusinessException("活动未停用");
  cache.pause(activityId);
  if(!cache.drained(activityId))throw new OrderBusinessException("Redis预留未排空");
  if(jdbc.queryForObject("select count(*) from seckill_inventory_allocation where activity_id=? and state='RETURNED'",Integer.class,activityId)!=1
    || jdbc.queryForObject("select count(*) from seckill_submit_request where activity_id=? and state='ACCEPTED'",Integer.class,activityId)>0
    || jdbc.queryForObject("select count(*) from seckill_cache_event where activity_id=?",Integer.class,activityId)>0
    || jdbc.queryForObject("select count(*) from seckill_reservation r join orders o on o.number=r.order_number where r.activity_id=? and o.status=1",Integer.class,activityId)>0)
   throw new OrderBusinessException("库存未回收或交易事件尚未终结");
  jdbc.update("update seckill_activity set runtime_retired=1 where id=?",activityId);
  // Keep Redis identity/results for late queries and cancellation. Retirement only removes background scanning.
  return true;
 }
 @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
 public void replay(String source,String key,long operator,String reason) {
  if(reason==null||reason.isBlank()||reason.length()>500)throw new OrderBusinessException("重放必须填写原因");
  if("MQ".equals(source)) {
   if(key==null||!key.matches("[1-9][0-9]{0,18}"))throw new OrderBusinessException("消息编号无效");
   var rows=jdbc.queryForList("select * from mq_fail_message where id=? and status=2 for update",key);
   if(rows.isEmpty())throw new OrderBusinessException("消息不处于失败状态");var row=rows.get(0);
   String route=(String)row.get("routing_key");
   if(!"order.submit".equals(route)&&!com.sky.service.mq.SeckillRouting.isOrder(route))throw new OrderBusinessException("该消息请使用专用恢复入口");
   String number=JSON.parseObject((String)row.get("message_body")).getString("orderNumber");
   String table="order.submit".equals(route)?"order_submit_request":"seckill_submit_request";
   if(jdbc.queryForObject("select count(*) from "+table+" where order_number=? and state='ACCEPTED' and deadline_at>now()",Integer.class,number)!=1)
    throw new OrderBusinessException("原受理已终结或超时，不允许复活，请发起新请求");
   jdbc.update("update mq_fail_message set status=0,retry_count=0,available_at=now(),claim_token=null,lease_until=null,update_time=now() where id=?",key);
  } else if("JOB".equals(source)) {
   if(jdbc.update("update order_reliability_job set state='READY',attempts=0,next_at=now(),lease_token=null,lease_until=null,updated_at=now() where id=? and state='FAILED'",key)!=1)
    throw new OrderBusinessException("任务不处于失败状态");
  } else throw new OrderBusinessException("重放来源无效");
  jdbc.update("insert into trade_replay_audit(source,event_key,operator_id,reason) values(?,?,?,?)",source,key,operator,reason);
 }
}
