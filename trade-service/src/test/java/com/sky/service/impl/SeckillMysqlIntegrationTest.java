package com.sky.service.impl;

import com.sky.client.AccountClient;
import com.sky.contract.account.AccountAddressView;
import com.sky.contract.catalog.ProductQuote;
import com.sky.dto.*;
import com.sky.entity.*;
import com.sky.exception.OrderBusinessException;
import com.sky.mapper.*;
import com.sky.service.mq.OrderMessagePublisher;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Uses real InnoDB transactions in a fresh random schema, never modifies application tables. */
@EnabledIfSystemProperty(named="seckill.mysql.integration",matches="true")
class SeckillMysqlIntegrationTest {
    static String base,user,password,schema;
    static DriverManagerDataSource ds;
    static JdbcTemplate jdbc;
    static DataSourceTransactionManager tm;
    static SeckillServiceImpl service;
    static SeckillAsyncStore store;
    static OrderMessagePublisher publisher;
    static SeckillActivityMapper activities;
    static com.sky.mapper.MqFailMessageMapper outboxMapper;
    @BeforeAll static void setup() throws Exception {
        var yaml=new YamlPropertiesFactoryBean();yaml.setResources(new ClassPathResource("application-dev.yml"));var p=yaml.getObject();
        user=p.getProperty("sky.datasource.username");password=p.getProperty("sky.datasource.password");
        base="jdbc:mysql://"+p.getProperty("sky.datasource.host")+":"+p.getProperty("sky.datasource.port")+"/";
        String options="?serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true";
        schema="sky_async_test_"+UUID.randomUUID().toString().replace("-","");
        try(var c=DriverManager.getConnection(base+options,user,password);var stmt=c.createStatement()){stmt.execute("create database "+schema);}
        ds=new DriverManagerDataSource(base+schema+options,user,password);jdbc=new JdbcTemplate(ds);tm=new DataSourceTransactionManager(ds);
        jdbc.execute("create table seckill_activity(id bigint primary key,setmeal_id bigint,stock int,remaining_stock int,stock_version bigint,status int,begin_time datetime,end_time datetime,seckill_price decimal(10,2)) engine=InnoDB");
        try(var c=ds.getConnection()){ScriptUtils.executeSqlScript(c,new ClassPathResource("db/migration/V2026100901__seckill_async.sql"));}
        jdbc.execute("create table orders(id bigint primary key auto_increment,number varchar(64) unique,user_id bigint,amount decimal(10,2),"+
            "status int,address_book_id bigint,order_time datetime,expire_time datetime,checkout_time datetime,pay_method int,pay_status int,"+
            "coupon_id bigint,discount_amount decimal(10,2),user_red_packet_id bigint,remark varchar(255),phone varchar(32),address varchar(255),"+
            "consignee varchar(64),estimated_delivery_time datetime,delivery_status int,pack_amount int,tableware_number int,tableware_status int,canteen_id bigint,canteen_name varchar(64)) engine=InnoDB");
        jdbc.execute("create table order_detail(name varchar(64),order_id bigint,dish_id bigint,setmeal_id bigint,dish_flavor varchar(255),number int,amount decimal(10,2),image varchar(255)) engine=InnoDB");
        jdbc.execute("create table seckill_order_guard(user_id bigint,activity_id bigint,setmeal_id bigint,order_number varchar(64) unique,create_time datetime,unique key uk_user_activity(user_id,activity_id)) engine=InnoDB");
        jdbc.execute("create table seckill_reservation(order_number varchar(64) primary key,activity_id bigint,setmeal_id bigint,user_id bigint,release_status int,request_id varchar(64),request_hash varchar(64)) engine=InnoDB");
        jdbc.execute("create table mq_fail_message(id bigint primary key auto_increment,exchange_name varchar(128),routing_key varchar(128),message_body text,fail_reason varchar(500),status int,retry_count int,create_time datetime,update_time datetime,lease_until datetime) engine=InnoDB");
        try(var c=ds.getConnection()){ScriptUtils.executeSqlScript(c,new ClassPathResource("db/migration/V2026101301__seckill_outbox_schedule.sql"));}
        try(var c=ds.getConnection()){ScriptUtils.executeSqlScript(c,new ClassPathResource("db/migration/V2026101302__seckill_publish_batch.sql"));}
        try(var c=ds.getConnection()){ScriptUtils.executeSqlScript(c,new ClassPathResource("db/migration/V2026101603__timeout_delivery_deadline.sql"));}
        try(var c=ds.getConnection()){ScriptUtils.executeSqlScript(c,new ClassPathResource("db/migration/V2026101403__seckill_stock_buckets.sql"));}
        jdbc.execute("create table order_reliability_job(id varchar(64) primary key,order_number varchar(64),kind varchar(16),due_at datetime,next_at datetime,state varchar(16) not null default 'READY',lease_token varchar(64),lease_until datetime,updated_at datetime default current_timestamp) engine=InnoDB");
        var factory=new SqlSessionFactoryBean();factory.setDataSource(ds);
        factory.setTypeAliasesPackage("com.sky.entity");
        factory.setMapperLocations(new ClassPathResource("mapper/OrderMapper.xml"),new ClassPathResource("mapper/OrderDetailMapper.xml"));
        var configuration=new org.apache.ibatis.session.Configuration();configuration.setMapUnderscoreToCamelCase(true);factory.setConfiguration(configuration);
        var sql=new SqlSessionTemplate(factory.getObject());
        for(var mapper:List.of(SeckillActivityMapper.class,SeckillOrderGuardMapper.class,SeckillReservationMapper.class))sql.getConfiguration().addMapper(mapper);
        sql.getConfiguration().addMapper(com.sky.mapper.MqFailMessageMapper.class);outboxMapper=sql.getMapper(com.sky.mapper.MqFailMessageMapper.class);
        activities=sql.getMapper(SeckillActivityMapper.class);publisher=mock(OrderMessagePublisher.class);
        store=proxy(new SeckillAsyncStore(jdbc,activities,publisher));ReflectionTestUtils.setField(store,"processingSeconds",120L);
        var target=new SeckillServiceImpl();
        ReflectionTestUtils.setField(target,"asyncStore",store);ReflectionTestUtils.setField(target,"seckillActivityMapper",activities);
        ReflectionTestUtils.setField(target,"seckillOrderGuardMapper",sql.getMapper(SeckillOrderGuardMapper.class));
        ReflectionTestUtils.setField(target,"reservationMapper",sql.getMapper(SeckillReservationMapper.class));
        ReflectionTestUtils.setField(target,"orderMessagePublisher",publisher);ReflectionTestUtils.setField(target,"redPacketMapper",mock(RedPacketMapper.class));
        ReflectionTestUtils.setField(target,"orderMapper",sql.getMapper(OrderMapper.class));
        ReflectionTestUtils.setField(target,"orderDetailMapper",sql.getMapper(OrderDetailMapper.class));
        var catalog=mock(CatalogQuoteService.class);var quote=ProductQuote.builder().productId(48L).status(1).name("套餐").build();
        when(catalog.quoteSetmeal(48L)).thenReturn(quote);
        when(catalog.quotes(anyList())).thenReturn(Map.of(ProductQuote.SETMEAL+":48",quote));
        ReflectionTestUtils.setField(target,"catalogQuoteService",catalog);
        var account=mock(AccountClient.class);when(account.address(anyLong(),anyLong())).thenAnswer(call->new AccountAddressView(call.getArgument(1),call.getArgument(0),"用户","13000000000","地址"));ReflectionTestUtils.setField(target,"accountClient",account);
        when(account.addresses(anyList())).thenAnswer(call->{java.util.List<com.sky.contract.account.AccountAddressLookup> lookups=call.getArgument(0);
            return lookups.stream().map(row->new AccountAddressView(row.addressId(),row.userId(),"用户","13000000000","地址")).toList();});
        service=proxy(target);
    }
    @SuppressWarnings("unchecked") static <T>T proxy(T target){var p=new ProxyFactory(target);p.setProxyTargetClass(true);p.addAdvice(new TransactionInterceptor(tm,new AnnotationTransactionAttributeSource()));return (T)p.getProxy();}
    @AfterAll static void cleanup() throws Exception {
        if(schema!=null && schema.matches("sky_async_test_[a-f0-9]{32}"))try(var c=DriverManager.getConnection(base+"?useSSL=false&allowPublicKeyRetrieval=true",user,password);var stmt=c.createStatement()){stmt.execute("drop database "+schema);}
    }
    @BeforeEach void resetData(){reset(publisher);for(String table:List.of("order_reliability_job","mq_fail_message","seckill_cache_event","seckill_submit_request","order_detail","orders","seckill_reservation","seckill_order_guard","seckill_stock_bucket","seckill_activity"))jdbc.update("delete from "+table);
        // Expired activity proves valid prior reservations can be fulfilled after the sale ends.
        jdbc.update("insert into seckill_activity values(1,48,20,20,0,0,date_sub(now(),interval 2 hour),date_sub(now(),interval 1 hour),8.90,1,1)");}
    SeckillOrderMessageDTO command(long user,String number){var r=new SeckillOrderSubmitDTO();r.setActivityId(1L);r.setSetmealId(48L);r.setAddressBookId(user);r.setRequestId("request_"+number);r.setUseRedPacket(false);r.setAmount(new BigDecimal("15.90"));
        return SeckillOrderMessageDTO.builder().activityId(1L).setmealId(48L).userId(user).request(r).requestHash("hash").orderNumber(number).price(new BigDecimal("8.90")).acceptedAt(System.currentTimeMillis()).build();}
    int count(String sql){return jdbc.queryForObject(sql,Integer.class);}
    @Test void bucketBatchUsesOneLedgerAndGlobalGuardRollsBackCrossBucketDuplicate() {
        jdbc.update("update seckill_activity set bucket_count=2 where id=1");activities.insertBucket(1L,0,10);activities.insertBucket(1L,1,10);
        var commands=new ArrayList<SeckillOrderMessageDTO>();
        for(int i=0;i<8;i++){var c=command(i+1,"SKbatchbucket"+i);c.setBucketCount(2);c.setBucketId(0);commands.add(c);}
        store.acceptBatch(commands);service.createPreparedOrders(commands.stream().map(service::prepareSeckillOrder).toList());
        assertEquals(2,count("select remaining_stock from seckill_stock_bucket where bucket_id=0"));
        assertEquals(12,activities.getById(1L).getRemainingStock());assertEquals(8,count("select count(*) from seckill_reservation where bucket_id=0"));
        var duplicate=command(1,"SKcrossduplicate");duplicate.setBucketCount(2);duplicate.setBucketId(1);store.accept(duplicate);
        assertThrows(OrderBusinessException.class,()->service.createPreparedOrders(List.of(service.prepareSeckillOrder(duplicate))));
        assertEquals(10,count("select remaining_stock from seckill_stock_bucket where bucket_id=1"));assertEquals(8,count("select count(*) from orders"));
        var insufficient=new ArrayList<SeckillOrderMessageDTO>();
        for(int i=0;i<3;i++){var c=command(i+20,"SKinsufficient"+i);c.setBucketCount(2);c.setBucketId(0);insufficient.add(c);}
        store.acceptBatch(insufficient);
        assertThrows(OrderBusinessException.class,()->service.createPreparedOrders(insufficient.stream().map(service::prepareSeckillOrder).toList()));
        assertEquals(2,count("select remaining_stock from seckill_stock_bucket where bucket_id=0"));assertEquals(8,count("select count(*) from orders"));
    }
    @Test void bucketWritersDoNotWaitForAnUnrelatedBucketLock() throws Exception {
        jdbc.update("update seckill_activity set bucket_count=8 where id=1");
        for(int i=0;i<8;i++)activities.insertBucket(1L,i,20);
        var first=command(1,"SKbucket0");first.setBucketCount(8);first.setBucketId(0);store.accept(first);
        var second=command(2,"SKbucket1");second.setBucketCount(8);second.setBucketId(1);store.accept(second);
        var held=new CountDownLatch(1);var release=new CountDownLatch(1);var executor=Executors.newFixedThreadPool(2);
        try {
            var blocked=executor.submit(()->new org.springframework.transaction.support.TransactionTemplate(tm).execute(status->{
                activities.lockBucket(1L,0);held.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){throw new RuntimeException(e);}return null;}));
            assertTrue(held.await(3,TimeUnit.SECONDS));
            executor.submit(()->service.createPreparedOrders(List.of(service.prepareSeckillOrder(second)))).get(3,TimeUnit.SECONDS);
            assertEquals(19,jdbc.queryForObject("select remaining_stock from seckill_stock_bucket where activity_id=1 and bucket_id=1",Integer.class));
            assertEquals(20,jdbc.queryForObject("select remaining_stock from seckill_stock_bucket where activity_id=1 and bucket_id=0",Integer.class));
            release.countDown();blocked.get(3,TimeUnit.SECONDS);assertEquals(1,count("select count(*) from orders"));
        } finally {release.countDown();executor.shutdownNow();}
    }
    @Test void bucketCancellationRestoresItsOwnLedgerOnlyOnce() throws Exception {
        jdbc.update("update seckill_activity set bucket_count=2 where id=1");activities.insertBucket(1L,0,10);activities.insertBucket(1L,1,10);
        var command=command(1,"SKcancelbucket");command.setBucketCount(2);command.setBucketId(1);store.accept(command);
        service.createPreparedOrders(List.of(service.prepareSeckillOrder(command)));
        var factory=new SqlSessionFactoryBean();factory.setDataSource(ds);
        var config=new org.apache.ibatis.session.Configuration();config.setMapUnderscoreToCamelCase(true);factory.setConfiguration(config);
        var sql=new SqlSessionTemplate(factory.getObject());sql.getConfiguration().addMapper(SeckillReservationMapper.class);sql.getConfiguration().addMapper(SeckillOrderGuardMapper.class);
        var cancel=new SeckillReservationService();ReflectionTestUtils.setField(cancel,"mapper",sql.getMapper(SeckillReservationMapper.class));
        ReflectionTestUtils.setField(cancel,"inventoryReturn",mock(SeckillInventoryReturn.class));
        ReflectionTestUtils.setField(cancel,"activities",activities);ReflectionTestUtils.setField(cancel,"guards",sql.getMapper(SeckillOrderGuardMapper.class));ReflectionTestUtils.setField(cancel,"asyncStore",store);
        var order=Orders.builder().number(command.getOrderNumber()).build();
        new org.springframework.transaction.support.TransactionTemplate(tm).execute(status->{cancel.cancel(order);cancel.cancel(order);return null;});
        assertEquals(20,count("select sum(remaining_stock) from seckill_stock_bucket"));
        assertEquals(1,count("select bucket_id from seckill_cache_event where order_number='SKcancelbucket'"));assertEquals(0,count("select count(*) from seckill_order_guard"));
    }
    @Test void realBatchMapperReturnsTheActualKeyForEveryOrderAndDetail() {
        var target=org.springframework.test.util.AopTestUtils.getTargetObject(service);
        var mapper=(OrderMapper)ReflectionTestUtils.getField(target,"orderMapper");
        var details=(OrderDetailMapper)ReflectionTestUtils.getField(target,"orderDetailMapper");
        var orders=new ArrayList<Orders>();
        for(int i=0;i<16;i++)orders.add(Orders.builder().number("SKkeys"+(15-i)).userId(100L+i)
                .amount(new BigDecimal("20.00").add(BigDecimal.valueOf(i))).build());
        new org.springframework.transaction.support.TransactionTemplate(tm).executeWithoutResult(tx->{
            assertEquals(16,mapper.insertSeckillBatch(orders));
            assertEquals(16,orders.stream().map(Orders::getId).filter(Objects::nonNull).distinct().count());
            details.insertBatch(orders.stream().map(order->OrderDetail.builder().orderId(order.getId())
                    .number(1).amount(order.getAmount().subtract(new BigDecimal("7.00"))).build()).toList());
        });
        for(var order:orders) {
            var stored=mapper.getByNumber(order.getNumber());
            assertEquals(order.getId(),stored.getId());assertEquals(order.getUserId(),stored.getUserId());
            assertEquals(order.getAmount(),stored.getAmount());
        }
        assertEquals(16,count("select count(*) from orders o join order_detail d on d.order_id=o.id where d.amount=o.amount-7"));
    }
    @Test void orderBatchToggleKeepsSingleInsertFallbackAndDetailLinks() {
        var target=org.springframework.test.util.AopTestUtils.getTargetObject(service);
        ReflectionTestUtils.setField(target,"orderBatchEnabled",false);
        try {
            var commands=new ArrayList<SeckillOrderMessageDTO>();
            for(int i=0;i<8;i++){var command=command(i+1,"SKfallback"+i);store.accept(command);commands.add(command);}
            assertTrue(new SeckillBatchCoordinator(service).fulfill(commands).isEmpty());
            assertEquals(8,count("select count(*) from orders o join order_detail d on d.order_id=o.id"));
            assertEquals(12,count("select remaining_stock from seckill_activity"));
        } finally {ReflectionTestUtils.setField(target,"orderBatchEnabled",true);}
    }
    @Test void earlyTimeoutCreatesMissingRecoveryAndPreservesTerminalJobs() {
        var jobs=new OrderReliabilityStore(jdbc);var due=java.time.LocalDateTime.now().plusMinutes(15).withNano(0);
        jobs.early("SKearly",due);jobs.early("SKearly",due);
        assertEquals(1,count("select count(*) from order_reliability_job where id='timeout:SKearly' and state='READY'"));
        assertEquals(due,jdbc.queryForObject("select next_at from order_reliability_job where id='timeout:SKearly'",java.time.LocalDateTime.class));
        for(String state:List.of("DONE","FAILED")) {
            jdbc.update("update order_reliability_job set state=? where id='timeout:SKearly'",state);jobs.early("SKearly",due.plusMinutes(1));
            assertEquals(state,jdbc.queryForObject("select state from order_reliability_job where id='timeout:SKearly'",String.class));
            assertEquals(due,jdbc.queryForObject("select next_at from order_reliability_job where id='timeout:SKearly'",java.time.LocalDateTime.class));
        }
    }
    void parallel(int n,java.util.function.IntConsumer action)throws Exception{var pool=Executors.newFixedThreadPool(12);try{List<Future<?>> jobs=new ArrayList<>();for(int i=0;i<n;i++){int index=i;jobs.add(pool.submit(()->action.accept(index)));}for(var job:jobs)job.get(30,TimeUnit.SECONDS);}finally{pool.shutdownNow();}}
    @Test void mysqlCapsOrdersEvenIfRedisAdmitsMoreThanStock()throws Exception{
        List<SeckillOrderMessageDTO> commands=new ArrayList<>();for(int i=0;i<50;i++){var c=command(i+1,"SK"+i);commands.add(c);store.accept(c);}
        parallel(50,i->{var c=commands.get(i);try{service.createSeckillOrder(c);}catch(com.sky.exception.OrderBusinessException failure){store.failed(c.getOrderNumber(),failure,true);}});
        assertEquals(20,count("select count(*) from orders"));assertEquals(0,count("select remaining_stock from seckill_activity"));assertEquals(30,count("select count(*) from seckill_submit_request where state='FAILED'"));
    }
    @Test void duplicateConsumptionCreatesAndDebitsOnce()throws Exception{
        var c=command(1,"SKsame");store.accept(c);parallel(30,i->service.createSeckillOrder(c));
        assertEquals(1,count("select count(*) from orders"));assertEquals(19,count("select remaining_stock from seckill_activity"));
        verify(publisher,times(1)).seckillDeferredBatch(anyList());
    }
    @Test void smallBatchDuplicateDeliveriesCommitOnlyOneOrderPerAcceptance()throws Exception {
        var commands=new ArrayList<SeckillOrderMessageDTO>();for(int i=0;i<8;i++){var row=command(i+1,"SKsmall"+i);store.accept(row);commands.add(row);}
        var prepared=commands.stream().map(service::prepareSeckillOrder).toList();
        parallel(4,i->service.createPreparedOrders(prepared));
        assertEquals(8,count("select count(*) from orders"));assertEquals(12,count("select remaining_stock from seckill_activity"));
        assertEquals(8,count("select count(*) from seckill_order_guard"));
    }
    @Test void preparationBatchesRemoteCallsAndRejectsForeignAddress() {
        var commands=new ArrayList<SeckillOrderMessageDTO>();
        for(int i=0;i<8;i++){var row=command(i+1,"SKprepare"+i);store.accept(row);commands.add(row);}
        var target=org.springframework.test.util.AopTestUtils.getTargetObject(service);
        CatalogQuoteService catalog=(CatalogQuoteService)ReflectionTestUtils.getField(target,"catalogQuoteService");
        AccountClient account=(AccountClient)ReflectionTestUtils.getField(target,"accountClient");
        clearInvocations(catalog,account);
        var prepared=service.prepareSeckillOrders(commands);
        assertEquals(8,prepared.ready().size());assertTrue(prepared.failures().isEmpty());
        verify(catalog,times(1)).quotes(anyList());verify(account,times(1)).addresses(anyList());
        verify(account,never()).address(anyLong(),anyLong());
        try {
            when(account.addresses(anyList())).thenReturn(List.of(new AccountAddressView(1L,999L,"wrong","13000000000","foreign")));
            var rejected=service.prepareSeckillOrders(List.of(commands.get(0)));
            assertTrue(rejected.ready().isEmpty());assertEquals(Set.of("SKprepare0"),rejected.failures().keySet());
        } finally {
            when(account.addresses(anyList())).thenAnswer(call->{java.util.List<com.sky.contract.account.AccountAddressLookup> lookups=call.getArgument(0);
                return lookups.stream().map(row->new AccountAddressView(row.addressId(),row.userId(),"用户","13000000000","地址")).toList();});
        }
    }
    @Test void batchTimeoutOutboxRollsBackAndPersistsOneDueRowPerOrder() {
        var realPublisher=new OrderMessagePublisher(mock(org.springframework.amqp.rabbit.core.RabbitTemplate.class),outboxMapper);
        var first=new Orders();first.setNumber("SKout1");first.setExpireTime(java.time.LocalDateTime.now().plusMinutes(15));
        var second=new Orders();second.setNumber("SKout2");second.setExpireTime(java.time.LocalDateTime.now().plusMinutes(15));
        var tx=new org.springframework.transaction.support.TransactionTemplate(tm);
        assertThrows(IllegalStateException.class,()->tx.execute(status->{
            realPublisher.timeoutDeferredBatch(List.of(first,second));throw new IllegalStateException("force rollback");}));
        assertEquals(0,count("select count(*) from mq_fail_message"));
        tx.executeWithoutResult(status->realPublisher.timeoutDeferredBatch(List.of(first,second)));
        assertEquals(2,count("select count(*) from mq_fail_message where routing_key='seckill.timeout'"));
    }
    @Test void batchKeepsPerOrderRedPacketOwnershipAndPayableAmount() {
        var target=org.springframework.test.util.AopTestUtils.getTargetObject(service);
        var red=(RedPacketMapper)ReflectionTestUtils.getField(target,"redPacketMapper");
        var packet=new UserRedPacket();packet.setId(777L);packet.setUserId(1L);
        packet.setStatus(UserRedPacket.UNUSED);packet.setAmount(new BigDecimal("5.00"));
        packet.setExpireTime(java.time.LocalDateTime.now().plusDays(1));
        when(red.byIdAndUser(777L,1L)).thenReturn(packet);when(red.reserve(eq(777L),eq(1L),anyLong())).thenReturn(1);
        try {
            var withPacket=command(1,"SKpacket");withPacket.getRequest().setUseRedPacket(true);
            withPacket.getRequest().setUserRedPacketId(777L);withPacket.getRequest().setAmount(new BigDecimal("10.90"));
            var regular=command(2,"SKregular");store.accept(withPacket);store.accept(regular);
            assertTrue(new SeckillBatchCoordinator(service).fulfill(List.of(withPacket,regular)).isEmpty());
            assertEquals(2,count("select count(*) from orders"));assertEquals(18,count("select remaining_stock from seckill_activity"));
            assertEquals(new BigDecimal("10.90"),jdbc.queryForObject("select amount from orders where number='SKpacket'",BigDecimal.class));
            long id=jdbc.queryForObject("select id from orders where number='SKpacket'",Long.class);
            verify(red).reserve(777L,1L,id);
        } finally {reset(red);}
    }
    @Test void batchFailureRollsBackAllWritesThenCoordinatorIsolatesBadRequest() {
        var good=command(1,"SKgood");var bad=command(2,"SKbad");store.accept(good);store.accept(bad);
        doAnswer(call->{List<Orders> orders=call.getArgument(0);if(orders.stream().anyMatch(order->"SKbad".equals(order.getNumber())))
            throw new OrderBusinessException("红包不可用");return null;}).when(publisher).timeoutDeferredBatch(anyList());
        doThrow(new OrderBusinessException("红包不可用")).when(publisher).timeoutDeferred(eq("SKbad"),any());
        var prepared=List.of(service.prepareSeckillOrder(good),service.prepareSeckillOrder(bad));
        assertThrows(OrderBusinessException.class,()->service.createPreparedOrders(prepared));
        assertEquals(0,count("select count(*) from orders"));assertEquals(20,count("select remaining_stock from seckill_activity"));
        assertEquals(0,count("select count(*) from seckill_order_guard"));assertEquals(0,count("select count(*) from seckill_cache_event"));
        var failures=new SeckillBatchCoordinator(service).fulfill(List.of(good,bad));assertEquals(Set.of("SKbad"),failures.keySet());
        assertEquals(1,count("select count(*) from orders"));assertEquals(19,count("select remaining_stock from seckill_activity"));
    }
    @Test void bulkFulfillmentStillHonorsMysqlStockAndExpiredAcceptance() {
        var commands=new ArrayList<SeckillOrderMessageDTO>();for(int i=0;i<30;i++){var row=command(i+1,"SKcapbatch"+i);store.accept(row);commands.add(row);}
        var failures=new SeckillBatchCoordinator(service).fulfill(commands);
        assertEquals(20,count("select count(*) from orders"));assertEquals(0,count("select remaining_stock from seckill_activity"));assertEquals(10,failures.size());
        var late=command(100,"SKbatchlate");store.accept(late);var prepared=service.prepareSeckillOrder(late);
        jdbc.update("update seckill_submit_request set deadline_at=date_sub(now(3),interval 1 second) where order_number='SKbatchlate'");
        assertThrows(OrderBusinessException.class,()->service.createPreparedOrders(List.of(prepared)));
        assertEquals(20,count("select count(*) from orders"));
    }
    @Test void failedCreationRollsBackInventoryOrderAndEligibility(){
        var c=command(1,"SKrollback");store.accept(c);doThrow(new IllegalStateException("timeout outbox insert failed")).when(publisher).timeoutDeferred(anyString(),any());
        assertThrows(IllegalStateException.class,()->service.createSeckillOrder(c));assertEquals(20,count("select remaining_stock from seckill_activity"));
        assertEquals(0,count("select count(*) from orders"));assertEquals(0,count("select count(*) from seckill_order_guard"));assertEquals("ACCEPTED",store.get(c.getOrderNumber()).get("state"));
        reset(publisher);service.createSeckillOrder(c);assertEquals(19,count("select remaining_stock from seckill_activity"));
    }
    @Test void sameUserCannotCreateTwoActiveOrdersEvenWithDifferentRequests(){
        var first=command(1,"SKfirst");var second=command(1,"SKsecond");store.accept(first);store.accept(second);
        service.createSeckillOrder(first);
        assertThrows(com.sky.exception.OrderBusinessException.class,()->service.createSeckillOrder(second));
        assertEquals(1,count("select count(*) from orders"));assertEquals(19,count("select remaining_stock from seckill_activity"));
    }
    @Test void expiryAndLateDeliveryAreMutuallyExclusive(){
        var c=command(1,"SKexpired");store.accept(c);
        jdbc.update("update seckill_submit_request set deadline_at=date_sub(now(3),interval 1 second) where order_number=?",c.getOrderNumber());
        store.republishOrExpire(c.getOrderNumber());service.createSeckillOrder(c);
        assertEquals("FAILED",store.get(c.getOrderNumber()).get("state"));assertEquals(0,count("select count(*) from orders"));
        assertEquals(20,count("select remaining_stock from seckill_activity"));
    }
    @Test void failedHandoffOutboxInsertRollsBackAcceptance(){
        var c=command(1,"SKhandoff");doThrow(new IllegalStateException("outbox unavailable")).when(publisher).seckillDeferredBatch(anyList());
        assertThrows(IllegalStateException.class,()->store.accept(c));assertNull(store.get(c.getOrderNumber()));
        reset(publisher);store.accept(c);store.accept(c);verify(publisher,times(1)).seckillDeferredBatch(anyList());
    }
    @Test void durableFailureMakesLateMessageUnableToCreate(){
        var c=command(1,"SKlate");store.accept(c);store.failed(c.getOrderNumber(),new IllegalStateException("expired"),true);
        service.createSeckillOrder(c);assertEquals(0,count("select count(*) from orders"));assertEquals(20,count("select remaining_stock from seckill_activity"));
        assertEquals(1,count("select count(*) from seckill_cache_event where state='FAILED'"));
    }
    SeckillAsyncStore realBatchStore() {
        var realPublisher=new OrderMessagePublisher(mock(org.springframework.amqp.rabbit.core.RabbitTemplate.class),outboxMapper);
        var target=new SeckillAsyncStore(jdbc,activities,realPublisher);ReflectionTestUtils.setField(target,"processingSeconds",120L);return proxy(target);
    }
    @Test void mixedBucketHandoffRoutesEveryLaneAndOnlySeckillPublisherClaimsIt() {
        jdbc.update("update seckill_activity set bucket_count=8 where id=1");
        var commands=new ArrayList<SeckillOrderMessageDTO>();
        for(int bucket=0;bucket<8;bucket++){var c=command(bucket+1,"SKrouting"+bucket);c.setBucketCount(8);c.setBucketId(bucket);commands.add(c);}
        realBatchStore().acceptBatch(commands);
        assertTrue(outboxMapper.listRetryable(5,50).isEmpty());
        var publishing=proxy(new com.sky.service.mq.SeckillPublishStore(jdbc));var rows=publishing.claim(false,200);
        assertEquals(8,rows.size());assertEquals(8,rows.stream().map(com.sky.entity.MqFailMessage::getRoutingKey).distinct().count());
        for(var row:rows)assertTrue(com.sky.service.mq.SeckillRouting.isOrder(row.getRoutingKey()));
        assertTrue(publishing.claim(false,200).isEmpty());
    }
    @Test void concurrentBatchPublishClaimsAreExclusiveAndOldConfirmCannotSettleNewLease()throws Exception {
        var bridge=realBatchStore();var commands=new ArrayList<SeckillOrderMessageDTO>();
        for(int i=0;i<25;i++)commands.add(command(i+1,"SKpublish"+i));bridge.acceptBatch(commands);
        var publishing=proxy(new com.sky.service.mq.SeckillPublishStore(jdbc));
        var claimed=new java.util.concurrent.ConcurrentLinkedQueue<com.sky.entity.MqFailMessage>();
        parallel(8,i->claimed.addAll(publishing.claim(false,200)));assertEquals(25,claimed.size());
        assertEquals(25,claimed.stream().map(com.sky.entity.MqFailMessage::getId).distinct().count());
        jdbc.update("update mq_fail_message set lease_until=date_sub(now(),interval 1 second)");publishing.releaseExpiredClaims();
        var newLease=publishing.claim(false,200);assertEquals(25,newLease.size());
        publishing.complete(claimed.stream().map(row->new com.sky.service.mq.SeckillPublishStore.Result(row.getId(),row.getClaimToken(),false,true)).toList());
        assertEquals(25,count("select count(*) from mq_fail_message where status=3"));
        publishing.complete(newLease.stream().map(row->new com.sky.service.mq.SeckillPublishStore.Result(row.getId(),row.getClaimToken(),false,true)).toList());
        assertEquals(25,count("select count(*) from mq_fail_message where status=1"));
    }
    @Test void batchPublishingRetriesCreationBoundedlyButTimeoutsRemainDurable() {
        var publishing=proxy(new com.sky.service.mq.SeckillPublishStore(jdbc));
        realBatchStore().acceptBatch(List.of(command(1,"SKbounded")));
        new org.springframework.transaction.support.TransactionTemplate(tm).executeWithoutResult(tx->
            new OrderMessagePublisher(mock(org.springframework.amqp.rabbit.core.RabbitTemplate.class),outboxMapper).timeoutDeferred("SKtimeout",java.time.LocalDateTime.now().minusSeconds(1)));
        for(int round=0;round<8;round++) {
            jdbc.update("update mq_fail_message set available_at=date_sub(now(3),interval 1 second) where status=0");
            for(boolean timeout:List.of(false,true)) {
                var batch=publishing.claim(timeout,200);
                publishing.complete(batch.stream().map(row->new com.sky.service.mq.SeckillPublishStore.Result(row.getId(),row.getClaimToken(),timeout,false)).toList());
            }
        }
        assertEquals(2,count("select status from mq_fail_message where routing_key='seckill.order'"));
        assertEquals(5,count("select retry_count from mq_fail_message where routing_key='seckill.order'"));
        assertEquals(0,count("select status from mq_fail_message where routing_key='seckill.timeout'"));
        assertEquals(8,count("select retry_count from mq_fail_message where routing_key='seckill.timeout'"));
        assertTrue(outboxMapper.listRetryable(5,50).isEmpty());
    }
    @Test void confirmationBatchDoesNotLockAnUnrelatedClaimedMessage()throws Exception {
        var commands=new ArrayList<SeckillOrderMessageDTO>();for(int i=0;i<26;i++)commands.add(command(i+1,"SKconfirm"+i));realBatchStore().acceptBatch(commands);
        var publishing=proxy(new com.sky.service.mq.SeckillPublishStore(jdbc));var claimed=publishing.claim(false,200);
        var ordered=claimed.stream().sorted(java.util.Comparator.comparing(com.sky.entity.MqFailMessage::getId)).toList();
        var held=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var blocker=pool.submit(()->new org.springframework.transaction.support.TransactionTemplate(tm).executeWithoutResult(status->{
                jdbc.queryForObject("select id from mq_fail_message where id=? for update",Long.class,ordered.get(25).getId());held.countDown();
                try{if(!release.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("test release timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
            }));assertTrue(held.await(3,java.util.concurrent.TimeUnit.SECONDS));
            var settle=pool.submit(()->publishing.complete(ordered.subList(0,25).stream().map(row->new com.sky.service.mq.SeckillPublishStore.Result(row.getId(),row.getClaimToken(),false,true)).toList()));
            settle.get(2,java.util.concurrent.TimeUnit.SECONDS);assertEquals(25,count("select count(*) from mq_fail_message where status=1"));release.countDown();blocker.get(3,java.util.concurrent.TimeUnit.SECONDS);
        }finally{release.countDown();pool.shutdownNow();}
    }
    @Test void batchedCacheDeleteDoesNotLockUnrelatedEventsAndPreservesNewerCancellation() throws Exception {
        var numbers=new ArrayList<String>();for(int i=0;i<200;i++)numbers.add(String.format("SKpoint%04d",i));numbers.add("SKzzhold");
        jdbc.update("insert into seckill_cache_event(order_number,activity_id,user_id,state) values "+
            String.join(",",Collections.nCopies(numbers.size(),"(?,1,1,'SUCCEEDED')")),numbers.toArray());
        var events=jdbc.queryForList("select * from seckill_cache_event where order_number<>'SKzzhold' order by order_number");
        jdbc.update("update seckill_cache_event set state='CANCELLED' where order_number='SKpoint0000'");
        var held=new CountDownLatch(1);var release=new CountDownLatch(1);var executor=Executors.newFixedThreadPool(2);
        try {
            var lock=executor.submit(()->new org.springframework.transaction.support.TransactionTemplate(tm).execute(status->{
                jdbc.queryForList("select * from seckill_cache_event where order_number='SKzzhold' for update");held.countDown();
                try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){throw new RuntimeException(e);}return null;}));
            assertTrue(held.await(3,TimeUnit.SECONDS));executor.submit(()->store.eventsDone(events)).get(3,TimeUnit.SECONDS);
            assertEquals(2,count("select count(*) from seckill_cache_event"));
            assertEquals("CANCELLED",jdbc.queryForObject("select state from seckill_cache_event where order_number='SKpoint0000'",String.class));
            release.countDown();lock.get(3,TimeUnit.SECONDS);
        } finally {release.countDown();executor.shutdownNow();}
    }
    @Test void batchedCacheEventDeleteNeverRemovesNewerState() {
        store.event("SKevent",1L,1L,"req","SUCCEEDED",null);
        var old=store.events();store.event("SKevent",1L,1L,"req","CANCELLED",null);
        store.eventsDone(old);assertEquals(1,count("select count(*) from seckill_cache_event"));
        store.eventsDone(store.events());assertEquals(0,count("select count(*) from seckill_cache_event"));
    }
    @Test void batchPersistsOneOutboxPerRequestEvenWithConcurrentRepeatedHandoffs()throws Exception {
        var target=realBatchStore();List<SeckillOrderMessageDTO> commands=new ArrayList<>();
        for(int i=0;i<25;i++) commands.add(command(i+1,"SKbatch"+i));
        parallel(8,i->target.acceptBatch(commands));
        assertEquals(25,count("select count(*) from seckill_submit_request"));assertEquals(25,count("select count(*) from mq_fail_message"));
    }
    @Test void oneBadBatchOutboxInsertRollsBackEveryAcceptance() {
        var target=realBatchStore();jdbc.execute("alter table mq_fail_message rename mq_fail_message_saved");
        try {
            assertThrows(Exception.class,()->target.acceptBatch(List.of(command(1,"SKbad1"),command(2,"SKbad2"))));
            assertEquals(0,count("select count(*) from seckill_submit_request"));
        } finally {jdbc.execute("alter table mq_fail_message_saved rename mq_fail_message");}
    }
    @Test void conflictingBatchDoesNotCreatePartialRequestsOrOutbox() {
        var target=realBatchStore();var original=command(1,"SKidentity");target.acceptBatch(List.of(original));
        var conflicting=command(1,"SKdifferent");conflicting.getRequest().setRequestId(original.getRequest().getRequestId());
        assertThrows(SeckillAsyncStore.AcceptanceConflict.class,()->target.acceptBatch(List.of(command(2,"SKfresh"),conflicting)));
        assertEquals(1,count("select count(*) from seckill_submit_request"));assertEquals(1,count("select count(*) from mq_fail_message"));
    }
    @Test void timeoutIsNotEligibleEarlyAndRemainsRetryableBeyondFiveFailures() {
        var realPublisher=new OrderMessagePublisher(mock(org.springframework.amqp.rabbit.core.RabbitTemplate.class),outboxMapper);
        var deadline=java.time.LocalDateTime.now().withNano(0).plusMinutes(15);
        new org.springframework.transaction.support.TransactionTemplate(tm).executeWithoutResult(tx->realPublisher.timeoutDeferred("SKdue",deadline));
        assertEquals(1,outboxMapper.listSeckillTimeoutPending(200).size());assertTrue(outboxMapper.listRetryable(5,50).isEmpty());
        long id=jdbc.queryForObject("select id from mq_fail_message",Long.class);
        for(int i=0;i<8;i++) {
            jdbc.update("update mq_fail_message set available_at=date_sub(now(3),interval 1 second) where id=?",id);
            assertEquals(1,outboxMapper.listSeckillTimeoutPending(200).size());assertEquals(1,outboxMapper.claimTimeout(id));assertEquals(0,outboxMapper.claimTimeout(id));
            assertEquals(1,outboxMapper.retryTimeout(id,"broker down"));assertTrue(outboxMapper.listSeckillTimeoutPending(200).isEmpty());
        }
        assertEquals(0,count("select status from mq_fail_message"));assertEquals(8,count("select retry_count from mq_fail_message"));
    }

}
