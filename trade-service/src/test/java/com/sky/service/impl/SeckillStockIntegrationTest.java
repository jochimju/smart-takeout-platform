package com.sky.service.impl;

import com.sky.context.BaseContext;
import com.sky.client.AccountClient;
import com.sky.contract.account.AccountAddressView;
import com.sky.dto.SeckillOrderSubmitDTO;
import com.sky.entity.*;
import com.sky.mapper.*;
import com.sky.vo.OrderSubmitVO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntConsumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Uses real MySQL and Lua in isolated random schema/keyspace. Opt in: -Dseckill.integration=true. */
@EnabledIfSystemProperty(named="seckill.integration",matches="true")
class SeckillStockIntegrationTest {
 static String schema,base,user,password; static JdbcTemplate jdbc; static DriverManagerDataSource ds;
 static DataSourceTransactionManager tm; static SqlSessionTemplate sql; static SeckillServiceImpl service;
 static SeckillCache cache; static StringRedisTemplate redis; static LettuceConnectionFactory connection;
 static OrderLifecycleService lifecycle; static OrderReliabilityStore jobs; static String prefix;
 @BeforeAll static void setup() throws Exception {
  ((ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).setLevel(ch.qos.logback.classic.Level.WARN);
  YamlPropertiesFactoryBean yaml=new YamlPropertiesFactoryBean();yaml.setResources(new ClassPathResource("application-dev.yml"));Properties p=yaml.getObject();
  user=p.getProperty("sky.datasource.username");password=p.getProperty("sky.datasource.password");
  base="jdbc:mysql://"+p.getProperty("sky.datasource.host")+":"+p.getProperty("sky.datasource.port")+"/";
  String opt="?serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true";
  schema="sky_seckill_it_"+UUID.randomUUID().toString().replace("-","");
  try(Connection c=DriverManager.getConnection(base+opt,user,password);Statement s=c.createStatement()){s.execute("create database "+schema);}
  ds=new DriverManagerDataSource(base+schema+opt,user,password);jdbc=new JdbcTemplate(ds);
  for(String table:Arrays.asList("orders","order_detail","setmeal","dish","address_book","user_coupon","seckill_activity","seckill_reservation","seckill_order_guard"))
   jdbc.execute("create table "+table+" like sky_take_out."+table);
  try(Connection c=ds.getConnection()) {
   if(jdbc.queryForObject("select count(*) from information_schema.columns where table_schema=? and table_name='orders' and column_name='expire_time'",Integer.class,schema)==0)
    ScriptUtils.executeSqlScript(c,new ClassPathResource("db/migration/V2026091201__order_reliability.sql"));
   else for(String t:Arrays.asList("order_reliability_job","order_payment_receipt"))jdbc.execute("create table "+t+" like sky_take_out."+t);
   if(jdbc.queryForObject("select count(*) from information_schema.columns where table_schema=? and table_name='seckill_activity' and column_name='remaining_stock'",Integer.class,schema)==0)
    ScriptUtils.executeSqlScript(c,new ClassPathResource("db/seckill-stock-v2.sql"));
  }
  SqlSessionFactoryBean f=new SqlSessionFactoryBean();f.setDataSource(ds);f.setTypeAliasesPackage("com.sky.entity,com.sky.dto,com.sky.vo");
  f.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath:mapper/*.xml"));
  org.apache.ibatis.session.Configuration config=new org.apache.ibatis.session.Configuration();config.setMapUnderscoreToCamelCase(true);f.setConfiguration(config);
  sql=new SqlSessionTemplate(f.getObject());
  for(Class<?> mapper:Arrays.asList(SeckillActivityMapper.class,SeckillReservationMapper.class,SeckillOrderGuardMapper.class,UserCouponMapper.class))
   if(!sql.getConfiguration().hasMapper(mapper))sql.getConfiguration().addMapper(mapper);
  tm=new DataSourceTransactionManager(ds);jobs=spy(new OrderReliabilityStore(jdbc));
  connection=new LettuceConnectionFactory("127.0.0.1",Integer.getInteger("seckill.redis.port",16479));connection.afterPropertiesSet();
  redis=new StringRedisTemplate(connection);prefix=schema+":";cache=spy(new SeckillCache());
  ReflectionTestUtils.setField(cache,"redis",redis);ReflectionTestUtils.setField(cache,"guards",sql.getMapper(SeckillOrderGuardMapper.class));ReflectionTestUtils.setField(cache,"prefix",prefix);
  SeckillServiceImpl target=new SeckillServiceImpl();
  ReflectionTestUtils.setField(target,"seckillActivityMapper",sql.getMapper(SeckillActivityMapper.class));
  ReflectionTestUtils.setField(target,"seckillOrderGuardMapper",sql.getMapper(SeckillOrderGuardMapper.class));
  ReflectionTestUtils.setField(target,"reservationMapper",sql.getMapper(SeckillReservationMapper.class));
  CatalogQuoteService catalog=mock(CatalogQuoteService.class);
  when(catalog.quoteSetmeal(anyLong())).thenAnswer(call -> com.sky.contract.catalog.ProductQuote.builder()
      .productId(call.getArgument(0)).productType("SETMEAL").name("setmeal").image("image")
      .price(new BigDecimal("20.00")).status(1).stock(100).version(1L).build());
  ReflectionTestUtils.setField(target,"catalogQuoteService",catalog);
  AccountClient account = mock(AccountClient.class);
  when(account.address(anyLong(), anyLong())).thenAnswer(call -> {
   Long userId = call.getArgument(0); Long addressId = call.getArgument(1);
   List<AccountAddressView> result = jdbc.query("select * from address_book where id=? and user_id=?", (rs, row) ->
       new AccountAddressView(rs.getLong("id"), rs.getLong("user_id"),
           rs.getString("consignee"), rs.getString("phone"), rs.getString("detail")), addressId, userId);
   return result.isEmpty() ? null : result.get(0);
  });
  ReflectionTestUtils.setField(target,"accountClient",account);
  ReflectionTestUtils.setField(target,"orderMapper",sql.getMapper(OrderMapper.class));ReflectionTestUtils.setField(target,"orderDetailMapper",sql.getMapper(OrderDetailMapper.class));
  ReflectionTestUtils.setField(target,"jobs",jobs);ReflectionTestUtils.setField(target,"cache",cache);service=proxy(target);
  SeckillReservationService reservations=new SeckillReservationService();
  ReflectionTestUtils.setField(reservations,"mapper",sql.getMapper(SeckillReservationMapper.class));ReflectionTestUtils.setField(reservations,"activities",sql.getMapper(SeckillActivityMapper.class));ReflectionTestUtils.setField(reservations,"guards",sql.getMapper(SeckillOrderGuardMapper.class));
  OrderLifecycleService life=new OrderLifecycleService(sql.getMapper(OrderMapper.class),sql.getMapper(OrderDetailMapper.class),mock(TradeInventoryService.class),sql.getMapper(UserCouponMapper.class),reservations,jobs,mock(BusinessEventOutbox.class),jdbc);
  ReflectionTestUtils.setField(life,"mockEnabled",true);lifecycle=proxy(life);
 }
 @SuppressWarnings("unchecked") static <T>T proxy(T target){ProxyFactory p=new ProxyFactory(target);p.setProxyTargetClass(true);p.addAdvice(new TransactionInterceptor(tm,new AnnotationTransactionAttributeSource()));return (T)p.getProxy();}
 @AfterAll static void cleanup() throws Exception {
  if(redis!=null){Set<String> keys=redis.keys(prefix+"*");if(keys!=null&&!keys.isEmpty())redis.delete(keys);connection.destroy();}
  if(schema!=null&&schema.matches("sky_seckill_it_[a-f0-9]{32}"))try(Connection c=DriverManager.getConnection(base+"?useSSL=false&allowPublicKeyRetrieval=true",user,password);Statement s=c.createStatement()){s.execute("drop database "+schema);}
 }
 @BeforeEach void resetData(){
  reset(jobs,cache);
  for(String t:Arrays.asList("order_reliability_job","order_payment_receipt","order_detail","orders","seckill_reservation","seckill_order_guard","seckill_activity","setmeal","address_book"))jdbc.update("delete from "+t);
  Set<String> keys=redis.keys(prefix+"*");if(keys!=null&&!keys.isEmpty())redis.delete(keys);
  jdbc.update("insert into setmeal(id,category_id,name,price,status,stock) values(48,1,'test',20,1,100)");
  jdbc.update("insert into seckill_activity(id,setmeal_id,stock,remaining_stock,stock_version,seckill_price,begin_time,end_time,status) values(1,48,20,20,0,8.9,date_sub(now(),interval 1 hour),date_add(now(),interval 1 hour),1)");
  for(int i=1;i<=120;i++)jdbc.update("insert into address_book(id,user_id,phone,detail) values(?,?,?,?)",i,i,"13000000000","test");
 }
 SeckillOrderSubmitDTO request(int user,String token){SeckillOrderSubmitDTO r=new SeckillOrderSubmitDTO();r.setActivityId(1L);r.setSetmealId(48L);r.setAddressBookId((long)user);r.setRequestId(token);r.setAmount(new BigDecimal("15.90"));return r;}
 OrderSubmitVO buy(int user,String token){BaseContext.setCurrentId((long)user);return service.seckillOrder(request(user,token));}
 int n(String expression){return jdbc.queryForObject(expression,Integer.class);}
 void parallel(int count,IntConsumer action)throws Exception {
  ExecutorService pool=Executors.newFixedThreadPool(16);List<Future<?>> futures=new ArrayList<>();
  try{for(int i=0;i<count;i++){final int index=i;futures.add(pool.submit(()->action.accept(index)));}for(Future<?> f:futures)f.get(45,TimeUnit.SECONDS);}finally{pool.shutdownNow();}
 }
 @Test void hundredBuyersNeverExceedTwentyAndCacheLossCannotRefill()throws Exception{
  java.util.concurrent.atomic.AtomicInteger success=new java.util.concurrent.atomic.AtomicInteger();
  parallel(100,i->{try{buy(i+1,"request_user_"+String.format("%08d",i));success.incrementAndGet();}catch(com.sky.exception.OrderBusinessException e){assertTrue(e.getMessage().contains("库存不足"),e.getMessage());}});
  assertEquals(20,success.get());assertEquals(0,n("select remaining_stock from seckill_activity where id=1"));assertEquals(20,n("select count(*) from orders"));assertEquals(20,n("select count(*) from order_reliability_job"));
  redis.delete(cache.keys(1L));assertThrows(com.sky.exception.OrderBusinessException.class,()->buy(120,"request_after_cache_loss"));
  assertEquals("0",redis.opsForValue().get(cache.keys(1L).get(1)));
 }
 @Test void duplicateRequestsAndSameUserCreateOneOrder()throws Exception{
  Set<Long> ids=ConcurrentHashMap.newKeySet();parallel(30,i->ids.add(buy(1,"same_request_00000001").getId()));assertEquals(1,ids.size());
  parallel(30,i->assertThrows(com.sky.exception.OrderBusinessException.class,()->buy(1,"another_request_"+String.format("%08d",i))));
  assertEquals(1,n("select count(*) from orders"));assertEquals(19,n("select remaining_stock from seckill_activity where id=1"));
 }
 @Test void concurrentCancellationRestoresOnceAndAllowsNewPurchase()throws Exception{
  OrderSubmitVO first=buy(1,"first_request_00000001");parallel(30,i->{BaseContext.setCurrentId(1L);lifecycle.cancel(first.getId(),"cancel","USER");});
  assertEquals(20,n("select remaining_stock from seckill_activity where id=1"));assertEquals(0,n("select count(*) from seckill_order_guard"));
  assertEquals(first.getId(),buy(1,"first_request_00000001").getId());OrderSubmitVO next=buy(1,"second_request_0000001");assertNotEquals(first.getId(),next.getId());
  lifecycle.cancel(first.getId(),"duplicate","USER");assertEquals(19,n("select remaining_stock from seckill_activity where id=1"));assertEquals(1,n("select count(*) from seckill_order_guard"));
 }
 @Test void failedDatabaseCommitAndRedisFailureDoNotConsumeDatabaseStock(){
  doThrow(new IllegalStateException("injected outbox failure")).when(jobs).scheduleTimeout(any());
  assertThrows(IllegalStateException.class,()->buy(1,"rollback_request_0001"));assertEquals(20,n("select remaining_stock from seckill_activity where id=1"));assertEquals(0,n("select count(*) from orders"));
  reset(jobs);buy(1,"rollback_request_0001");assertEquals(19,n("select remaining_stock from seckill_activity where id=1"));
  doThrow(new org.springframework.data.redis.RedisConnectionFailureException("injected unavailable")).when(cache).rebuild(any());
  assertThrows(com.sky.exception.OrderBusinessException.class,()->buy(2,"unavailable_request_1"));assertEquals(19,n("select remaining_stock from seckill_activity where id=1"));
 }
 @Test void stockAdjustmentCannotEraseOccupiedUnitsAndRetryDoesNotAddTwice(){
  buy(1,"adjust_request_00001");assertThrows(com.sky.exception.OrderBusinessException.class,()->service.adjustStock(1L,0,20));
  service.adjustStock(1L,25,20);service.adjustStock(1L,25,20);assertEquals(24,n("select remaining_stock from seckill_activity where id=1"));
  redis.opsForValue().set(cache.keys(1L).get(1),"999");service.warmUpActivity(1L);assertEquals("24",redis.opsForValue().get(cache.keys(1L).get(1)));
 }
 @Test void paymentCancellationRaceCannotResurrectOrDoubleRelease()throws Exception{
  OrderSubmitVO first=buy(1,"pay_cancel_request_1");parallel(20,i->{BaseContext.setCurrentId(1L);if(i%2==0)lifecycle.cancel(first.getId(),"cancel","USER");else try{lifecycle.paid(first.getOrderNumber(),"mock_test_tx",new BigDecimal("15.90"),true);}catch(com.sky.exception.OrderBusinessException expected){assertTrue(expected.getMessage().contains("no longer payable"));}});
  assertEquals(6,n("select status from orders"));assertEquals(20,n("select remaining_stock from seckill_activity where id=1"));assertEquals(0,n("select count(*) from seckill_order_guard"));
 }
 @Test void timeoutRestoresEligibilityAndMetadataEditDoesNotResetStock(){
  OrderSubmitVO first=buy(1,"timeout_request_0001");
  com.sky.dto.SeckillActivityDTO edit=new com.sky.dto.SeckillActivityDTO();edit.setId(1L);edit.setSetmealId(48L);edit.setStock(20);edit.setSeckillPrice(new BigDecimal("8.90"));edit.setBeginTime(java.time.LocalDateTime.now().minusHours(1));edit.setEndTime(java.time.LocalDateTime.now().plusHours(2));edit.setStatus(1);
  service.saveActivity(edit);service.warmUpActivity(1L);assertEquals("19",redis.opsForValue().get(cache.keys(1L).get(1)));
  jdbc.update("update orders set expire_time=date_sub(now(),interval 1 minute) where id=?",first.getId());
  lifecycle.timeout(first.getOrderNumber());lifecycle.timeout(first.getOrderNumber());
  assertEquals(20,n("select remaining_stock from seckill_activity where id=1"));buy(1,"after_timeout_000001");
 }
 @Test void luaRejectsEarlyExpiredDisabledWrongMealAndDuplicateWithoutDebit(){
  SeckillActivity a=sql.getMapper(SeckillActivityMapper.class).getById(1L);cache.rebuild(a);
  a.setBeginTime(java.time.LocalDateTime.now().plusHours(1));cache.rebuild(a);assertEquals(-4L,cache.reserve(a,1L,"token"));
  a.setBeginTime(java.time.LocalDateTime.now().minusHours(2));a.setEndTime(java.time.LocalDateTime.now().minusHours(1));cache.rebuild(a);assertEquals(-5L,cache.reserve(a,1L,"token"));
  a.setEndTime(java.time.LocalDateTime.now().plusHours(1));a.setStatus(0);cache.rebuild(a);assertEquals(-2L,cache.reserve(a,1L,"token"));
  a.setStatus(1);cache.rebuild(a);a.setSetmealId(49L);assertEquals(-3L,cache.reserve(a,1L,"token"));a.setSetmealId(48L);
  assertEquals("20",redis.opsForValue().get(cache.keys(1L).get(1)));assertEquals(1L,cache.reserve(a,1L,"token"));assertEquals(-1L,cache.reserve(a,1L,"other"));assertEquals("19",redis.opsForValue().get(cache.keys(1L).get(1)));
 }}
