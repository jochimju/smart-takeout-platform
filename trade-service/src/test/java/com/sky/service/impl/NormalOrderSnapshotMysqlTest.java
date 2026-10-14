package com.sky.service.impl;
import com.sky.context.BaseContext;
import com.sky.dto.*;
import com.sky.entity.*;
import com.sky.mapper.*;
import com.sky.service.mq.OrderMessagePublisher;
import com.sky.exception.OrderBusinessException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.*;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
@EnabledIfSystemProperty(named="order.integration",matches="true")
class NormalOrderSnapshotMysqlTest {
 static String schema,base,user,password;static JdbcTemplate jdbc;static DriverManagerDataSource ds;static TransactionTemplate tx;
 static OrderSubmitRequestMapper requests;static ShoppingCartMapper carts;static OrderServiceImpl service;static OrderMessagePublisher publisher;
 @BeforeAll static void setup()throws Exception {
  var y=new YamlPropertiesFactoryBean();y.setResources(new ClassPathResource("application-dev.yml"));var p=y.getObject();user=p.getProperty("sky.datasource.username");password=p.getProperty("sky.datasource.password");
  base="jdbc:mysql://"+p.getProperty("sky.datasource.host")+":"+p.getProperty("sky.datasource.port")+"/";schema="sky_normal_it_"+UUID.randomUUID().toString().replace("-","");
  try(var c=DriverManager.getConnection(base+"?allowPublicKeyRetrieval=true&useSSL=false",user,password);var s=c.createStatement()){s.execute("create database "+schema);}
  ds=new DriverManagerDataSource(base+schema+"?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=Asia/Shanghai",user,password);jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
  jdbc.execute("create table order_submit_request(user_id bigint,request_id varchar(64),order_number varchar(64) unique,create_time datetime,primary key(user_id,request_id)) engine=InnoDB");
  jdbc.execute("create table shopping_cart(id bigint primary key,user_id bigint,setmeal_id bigint,dish_id bigint,number int,name varchar(64),dish_flavor varchar(64),amount decimal(10,2),image varchar(64),create_time datetime,canteen_id bigint) engine=InnoDB");
  try(var c=ds.getConnection()){ScriptUtils.executeSqlScript(c,new ClassPathResource("db/migration/V2026101602__normal_order_snapshot.sql"));}
  var f=new SqlSessionFactoryBean();f.setDataSource(ds);f.setTypeAliasesPackage("com.sky.entity");f.setMapperLocations(new ClassPathResource("mapper/ShoppingCartMapper.xml"));
  var cfg=new org.apache.ibatis.session.Configuration();cfg.setMapUnderscoreToCamelCase(true);f.setConfiguration(cfg);var sql=new SqlSessionTemplate(f.getObject());sql.getConfiguration().addMapper(OrderSubmitRequestMapper.class);
  requests=sql.getMapper(OrderSubmitRequestMapper.class);carts=sql.getMapper(ShoppingCartMapper.class);publisher=mock(OrderMessagePublisher.class);
  service=new OrderServiceImpl();ReflectionTestUtils.setField(service,"orderSubmitRequestMapper",requests);ReflectionTestUtils.setField(service,"shoppingCartMapper",carts);ReflectionTestUtils.setField(service,"orderMapper",mock(OrderMapper.class));ReflectionTestUtils.setField(service,"orderMessagePublisher",publisher);
 }
 @AfterAll static void cleanup()throws Exception {if(schema!=null)try(var c=DriverManager.getConnection(base+"?allowPublicKeyRetrieval=true&useSSL=false",user,password);var s=c.createStatement()){s.execute("drop database "+schema);}}
 @BeforeEach void resetData(){jdbc.update("delete from order_submit_request");jdbc.update("delete from shopping_cart");jdbc.update("insert into shopping_cart(id,user_id,setmeal_id,number,create_time) values(1,11,48,1,now())");reset(publisher);BaseContext.setCurrentId(11L);}
 @AfterEach void clear(){BaseContext.removeCurrentId();}
 OrdersSubmitDTO request(){var d=new OrdersSubmitDTO();d.setRequestId("snapshot_request_001");d.setAddressBookId(1L);return d;}
 @Test void submissionSnapshotSurvivesCartEditAndDoesNotDeleteNewContent() {
  tx.execute(s->service.submitOrder(request()));var row=requests.find(11L,"snapshot_request_001");
  var body=com.alibaba.fastjson.JSON.parseObject((String)row.get("command_body"),OrderSubmitMessageDTO.class);assertEquals(1,body.getCartSnapshot().get(0).getNumber());
  carts.updateNumberById(ShoppingCart.builder().id(1L).number(3).build());
  assertEquals(0,carts.deleteSubmitted(body.getCartSnapshot().get(0)));assertEquals(3,carts.list(ShoppingCart.builder().userId(11L).build()).get(0).getNumber());
 }
 @Test void sameRequestConflictAndTerminalFailureRemainQueryable() {
  var d=request();var first=tx.execute(s->service.submitOrder(d));assertEquals("ACCEPTED",first.getSubmitStatus());
  assertEquals(first.getOrderNumber(),tx.execute(s->service.submitOrder(d)).getOrderNumber());
  d.setRemark("changed");assertThrows(OrderBusinessException.class,()->tx.execute(s->service.submitOrder(d)));
  tx.executeWithoutResult(s->requests.failed(first.getOrderNumber(),"库存不足",true));assertEquals("FAILED",service.findSubmitResult("snapshot_request_001").getSubmitStatus());
  assertEquals(1,jdbc.queryForObject("select count(*) from order_submit_request",Integer.class));verify(publisher,times(1)).submitAfterCommit(any());
 }
 @Test void concurrentReplayOnlyRegistersOneCommand()throws Exception {
  var pool=Executors.newFixedThreadPool(8);
  try {var jobs=new ArrayList<Future<String>>();for(int i=0;i<20;i++)jobs.add(pool.submit(()->{BaseContext.setCurrentId(11L);try{return tx.execute(s->service.submitOrder(request())).getOrderNumber();}finally{BaseContext.removeCurrentId();}}));
   var numbers=new HashSet<String>();for(var job:jobs)numbers.add(job.get(20,TimeUnit.SECONDS));assertEquals(1,numbers.size());verify(publisher,times(1)).submitAfterCommit(any());
  }finally{pool.shutdownNow();}
 }
 @Test void preparedConsumerUsesSnapshotAndServerPackagingDefaults() {
  var dto=request();dto.setUseRedPacket(false);var accepted=tx.execute(s->service.submitOrder(dto));
  carts.updateNumberById(ShoppingCart.builder().id(1L).number(3).build());
  var client=mock(com.sky.client.CatalogClient.class);when(client.quotes(any())).thenAnswer(call->{assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());return List.of(com.sky.contract.catalog.ProductQuote.builder().productType("SETMEAL").productId(48L).stock(10).status(1).price(java.math.BigDecimal.ONE).build());});
  ReflectionTestUtils.setField(service,"catalogQuoteService",new CatalogQuoteService(client));
  var account=mock(com.sky.client.AccountClient.class);when(account.address(11L,1L)).thenAnswer(call->{assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());var a=new com.sky.contract.account.AccountAddressView();a.setUserId(11L);a.setId(1L);return a;});ReflectionTestUtils.setField(service,"accountClient",account);
  var inventory=mock(TradeInventoryService.class);ReflectionTestUtils.setField(service,"tradeInventoryService",inventory);
  ReflectionTestUtils.setField(service,"redPacketMapper",mock(RedPacketMapper.class));ReflectionTestUtils.setField(service,"orderDetailMapper",mock(OrderDetailMapper.class));
  var redis=mock(org.springframework.data.redis.core.RedisTemplate.class);var values=mock(org.springframework.data.redis.core.ValueOperations.class);when(redis.opsForValue()).thenReturn(values);when(values.get("SHOP_STATUS")).thenReturn(1);ReflectionTestUtils.setField(service,"redisTemplate",redis);
  var orders=mock(OrderMapper.class);ReflectionTestUtils.setField(service,"orderMapper",orders);var captured=new java.util.concurrent.atomic.AtomicReference<Orders>();doAnswer(call->{Orders o=call.getArgument(0);o.setId(55L);captured.set(o);return null;}).when(orders).insert(any(Orders.class));
  var received=OrderSubmitMessageDTO.builder().orderNumber(accepted.getOrderNumber()).build();var prepared=service.prepareNormalOrder(received);tx.executeWithoutResult(s->service.createPreparedNormalOrder(prepared));
  assertEquals(1,captured.get().getPackAmount());assertEquals(0,captured.get().getTablewareNumber());assertEquals(new java.math.BigDecimal("8.00"),captured.get().getAmount());
  assertEquals("SUCCEEDED",requests.find(11L,dto.getRequestId()).get("state"));assertEquals(3,carts.list(ShoppingCart.builder().userId(11L).build()).get(0).getNumber());
  verify(inventory).deductQuoted(argThat(items->items.get(0).getNumber()==1),anyMap());
 }
}
