package com.sky.service.impl;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfSystemProperty(named="order.integration",matches="true")
class TradeRuntimeOperationsMysqlTest {
 static JdbcTemplate jdbc;static DriverManagerDataSource ds;static TransactionTemplate tx;static String schema,base,user,password;
 TradeRuntimeOperations operations;SeckillCache cache;
 @BeforeAll static void setup()throws Exception {
  var yaml=new YamlPropertiesFactoryBean();yaml.setResources(new ClassPathResource("application-dev.yml"));var p=yaml.getObject();
  user=p.getProperty("sky.datasource.username");password=p.getProperty("sky.datasource.password");base="jdbc:mysql://"+p.getProperty("sky.datasource.host")+":"+p.getProperty("sky.datasource.port")+"/";
  schema="sky_governance_it_"+UUID.randomUUID().toString().replace("-","");
  try(var c=DriverManager.getConnection(base+"?useSSL=false&allowPublicKeyRetrieval=true",user,password);var s=c.createStatement()){s.execute("create database "+schema);}
  ds=new DriverManagerDataSource(base+schema+"?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai",user,password);jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
  jdbc.execute("create table seckill_activity(id bigint primary key,status int) engine=InnoDB");
  jdbc.execute("create table seckill_cache_event(order_number varchar(64) primary key,activity_id bigint,next_at datetime) engine=InnoDB");
  jdbc.execute("create table mq_fail_message(id bigint primary key,exchange_name varchar(128),routing_key varchar(128),message_body text,status int,retry_count int,update_time datetime,available_at datetime,claim_token varchar(64),lease_until datetime) engine=InnoDB");
  jdbc.execute("create table seckill_submit_request(order_number varchar(64) primary key,activity_id bigint,state varchar(16),deadline_at datetime) engine=InnoDB");
  jdbc.execute("create table order_submit_request(order_number varchar(64) primary key,state varchar(16),deadline_at datetime) engine=InnoDB");
  jdbc.execute("create table seckill_inventory_allocation(activity_id bigint primary key,state varchar(16)) engine=InnoDB");
  jdbc.execute("create table seckill_reservation(order_number varchar(64),activity_id bigint) engine=InnoDB");
  jdbc.execute("create table orders(number varchar(64) primary key,status int) engine=InnoDB");
  jdbc.execute("create table order_reliability_job(id varchar(64) primary key,state varchar(16),attempts int,next_at datetime,lease_token varchar(64),lease_until datetime,updated_at datetime) engine=InnoDB");
  jdbc.execute("create table payment_recovery_job(state varchar(16)) engine=InnoDB");
  try(var c=ds.getConnection()){ScriptUtils.executeSqlScript(c,new ClassPathResource("db/migration/V2026101606__runtime_governance.sql"));}
 }
 @AfterAll static void cleanup()throws Exception {if(schema!=null&&schema.matches("sky_governance_it_[a-f0-9]{32}"))try(var c=DriverManager.getConnection(base+"?useSSL=false&allowPublicKeyRetrieval=true",user,password);var s=c.createStatement()){s.execute("drop database "+schema);}}
 @BeforeEach void resetData(){for(String table:List.of("seckill_activity","seckill_cache_event","mq_fail_message","seckill_submit_request","order_submit_request","seckill_inventory_allocation","seckill_reservation","orders","order_reliability_job","payment_recovery_job","trade_runtime_archive","trade_replay_audit"))jdbc.update("delete from "+table);cache=mock(SeckillCache.class);operations=new TradeRuntimeOperations(jdbc,cache);}
 void message(int id,int status,String body){jdbc.update("insert into mq_fail_message(id,routing_key,message_body,status,retry_count,update_time) values(?,'order.submit',?,?,5,date_sub(now(),interval 40 day))",id,body,status);}
 @Test void archiveKeepsPendingFailedAndUnfinishedTimeoutsAndPreservesIdempotencyRows(){
  jdbc.update("insert into order_submit_request values('DONE','SUCCEEDED',now())");message(1,1,"{\"orderNumber\":\"DONE\"}");message(2,0,"{}");message(3,2,"{}");message(4,1,"\"unpaid-timeout\"");message(5,4,"\"terminal-timeout\"");message(6,1,"legacy-invalid-json");
  assertEquals(2,tx.<Integer>execute(s->operations.archive(30)).intValue());assertEquals(0,tx.<Integer>execute(s->operations.archive(30)).intValue());
  assertEquals(4,jdbc.queryForObject("select count(*) from mq_fail_message",Integer.class));assertEquals(2,jdbc.queryForObject("select count(*) from trade_runtime_archive",Integer.class));assertEquals(1,jdbc.queryForObject("select count(*) from order_submit_request",Integer.class));
 }
 @Test void replayRequiresLiveAcceptanceAndAuditCommitsTogether(){
  jdbc.update("insert into order_submit_request values('LIVE','ACCEPTED',date_add(now(),interval 1 minute))");message(1,2,"{\"orderNumber\":\"LIVE\"}");tx.executeWithoutResult(s->operations.replay("MQ","1",7,"broker recovered"));
  assertEquals(0,jdbc.queryForObject("select status from mq_fail_message",Integer.class));assertEquals(1,jdbc.queryForObject("select count(*) from trade_replay_audit",Integer.class));
  assertThrows(Exception.class,()->tx.executeWithoutResult(s->operations.replay("MQ","1",7,"duplicate action")));
  assertThrows(Exception.class,()->tx.executeWithoutResult(s->operations.replay("MQ","1abc",7,"invalid identifier")));
  jdbc.update("update mq_fail_message set status=2");jdbc.update("update order_submit_request set state='FAILED'");
  assertThrows(Exception.class,()->tx.executeWithoutResult(s->operations.replay("MQ","1",7,"must not revive")));assertEquals(1,jdbc.queryForObject("select count(*) from trade_replay_audit",Integer.class));
 }
 @Test void retirementRefusesOutstandingWorkAndLeavesOrderHistory(){
  jdbc.update("insert into seckill_activity(id,status) values(1,0)");jdbc.update("insert into seckill_inventory_allocation values(1,'RETURNED')");
  when(cache.drained(1L)).thenReturn(false);assertThrows(Exception.class,()->tx.execute(s->operations.retire(1L)));
  when(cache.drained(1L)).thenReturn(true);jdbc.update("insert into seckill_cache_event(order_number,activity_id,next_at) values('old',1,now())");assertThrows(Exception.class,()->tx.execute(s->operations.retire(1L)));
  jdbc.update("delete from seckill_cache_event");assertTrue(tx.<Boolean>execute(s->operations.retire(1L)).booleanValue());assertFalse(tx.<Boolean>execute(s->operations.retire(1L)).booleanValue());
  assertEquals(0,jdbc.queryForObject("select count(*) from seckill_activity where runtime_retired=0",Integer.class));verify(cache,atLeastOnce()).pause(1L);verify(cache,never()).evict(1L);
 }
 @Test void backlogDistinguishesExpiredAcceptanceAndCompensationAge(){
  jdbc.update("insert into order_submit_request values('OLD','ACCEPTED',date_sub(now(),interval 1 minute))");message(1,2,"{}");jdbc.update("insert into seckill_cache_event(order_number,activity_id,next_at,created_at) values('old',1,now(),date_sub(now(),interval 1 minute))");
  var counts=operations.backlog();assertEquals(1L,counts.get("acceptedOverdue"));assertEquals(1L,counts.get("publishFailed"));assertEquals(1L,counts.get("cacheCompensationStale"));
 }
}
