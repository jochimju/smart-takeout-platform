package com.sky.service.impl;

import com.sky.context.BaseContext;
import com.sky.entity.Orders;
import com.sky.mapper.*;
import com.sky.task.OrderReliabilityTask;
import com.sky.utils.WeChatPayUtil;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Opt in with -Dorder.integration=true; creates and removes ONLY a random test schema. */
@EnabledIfSystemProperty(named="order.integration",matches="true")
class OrderReliabilityIntegrationTest {
    static String schema,baseUrl,user,password;
    static JdbcTemplate jdbc;
    static DriverManagerDataSource dataSource;
    static TransactionTemplate tx;
    static OrderLifecycleService lifecycle;
    static OrderReliabilityStore jobs;
    static WeChatPayUtil gateway;
    static OrderReliabilityTask worker;

    @BeforeAll static void setup() throws Exception {
        ((ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).setLevel(ch.qos.logback.classic.Level.WARN);
        YamlPropertiesFactoryBean yaml=new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application-dev.yml"));
        Properties properties=yaml.getObject();
        String host=properties.getProperty("sky.datasource.host"),port=properties.getProperty("sky.datasource.port");
        user=properties.getProperty("sky.datasource.username"); password=properties.getProperty("sky.datasource.password");
        baseUrl="jdbc:mysql://"+host+":"+port+"/";
        String options="?serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true";
        schema="sky_order_it_"+UUID.randomUUID().toString().replace("-","");
        try(Connection c=DriverManager.getConnection(baseUrl+options,user,password); Statement s=c.createStatement()) {
            s.execute("create database "+schema);
        }
        dataSource=new DriverManagerDataSource(baseUrl+schema+options,user,password);
        jdbc=new JdbcTemplate(dataSource);
        jdbc.execute("create table orders(id bigint primary key,number varchar(64) unique,status int,pay_status int,amount decimal(12,2),"+
            "user_id bigint,order_time datetime,checkout_time datetime,cancel_reason varchar(255),rejection_reason varchar(255),cancel_time datetime,delivery_time datetime) engine=InnoDB");
        jdbc.execute("create table dish(id bigint primary key,stock int) engine=InnoDB");
        jdbc.execute("create table setmeal(id bigint primary key,stock int) engine=InnoDB");
        jdbc.execute("create table order_detail(id bigint primary key,order_id bigint,dish_id bigint,setmeal_id bigint,number int) engine=InnoDB");
        jdbc.execute("create table user_coupon(id bigint primary key,status int,order_id bigint,use_time datetime) engine=InnoDB");
        jdbc.update("insert into orders(id,number,status,pay_status,order_time) values(99,'historical',1,0,?)",LocalDateTime.now().minusMinutes(16));
        try(Connection c=dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(c,new ClassPathResource("db/migration/V2026091201__order_reliability.sql"));
        }
        assertEquals(1,jdbc.queryForObject("select count(*) from order_reliability_job where order_number='historical'",Integer.class));
        SqlSessionFactoryBean factory=new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setTypeAliasesPackage("com.sky.entity,com.sky.dto,com.sky.vo");
        factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath:mapper/*.xml"));
        org.apache.ibatis.session.Configuration config=new org.apache.ibatis.session.Configuration();
        config.setMapUnderscoreToCamelCase(true); factory.setConfiguration(config);
        SqlSessionTemplate sql=new SqlSessionTemplate(factory.getObject());
        if(!sql.getConfiguration().hasMapper(UserCouponMapper.class)) sql.getConfiguration().addMapper(UserCouponMapper.class);
        jobs=new OrderReliabilityStore(jdbc);
        DataSourceTransactionManager manager=new DataSourceTransactionManager(dataSource); tx=new TransactionTemplate(manager);
        OrderLifecycleService target=new OrderLifecycleService(sql.getMapper(OrderMapper.class),sql.getMapper(OrderDetailMapper.class),
            sql.getMapper(DishMapper.class),sql.getMapper(SetmealMapper.class),sql.getMapper(UserCouponMapper.class),
            mock(SeckillReservationService.class),jobs,jdbc);
        ReflectionTestUtils.setField(target,"mockEnabled",true);
        ProxyFactory proxy=new ProxyFactory(target);
        proxy.setProxyTargetClass(true); proxy.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));
        lifecycle=(OrderLifecycleService)proxy.getProxy();
        gateway=mock(WeChatPayUtil.class);
        worker=new OrderReliabilityTask(jobs,mock(RabbitTemplate.class),gateway,jdbc,tx);
    }

    @AfterAll static void cleanup() throws Exception {
        closeRabbit();
        if(schema!=null && schema.matches("sky_order_it_[a-f0-9]{32}"))
            try(Connection c=DriverManager.getConnection(baseUrl+"?useSSL=false&allowPublicKeyRetrieval=true",user,password);
                Statement s=c.createStatement()) { s.execute("drop database "+schema); }
    }
    @BeforeEach void resetData() {
        for(String table:Arrays.asList("order_reliability_job","order_payment_receipt","order_detail","user_coupon","orders","dish","setmeal"))
            jdbc.update("delete from "+table);
        reset(gateway); BaseContext.setCurrentId(1L);
        if(container!=null) { container.stop(); container=null; }
        if(admin!=null) { admin.purgeQueue(com.sky.constant.MqConstant.ORDER_DELAY_QUEUE); admin.purgeQueue(com.sky.constant.MqConstant.ORDER_DEAD_QUEUE); }
    }
    @AfterEach void clearUser() { BaseContext.removeCurrentId(); }
    Orders seed(boolean overdue) {
        LocalDateTime time=LocalDateTime.now().minusMinutes(overdue?16:1).withNano(0);
        Orders order=Orders.builder().id(1L).number("test-order").userId(1L).status(1).payStatus(0).amount(new BigDecimal("12.50"))
            .orderTime(time).expireTime(time.plusMinutes(15)).build();
        jdbc.update("insert into orders(id,number,user_id,status,pay_status,amount,order_time,expire_time) values(1,?,1,1,0,?,?,?)",
            order.getNumber(),order.getAmount(),time,order.getExpireTime());
        jdbc.update("insert into dish values(1,5)");
        jdbc.update("insert into order_detail values(1,1,1,null,2)");
        jdbc.update("insert into user_coupon values(1,1,1,now())");
        tx.executeWithoutResult(s->jobs.scheduleTimeout(order));
        return order;
    }
    int value(String sql) { return jdbc.queryForObject(sql,Integer.class); }
    String state(String id) { return jdbc.queryForObject("select state from order_reliability_job where id=?",String.class,id); }
    void parallel(int n,Runnable action) throws Exception {
        ExecutorService executor=Executors.newFixedThreadPool(n); CountDownLatch start=new CountDownLatch(1);
        List<Future<?>> futures=new ArrayList<>();
        try {
            for(int i=0;i<n;i++) futures.add(executor.submit(()->{
                try { start.await(); BaseContext.setCurrentId(1L); action.run(); }
                catch(InterruptedException e) { throw new RuntimeException(e); } finally { BaseContext.removeCurrentId(); }
            }));
            start.countDown();
            for(Future<?> f:futures) f.get(20,TimeUnit.SECONDS);
        } finally { executor.shutdownNow(); }
    }
    @Test void simultaneousTimeoutsRestoreStockOnce() throws Exception {
        seed(true); parallel(8,()->lifecycle.timeout("test-order"));
        assertEquals(6,value("select status from orders")); assertEquals(7,value("select stock from dish"));
        assertEquals(0,value("select status from user_coupon")); assertEquals("DONE",state("timeout:test-order"));
    }
    @Test void setmealStockIsAlsoRestoredExactlyOnce() throws Exception {
        seed(true);
        jdbc.update("delete from order_detail");
        jdbc.update("delete from dish");
        jdbc.update("insert into setmeal values(1,5)");
        jdbc.update("insert into order_detail values(1,1,null,1,2)");
        parallel(8,()->lifecycle.timeout("test-order"));
        assertEquals(6,value("select status from orders"));
        assertEquals(7,value("select stock from setmeal"));
    }
    @Test void manualAndTimeoutCancellationShareOneStockReturn() throws Exception {
        seed(true); AtomicInteger calls=new AtomicInteger();
        parallel(8,()->{ if(calls.getAndIncrement()%2==0) lifecycle.timeout("test-order"); else lifecycle.cancel(1L,"user","USER"); });
        assertEquals(7,value("select stock from dish"));
    }
    @Test void pendingDeadlineIsCheckedAgain() {
        Orders order=seed(false); lifecycle.timeout(order.getNumber());
        assertEquals(1,value("select status from orders")); assertEquals(5,value("select stock from dish"));
        assertEquals(order.getExpireTime(),jdbc.queryForObject("select next_at from order_reliability_job",Timestamp.class).toLocalDateTime());
    }
    @Test void databaseFailureRollsBackCancellationAndCoupon() {
        seed(true); jdbc.update("delete from dish");
        assertThrows(RuntimeException.class,()->lifecycle.timeout("test-order"));
        assertEquals(1,value("select status from orders")); assertEquals(1,value("select status from user_coupon"));
        assertEquals("READY",state("timeout:test-order"));
        jdbc.update("insert into dish values(1,5)"); lifecycle.timeout("test-order");
        assertEquals(7,value("select stock from dish"));
    }
    @Test void duplicatePaymentsOnlyTransitionOnce() throws Exception {
        seed(false); AtomicInteger accepted=new AtomicInteger();
        parallel(8,()->{if(lifecycle.paid("test-order","transaction-1",new BigDecimal("12.50"),false)) accepted.incrementAndGet();});
        assertEquals(1,accepted.get()); assertEquals(1,value("select count(*) from order_payment_receipt"));
        jdbc.update("update orders set expire_time=?",LocalDateTime.now().minusSeconds(1));
        lifecycle.timeout("test-order");
        assertEquals(2,value("select status from orders")); assertEquals(5,value("select stock from dish"));
    }
    @Test void latePaymentRacingTimeoutNeverRevivesOrder() throws Exception {
        seed(true); AtomicInteger calls=new AtomicInteger();
        parallel(8,()->{if(calls.getAndIncrement()%2==0) lifecycle.timeout("test-order");
            else lifecycle.paid("test-order","late-transaction",new BigDecimal("12.50"),false);});
        assertEquals(6,value("select status from orders")); assertEquals(7,value("select stock from dish"));
        assertEquals(1,value("select count(*) from order_reliability_job where kind='REFUND'"));
    }
    @Test void wrongAmountCannotChangeOrder() {
        seed(false);
        assertThrows(RuntimeException.class,()->lifecycle.paid("test-order","wrong",BigDecimal.ONE,false));
        assertEquals(1,value("select status from orders")); assertEquals(0,value("select count(*) from order_payment_receipt"));
    }
    @Test void outboxInsertRollsBackWithTransaction() {
        Orders order=seed(false);
        jdbc.update("delete from order_reliability_job");
        assertThrows(RuntimeException.class,()->tx.executeWithoutResult(s->{jobs.scheduleTimeout(order); throw new IllegalStateException("commit failed");}));
        assertEquals(0,value("select count(*) from order_reliability_job"));
    }
    @Test void failedEventsHaveBoundedRetriesAndCanBeReplayed() {
        seed(true);
        for(int i=0;i<12;i++) jobs.failed("timeout:test-order",null,new IllegalStateException("db unavailable"));
        assertEquals("FAILED",state("timeout:test-order")); assertEquals(10,value("select attempts from order_reliability_job"));
        assertEquals(1,jobs.failures().size()); assertEquals(1,jobs.replay("timeout:test-order"));
        lifecycle.timeout("test-order"); assertEquals("DONE",state("timeout:test-order"));
    }
    @Test void expiredLeaseRecoversAndStalePublisherCannotUndoCompletion() {
        seed(true); String first=jobs.claim("timeout:test-order"); assertNotNull(first);
        assertNull(jobs.claim("timeout:test-order"));
        jdbc.update("update order_reliability_job set lease_until=?",LocalDateTime.now().minusSeconds(1));
        String second=jobs.claim("timeout:test-order"); assertNotNull(second); assertNotEquals(first,second);
        jobs.waitUntil("timeout:test-order",first,LocalDateTime.now().plusHours(1));
        assertEquals("RUNNING",state("timeout:test-order"));
        lifecycle.timeout("test-order");
        jobs.waitUntil("timeout:test-order",second,LocalDateTime.now().plusHours(1));
        assertEquals("DONE",state("timeout:test-order"));
    }
    @Test void mockRefundCompletesWithoutNetwork() throws Exception {
        seed(false); lifecycle.paid("test-order","mock:test-order",null,true); lifecycle.cancel(1L,"user","USER");
        Map<String,Object> job=jdbc.queryForMap("select * from order_reliability_job where kind='REFUND'");
        worker.refund(job,jobs.claim((String)job.get("id")));
        assertEquals(2,value("select pay_status from orders")); assertEquals("DONE",state("refund:test-order"));
        verifyNoInteractions(gateway);
    }
    @Test void realRefundWaitsForConfirmedSuccessAndUsesActualAmount() throws Exception {
        seed(true); lifecycle.paid("test-order","transaction-1",new BigDecimal("12.50"),false);
        String refundNo=UUID.nameUUIDFromBytes("refund:test-order".getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString().replace("-","");
        when(gateway.queryRefund(refundNo)).thenReturn("{\"code\":\"RESOURCE_NOT_EXISTS\"}");
        when(gateway.refund("test-order",refundNo,new BigDecimal("12.50"),new BigDecimal("12.50")))
            .thenReturn("{\"status\":\"PROCESSING\"}");
        Map<String,Object> job=jdbc.queryForMap("select * from order_reliability_job where kind='REFUND'");
        worker.refund(job,jobs.claim("refund:test-order"));
        assertEquals(1,value("select pay_status from orders")); assertEquals("READY",state("refund:test-order"));
        when(gateway.queryRefund(refundNo)).thenReturn("{\"status\":\"SUCCESS\",\"out_trade_no\":\"test-order\",\"out_refund_no\":\""+refundNo+"\",\"amount\":{\"refund\":1250}}");
        worker.refund(job,null);
        assertEquals(2,value("select pay_status from orders")); assertEquals("DONE",state("refund:test-order"));
        verify(gateway,times(1)).refund(anyString(),anyString(),any(),any());
    }

    static String virtualHost;
    static Properties rabbitProperties;
    static org.springframework.amqp.rabbit.connection.CachingConnectionFactory rabbitConnection;
    static org.springframework.amqp.rabbit.core.RabbitAdmin admin;
    static org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer container;
    static OrderReliabilityTask rabbitWorker;
    static com.sky.config.RabbitMqConfiguration rabbitConfig;
    static java.util.concurrent.atomic.AtomicReference<String> death=new java.util.concurrent.atomic.AtomicReference<>();
    static void manage(String method,String path,String body) throws Exception {
        java.net.HttpURLConnection connection=(java.net.HttpURLConnection)new java.net.URL("http://"+
            rabbitProperties.getProperty("sky.rabbitmq.host")+":"+System.getProperty("order.rabbit.managementPort","15672")+"/api/"+path).openConnection();
        connection.setRequestMethod(method); connection.setConnectTimeout(5000); connection.setReadTimeout(5000);
        String auth=rabbitProperties.getProperty("sky.rabbitmq.username")+":"+rabbitProperties.getProperty("sky.rabbitmq.password");
        connection.setRequestProperty("Authorization","Basic "+Base64.getEncoder().encodeToString(auth.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        if(body!=null) {
            connection.setDoOutput(true); connection.setRequestProperty("Content-Type","application/json");
            try(java.io.OutputStream out=connection.getOutputStream()) { out.write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        }
        int result=connection.getResponseCode(); connection.disconnect();
        if(result<200 || result>=300) throw new IllegalStateException("isolated RabbitMQ management request failed: "+result);
    }
    static void initRabbit() throws Exception {
        if(admin!=null) return;
        YamlPropertiesFactoryBean yaml=new YamlPropertiesFactoryBean(); yaml.setResources(new ClassPathResource("application-dev.yml"));
        rabbitProperties=yaml.getObject();
        virtualHost="order_it_"+UUID.randomUUID().toString().replace("-","");
        manage("PUT","vhosts/"+virtualHost,"{}");
        manage("PUT","permissions/"+virtualHost+"/"+java.net.URLEncoder.encode(rabbitProperties.getProperty("sky.rabbitmq.username"),"UTF-8"),
            "{\"configure\":\".*\",\"write\":\".*\",\"read\":\".*\"}");
        rabbitConnection=new org.springframework.amqp.rabbit.connection.CachingConnectionFactory(rabbitProperties.getProperty("sky.rabbitmq.host"),
            Integer.parseInt(rabbitProperties.getProperty("sky.rabbitmq.port")));
        rabbitConnection.setUsername(rabbitProperties.getProperty("sky.rabbitmq.username"));
        rabbitConnection.setPassword(rabbitProperties.getProperty("sky.rabbitmq.password")); rabbitConnection.setVirtualHost(virtualHost);
        rabbitConnection.setPublisherConfirmType(org.springframework.amqp.rabbit.connection.CachingConnectionFactory.ConfirmType.CORRELATED);
        rabbitConnection.setPublisherReturns(true);
        admin=new org.springframework.amqp.rabbit.core.RabbitAdmin(rabbitConnection); rabbitConfig=new com.sky.config.RabbitMqConfiguration();
        admin.declareExchange(rabbitConfig.orderDelayExchange());
        admin.declareQueue(rabbitConfig.orderDelayQueue()); admin.declareQueue(rabbitConfig.orderDeadQueue());
        admin.declareBinding(rabbitConfig.orderDelayBinding()); admin.declareBinding(rabbitConfig.orderDeadBinding());
        rabbitWorker=new OrderReliabilityTask(jobs,rabbitConfig.rabbitTemplate(rabbitConnection,rabbitConfig.messageConverter()),gateway,jdbc,tx);
    }
    static void closeRabbit() throws Exception {
        if(container!=null) container.stop();
        if(rabbitConnection!=null) rabbitConnection.destroy();
        if(virtualHost!=null && virtualHost.matches("order_it_[a-f0-9]{32}")) manage("DELETE","vhosts/"+virtualHost,null);
    }
    CountDownLatch listen(boolean failFirst) {
        CountDownLatch consumed=new CountDownLatch(1); AtomicInteger received=new AtomicInteger(); death.set(null);
        com.sky.service.OrderService orderService=mock(com.sky.service.OrderService.class);
        doAnswer(invocation->{
            if(failFirst && received.getAndIncrement()==0) throw new IllegalStateException("injected transient failure");
            lifecycle.timeout(invocation.getArgument(0)); return null;
        }).when(orderService).cancelTimeoutOrder(anyString());
        com.sky.listener.OrderMessageListener listener=new com.sky.listener.OrderMessageListener(orderService,
            mock(com.sky.service.SetmealService.class),new TimeoutFailureService(jobs));
        container=new org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer(rabbitConnection);
        container.setQueueNames(com.sky.constant.MqConstant.ORDER_DEAD_QUEUE);
        container.setAcknowledgeMode(org.springframework.amqp.core.AcknowledgeMode.MANUAL);
        container.setMessageListener((org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener)(message,channel)->{
            death.set(String.valueOf(message.getMessageProperties().getHeaders().get("x-death")));
            listener.handleTimeoutOrder((String)rabbitConfig.messageConverter().fromMessage(message),channel,message);
            consumed.countDown();
        });
        container.start(); return consumed;
    }
    void setDeadline(LocalDateTime due) {
        jdbc.update("update orders set expire_time=?",due);
        jdbc.update("update order_reliability_job set due_at=?,next_at=now()",due);
    }
    @Test @EnabledIfSystemProperty(named="order.rabbit",matches="true")
    void rabbitDeadLetterActuallyCancelsAndRestoresStock() throws Exception {
        seed(false); initRabbit(); setDeadline(LocalDateTime.now().plusSeconds(3).withNano(0));
        CountDownLatch consumed=listen(false); rabbitWorker.dispatch();
        assertTrue(consumed.await(12,TimeUnit.SECONDS));
        assertEquals(6,value("select status from orders")); assertEquals(7,value("select stock from dish"));
        assertTrue(death.get().contains("expired")); assertEquals("DONE",state("timeout:test-order"));
    }
    @Test @EnabledIfSystemProperty(named="order.rabbit",matches="true")
    void returnedMessageRemainsRecoverableAndCanBeRepublished() throws Exception {
        seed(true); initRabbit();
        admin.removeBinding(rabbitConfig.orderDeadBinding());
        try { rabbitWorker.dispatch(); } finally { admin.declareBinding(rabbitConfig.orderDeadBinding()); }
        assertEquals("READY",state("timeout:test-order")); assertEquals(1,value("select attempts from order_reliability_job"));
        jdbc.update("update order_reliability_job set next_at=now()");
        CountDownLatch consumed=listen(false); rabbitWorker.dispatch();
        assertTrue(consumed.await(10,TimeUnit.SECONDS)); assertEquals(6,value("select status from orders"));
    }
    @Test @EnabledIfSystemProperty(named="order.rabbit",matches="true")
    void consumerFailureIsRecoveredFromDurableJob() throws Exception {
        seed(true); initRabbit(); CountDownLatch first=listen(true); rabbitWorker.dispatch();
        assertTrue(first.await(10,TimeUnit.SECONDS));
        assertEquals(1,value("select status from orders")); assertEquals("READY",state("timeout:test-order"));
        container.stop(); CountDownLatch second=listen(false);
        jdbc.update("update order_reliability_job set next_at=now()");
        rabbitWorker.dispatch(); assertTrue(second.await(10,TimeUnit.SECONDS));
        assertEquals(7,value("select stock from dish")); assertEquals("DONE",state("timeout:test-order"));
    }
    @Test @EnabledIfSystemProperty(named="order.ttl15",matches="true")
    void fullFifteenMinuteTtlWithoutWatchdog() throws Exception {
        seed(false); initRabbit();
        LocalDateTime due=LocalDateTime.now().plusMinutes(15).withNano(0);
        setDeadline(due); CountDownLatch consumed=listen(false);
        long start=System.nanoTime();
        System.out.println("FULL_TTL_START "+LocalDateTime.now()+" due="+due);
        rabbitWorker.dispatch(); // No further dispatch: success must come from the broker TTL + DLX.
        for(int minute=0;minute<32;minute++) {
            if(consumed.await(30,TimeUnit.SECONDS)) break;
            System.out.println("FULL_TTL_WAIT seconds="+TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-start));
            assertEquals(1,value("select status from orders"));
        }
        long elapsed=TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-start);
        assertEquals(0,consumed.getCount()); assertTrue(elapsed>=898 && elapsed<960);
        assertEquals(6,value("select status from orders")); assertEquals(7,value("select stock from dish"));
        assertTrue(death.get().contains("expired"));
        System.out.println("FULL_TTL_PASS elapsedSeconds="+elapsed);
    }
}
