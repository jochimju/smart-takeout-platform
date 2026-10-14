package com.sky.payment;

import com.alibaba.fastjson.JSONObject;
import com.sky.client.AccountClient;
import com.sky.context.BaseContext;
import com.sky.entity.*;
import com.sky.mapper.*;
import com.sky.service.impl.*;
import com.sky.task.OrderReliabilityTask;
import com.sky.utils.WeChatPayUtil;
import com.sky.utils.properties.WeChatProperties;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@EnabledIfSystemProperty(named="payment.mysql.integration",matches="true")
class PaymentRecoveryMysqlTest {
    static String schema="sky_payment_it_"+UUID.randomUUID().toString().replace("-","");
    static String base=System.getProperty("payment.mysql.url","jdbc:mysql://127.0.0.1:3306/"),user=System.getProperty("payment.mysql.user","root");
    static String password=System.getenv().getOrDefault("PAYMENT_TEST_DB_PASSWORD","1234"), options="?serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true";
    static JdbcTemplate jdbc;static DriverManagerDataSource ds;static DataSourceTransactionManager tm;
    static OrderLifecycleService orders;static RedPacketServiceImpl packets;static PaymentRecoveryStore recovery;static OrderReliabilityStore jobs;
    static PaymentSettlement settlement;static WeChatPayUtil wechat;static WeChatProperties properties;
    static PaymentRecoveryTask task;static PaymentReconciliation reconciliation;
    static <T> T transactional(T target) {var proxy=new ProxyFactory(target);proxy.setProxyTargetClass(true);proxy.addAdvice(new TransactionInterceptor(tm,new AnnotationTransactionAttributeSource()));return (T)proxy.getProxy();}
    @BeforeAll static void schema() throws Exception {
        try(var connection=DriverManager.getConnection(base+options,user,password);var statement=connection.createStatement()) {statement.execute("create database "+schema);}
        ds=new DriverManagerDataSource(base+schema+options,user,password);jdbc=new JdbcTemplate(ds);tm=new DataSourceTransactionManager(ds);
        jdbc.execute("create table orders(id bigint primary key,number varchar(64) unique,user_id bigint,status int,pay_status int,amount decimal(12,2),order_time datetime,checkout_time datetime,cancel_reason varchar(255),rejection_reason varchar(255),cancel_time datetime,delivery_time datetime) engine=InnoDB");
        jdbc.execute("create table order_detail(id bigint primary key,order_id bigint,dish_id bigint,setmeal_id bigint,number int) engine=InnoDB");
        jdbc.execute("create table user_coupon(id bigint primary key,status int,order_id bigint,use_time datetime) engine=InnoDB");
        jdbc.execute("create table trade_inventory(product_type varchar(16),product_id bigint,stock int,version bigint default 0,primary key(product_type,product_id)) engine=InnoDB");
        try(var connection=ds.getConnection()) {
            for(String migration:List.of("V2026091201__order_reliability.sql","V2026091302__red_packet_module.sql","V2026091305__red_packet_purchase_controls.sql","V2026101502__payment_recovery.sql"))
                ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migration/"+migration));
        }
        var factory=new SqlSessionFactoryBean();factory.setDataSource(ds);factory.setTypeAliasesPackage("com.sky.entity,com.sky.dto,com.sky.vo");
        factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath:mapper/*.xml"));
        var configuration=new org.apache.ibatis.session.Configuration();configuration.setMapUnderscoreToCamelCase(true);factory.setConfiguration(configuration);
        var sql=new SqlSessionTemplate(factory.getObject());
        for(Class<?> mapper:List.of(OrderMapper.class,OrderDetailMapper.class,UserCouponMapper.class,RedPacketMapper.class)) if(!sql.getConfiguration().hasMapper(mapper)) sql.getConfiguration().addMapper(mapper);
        recovery=new PaymentRecoveryStore(jdbc);jobs=new OrderReliabilityStore(jdbc);wechat=mock(WeChatPayUtil.class);
        properties=new WeChatProperties();properties.setAppid("app");properties.setMchid("merchant");
        orders=transactional(new OrderLifecycleService(sql.getMapper(OrderMapper.class),sql.getMapper(OrderDetailMapper.class),new TradeInventoryService(jdbc,mock(CatalogQuoteService.class)),sql.getMapper(UserCouponMapper.class),mock(SeckillReservationService.class),jobs,mock(BusinessEventOutbox.class),jdbc));
        var packetTarget=new RedPacketServiceImpl(sql.getMapper(RedPacketMapper.class),mock(AccountClient.class),wechat);
        ReflectionTestUtils.setField(packetTarget,"jdbc",jdbc);ReflectionTestUtils.setField(packetTarget,"jobs",jobs);ReflectionTestUtils.setField(packetTarget,"recovery",recovery);
        packets=transactional(packetTarget);settlement=transactional(new PaymentSettlement(orders,packets,properties,jdbc));
        task=new PaymentRecoveryTask(recovery,wechat,properties,orders,packets,jdbc,settlement);
        reconciliation=transactional(new PaymentReconciliation(jdbc,wechat,properties));
    }
    @AfterAll static void cleanup() throws Exception {
        if(schema.matches("sky_payment_it_[0-9a-f]{32}")) try(var connection=DriverManager.getConnection(base+options,user,password);var statement=connection.createStatement()) {statement.execute("drop database "+schema);}
    }
    @BeforeEach void resetData() {
        for(String table:List.of("payment_reconciliation_difference","payment_reconciliation_run","payment_recovery_job","order_reliability_job","order_payment_receipt","user_red_packet","red_packet_purchase_order","order_detail","user_coupon","orders","trade_inventory")) jdbc.update("delete from "+table);
        jdbc.update("update red_packet_package set reserved_amount=0,issued_amount=0");reset(wechat);BaseContext.removeCurrentId();
    }
    @Test void verifiedPaymentAndTimeoutRaceNeverDoubleReleaseOrReviveCancelledOrder() throws Exception {
        order("RACE1",true);var paid=receipt("RACE1",1000);
        paid.put("success_time",OffsetDateTime.now(ZoneOffset.ofHours(8)).minusMinutes(2).withNano(0).toString());
        var gate=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            var timeout=pool.submit(()->{try{gate.await();orders.timeout("RACE1");}catch(InterruptedException e){throw new RuntimeException(e);}});
            var payment=pool.submit(()->{try{gate.await();settlement.accept(paid);}catch(InterruptedException e){throw new RuntimeException(e);}});
            gate.countDown();timeout.get(10,TimeUnit.SECONDS);payment.get(10,TimeUnit.SECONDS);
            orders.timeout("RACE1");settlement.accept(paid);
            assertEquals(1,count("select count(*) from order_payment_receipt"));int state=count("select status from orders");
            assertTrue(state==2||state==6);
            assertEquals(state==2?5:7,count("select stock from trade_inventory"));
            assertEquals(state==2?0:1,count("select count(*) from order_reliability_job where kind='REFUND'"));
        } finally {pool.shutdownNow();}
    }
    void order(String number,boolean expired) {
        LocalDateTime deadline=LocalDateTime.now().plusMinutes(expired?-1:5);
        jdbc.update("insert into orders(id,number,user_id,status,pay_status,amount,order_time,expire_time) values(1,?,7,1,0,10,?,?)",number,deadline.minusMinutes(15),deadline);
        jdbc.update("insert into order_detail values(1,1,1,null,2)");jdbc.update("insert into trade_inventory(product_type,product_id,stock) values('DISH',1,5)");
        recovery.begin(number,BigDecimal.TEN,deadline);
    }
    void packet(String number,boolean expired,boolean closed) {
        LocalDateTime deadline=LocalDateTime.now().plusMinutes(expired?-1:5);
        jdbc.update("insert into red_packet_purchase_order(id,order_no,user_id,package_id,pay_amount,packet_count_snapshot,packet_amount_snapshot,packet_total_amount_snapshot,valid_months_snapshot,status,create_time,expire_time) values(1,?,7,1,25,5,5,25,1,?,now(),?)",number,closed?2:0,deadline);
        jdbc.update("update red_packet_package set reserved_amount=? where id=1",closed?0:25);
        recovery.begin(number,new BigDecimal("25"),deadline);
    }
    JSONObject receipt(String number,int cents) {
        var result=new JSONObject();result.put("out_trade_no",number);result.put("transaction_id","TX"+number);result.put("appid","app");result.put("mchid","merchant");result.put("trade_state","SUCCESS");
        result.put("success_time",OffsetDateTime.now(ZoneOffset.ofHours(8)).withNano(0).toString());var amount=new JSONObject();amount.put("total",cents);amount.put("currency","CNY");result.put("amount",amount);return result;
    }
    Map<String,Object> claim(String id) {jdbc.update("update payment_recovery_job set next_at=now() where id=?",id);var job=jdbc.queryForMap("select * from payment_recovery_job where id=?",id);job.put("token",recovery.claim(id));return job;}
    int count(String query) {return jdbc.queryForObject(query,Integer.class);}
    @Test void missingCallbackRecoveredAndRepeatedQueryCannotDuplicateReceipt() throws Exception {
        order("ORDER1",false);var paid=receipt("ORDER1",1000);when(wechat.queryOrder("ORDER1")).thenReturn(paid);var job=claim("check:ORDER1");task.process(job,(String)job.get("token"));
        assertEquals(2,count("select status from orders"));assertEquals(1,count("select count(*) from order_payment_receipt"));
        settlement.accept(paid);assertEquals(1,count("select count(*) from order_payment_receipt"));
        assertEquals("DONE",jdbc.queryForObject("select state from payment_recovery_job",String.class));
    }
    @Test void timelyChannelPaymentWithDelayedNotificationDoesNotCancelStillPendingOrder() {
        order("ORDER1",true);var paid=receipt("ORDER1",1000);
        paid.put("success_time",OffsetDateTime.now(ZoneOffset.ofHours(8)).minusMinutes(2).withNano(0).toString());
        settlement.accept(paid);assertEquals(2,count("select status from orders"));
        assertEquals(5,count("select stock from trade_inventory"));assertEquals(0,count("select count(*) from order_reliability_job where kind='REFUND'"));
    }
    @Test void timelyReceiptCannotReviveOrderAfterTimeoutReleasedInventory() {
        order("ORDER1",true);orders.timeout("ORDER1");var paid=receipt("ORDER1",1000);
        paid.put("success_time",OffsetDateTime.now(ZoneOffset.ofHours(8)).minusMinutes(2).withNano(0).toString());
        settlement.accept(paid);settlement.accept(paid);assertEquals(6,count("select status from orders"));
        assertEquals(7,count("select stock from trade_inventory"));assertEquals(1,count("select count(*) from order_reliability_job where kind='REFUND'"));
    }
    @Test void paidDuringCloseIsRequeriedThenRefundedWithoutRevivingOrder() throws Exception {
        order("ORDER1",false);orders.cancel(1L,"cancel","ADMIN");
        var unpaid=new JSONObject();unpaid.put("out_trade_no","ORDER1");unpaid.put("appid","app");unpaid.put("mchid","merchant");unpaid.put("trade_state","NOTPAY");
        var close=new JSONObject();close.put("code","ORDERPAID");when(wechat.queryOrder("ORDER1")).thenReturn(unpaid,receipt("ORDER1",1000));when(wechat.closeOrder("ORDER1")).thenReturn(close);
        var first=claim("close:ORDER1");task.process(first,(String)first.get("token"));assertEquals(0,count("select count(*) from order_payment_receipt"));
        var next=claim("close:ORDER1");task.process(next,(String)next.get("token"));assertEquals(6,count("select status from orders"));assertEquals(1,count("select count(*) from order_reliability_job where kind='REFUND'"));
        assertEquals(7,count("select stock from trade_inventory"));
    }
    @Test void unknownPrepayOutcomeKeepsCheckingAndLeaseCanRecoverAfterRestart() throws Exception {
        order("ORDER1",false);var missing=new JSONObject();missing.put("code","ORDER_NOT_EXIST");when(wechat.queryOrder("ORDER1")).thenReturn(missing);
        var first=claim("check:ORDER1");task.process(first,(String)first.get("token"));assertEquals("READY",jdbc.queryForObject("select state from payment_recovery_job",String.class));
        var next=claim("check:ORDER1");jdbc.update("update payment_recovery_job set lease_until=date_sub(now(),interval 1 second)");
        assertNotNull(recovery.claim("check:ORDER1"));recovery.done("check:ORDER1",(String)next.get("token"));assertEquals("RUNNING",jdbc.queryForObject("select state from payment_recovery_job",String.class));
    }
    @Test void terminalErrorsAreVisibleAndReplayableAndReceiptMismatchNeverPays() throws Exception {
        order("ORDER1",false);var wrong=receipt("ORDER1",999);when(wechat.queryOrder("ORDER1")).thenReturn(wrong);
        for(int i=0;i<10;i++) {var job=claim("check:ORDER1");try {task.process(job,(String)job.get("token"));fail("expected mismatch");} catch(SecurityException error) {recovery.failed("check:ORDER1",(String)job.get("token"),error);}}
        assertEquals(1,recovery.failures().size());assertEquals(0,count("select count(*) from order_payment_receipt"));assertEquals(1,recovery.replay("check:ORDER1"));
    }
    @Test void lateRedPacketReceiptCreatesExactlyOneRefundAndNoAssetsUnderConcurrency() throws Exception {
        packet("RP1",true,false);var paid=receipt("RP1",2500);ExecutorService executor=Executors.newFixedThreadPool(4);
        try {List<Future<?>> calls=new ArrayList<>();for(int i=0;i<8;i++) calls.add(executor.submit(()->settlement.accept(paid)));for(var call:calls) call.get(10,TimeUnit.SECONDS);} finally {executor.shutdownNow();}
        assertEquals(3,count("select status from red_packet_purchase_order"));assertEquals(0,count("select count(*) from user_red_packet"));assertEquals(0,count("select reserved_amount from red_packet_package where id=1"));
        assertEquals(1,count("select count(*) from order_payment_receipt"));assertEquals(1,count("select count(*) from order_reliability_job where kind='REFUND'"));
    }
    @Test void alreadyClosedRedPacketRefundChecksChannelSuccessBeforeCompletion() throws Exception {
        packet("RP1",true,true);settlement.accept(receipt("RP1",2500));
        var refund=new JSONObject();refund.put("status","PROCESSING");when(wechat.queryRefund(anyString())).thenReturn(refund.toJSONString());
        var worker=new OrderReliabilityTask(jobs,mock(org.springframework.amqp.rabbit.core.RabbitTemplate.class),wechat,jdbc,new TransactionTemplate(tm));
        var job=jdbc.queryForMap("select * from order_reliability_job where id='refund:RP1'");String token=jobs.claim("refund:RP1");worker.refund(job,token);assertEquals(3,count("select status from red_packet_purchase_order"));
        JSONObject success=new JSONObject();success.put("status","SUCCESS");success.put("out_trade_no","RP1");success.put("out_refund_no",UUID.nameUUIDFromBytes("refund:RP1".getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString().replace("-",""));
        var amount=new JSONObject();amount.put("refund",2500);success.put("amount",amount);when(wechat.queryRefund(anyString())).thenReturn(success.toJSONString());
        jdbc.update("update order_reliability_job set next_at=now()");worker.refund(job,jobs.claim("refund:RP1"));assertEquals(4,count("select status from red_packet_purchase_order"));assertEquals(0,count("select count(*) from user_red_packet"));
    }
    @Test void normalRedPacketReceiptStillIssuesAssetsOnceAndNeverQueuesRefund() {
        packet("RP1",false,false);JSONObject receipt=receipt("RP1",2500);settlement.accept(receipt);settlement.accept(receipt);
        assertEquals(5,count("select count(*) from user_red_packet"));assertEquals(0,count("select count(*) from order_reliability_job where kind='REFUND'"));
    }
    @Test void reconciliationDetectsMissingMismatchAndDuplicatesAndIsIdempotent() throws Exception {
        LocalDate date=LocalDate.now().minusDays(1);
        jdbc.update("insert into order_payment_receipt(order_number,transaction_id,amount,channel_paid_at) values('ORDER1','TX1',10,?),('ORDER2','TX2',5,?)",date.atStartOfDay(),date.atStartOfDay());
        String csv="商户号,公众账号ID,商户订单号,微信订单号,交易状态,货币种类,订单金额\n`merchant,`app,`ORDER1,`WRONG,`SUCCESS,`CNY,`9.99\n`merchant,`app,`ORDER1,`TX1,`SUCCESS,`CNY,`10\n`merchant,`app,`ORDER3,`TX3,`SUCCESS,`CNY,`8\n";
        csv="交易时间,"+csv.replace("\n`merchant","\n`"+date+" 10:00:00,`merchant");
        final String bill=csv;
        var report=reconciliation.upload(date,csv);assertEquals(4,((Number)report.get("difference_count")).intValue());
        reconciliation.upload(date,csv);assertEquals(1,count("select count(*) from payment_reconciliation_run"));assertEquals(4,count("select count(*) from payment_reconciliation_difference"));
        assertThrows(SecurityException.class,()->reconciliation.upload(date,bill.replace("`merchant","`foreign")));assertEquals(1,count("select count(*) from payment_reconciliation_run"));
        assertThrows(IllegalArgumentException.class,()->reconciliation.upload(date.minusDays(1),bill));assertEquals(1,count("select count(*) from payment_reconciliation_run"));
    }
    @Test void expiredUnpaidChannelOrderIsClosed() throws Exception {
        order("ORDER1",true);var unpaid=receipt("ORDER1",1000);unpaid.put("trade_state","NOTPAY");
        when(wechat.queryOrder("ORDER1")).thenReturn(unpaid);when(wechat.closeOrder("ORDER1")).thenReturn(new JSONObject());
        var job=claim("check:ORDER1");task.process(job,(String)job.get("token"));
        verify(wechat).closeOrder("ORDER1");assertEquals("DONE",jdbc.queryForObject("select state from payment_recovery_job",String.class));assertEquals(0,count("select count(*) from order_payment_receipt"));
    }
    @Test void redPacketBudgetFailureRollsBackReceiptAndAssetIssuance() {
        packet("RP1",false,false);jdbc.update("update red_packet_package set reserved_amount=0");
        assertThrows(RuntimeException.class,()->settlement.accept(receipt("RP1",2500)));
        assertEquals(0,count("select status from red_packet_purchase_order"));assertEquals(0,count("select count(*) from order_payment_receipt"));assertEquals(0,count("select count(*) from user_red_packet"));
    }
    @Test void verifiedDuplicateRepairsHistoricalRedPacketReceiptWithoutIssuingAssets() {
        packet("RP1",false,false);jdbc.update("update red_packet_purchase_order set status=1,transaction_id='TXRP1'");
        var paid=receipt("RP1",2500);settlement.accept(paid);settlement.accept(paid);
        assertEquals(1,count("select count(*) from order_payment_receipt"));assertEquals(0,count("select count(*) from user_red_packet"));
    }
    @Test void allBillAllowsPaymentAndRefundRowsForSameOrder() throws Exception {
        LocalDate date=LocalDate.now().minusDays(1);
        jdbc.update("insert into orders(id,number,user_id,status,pay_status,amount) values(1,'ORDER1',7,6,2,10)");
        jdbc.update("insert into order_payment_receipt(order_number,transaction_id,amount,channel_paid_at) values('ORDER1','TX1',10,?)",date.atStartOfDay());
        String csv="交易时间,商户号,公众账号ID,商户订单号,微信订单号,交易状态,货币种类,订单金额,申请退款金额,商户退款单号\n"
                +date+" 10:00:00,merchant,app,ORDER1,TX1,SUCCESS,CNY,10,,\n"
                +date+" 11:00:00,merchant,app,ORDER1,TX1,REFUND,CNY,10,10,REFUND1\n";
        when(wechat.downloadTradeBill(date)).thenReturn(csv);
        var report=reconciliation.download(date);assertEquals(0,((Number)report.get("difference_count")).intValue());assertEquals("CHANNEL_VERIFIED",report.get("source"));
    }
    @Test void expiredPurchaseBudgetReleaseAndChannelCloseAreAtomic() {
        packet("RP1",true,false);
        var factory=new SqlSessionFactoryBean();factory.setDataSource(ds);
        try {
            var configuration=new org.apache.ibatis.session.Configuration();configuration.setMapUnderscoreToCamelCase(true);configuration.addMapper(RedPacketMapper.class);factory.setConfiguration(configuration);
            var mapper=new SqlSessionTemplate(factory.getObject()).getMapper(RedPacketMapper.class);
            var target=new com.sky.task.RedPacketPurchaseTask(mapper);ReflectionTestUtils.setField(target,"jdbc",jdbc);
            var closer=transactional(target);jdbc.update("update red_packet_package set reserved_amount=0");
            assertThrows(IllegalStateException.class,closer::closeExpiredPurchases);assertEquals(0,count("select status from red_packet_purchase_order"));
            jdbc.update("update red_packet_package set reserved_amount=25");closer.closeExpiredPurchases();closer.closeExpiredPurchases();
            assertEquals(2,count("select status from red_packet_purchase_order"));assertEquals(0,count("select reserved_amount from red_packet_package where id=1"));assertEquals(1,count("select count(*) from payment_recovery_job where kind='CLOSE'"));
        } catch(Exception ex) {throw new RuntimeException(ex);}
    }
}
