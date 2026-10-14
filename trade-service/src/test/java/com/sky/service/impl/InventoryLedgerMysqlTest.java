package com.sky.service.impl;

import com.sky.contract.catalog.ProductQuote;
import com.sky.entity.ShoppingCart;
import com.sky.exception.OrderBusinessException;
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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfSystemProperty(named="order.integration",matches="true")
class InventoryLedgerMysqlTest {
    static JdbcTemplate jdbc; static DriverManagerDataSource ds; static TransactionTemplate tx;
    static String schema,base,user,password; static TradeInventoryService inventory; static CatalogQuoteService catalog;
    @BeforeAll static void setup() throws Exception {
        var yaml=new YamlPropertiesFactoryBean();yaml.setResources(new ClassPathResource("application-dev.yml"));var p=yaml.getObject();
        user=p.getProperty("sky.datasource.username");password=p.getProperty("sky.datasource.password");
        base="jdbc:mysql://"+p.getProperty("sky.datasource.host")+":"+p.getProperty("sky.datasource.port")+"/";
        schema="sky_inventory_it_"+UUID.randomUUID().toString().replace("-","");
        try(var c=DriverManager.getConnection(base+"?allowPublicKeyRetrieval=true&useSSL=false",user,password);var s=c.createStatement()){s.execute("create database "+schema);}
        ds=new DriverManagerDataSource(base+schema+"?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=Asia/Shanghai",user,password);
        jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        try(var c=ds.getConnection()) {
            ScriptUtils.executeSqlScript(c,new ClassPathResource("db/migration/V2026091902__trade_inventory.sql"));
            ScriptUtils.executeSqlScript(c,new ClassPathResource("db/migration/V2026101601__inventory_ledger.sql"));
            ScriptUtils.executeSqlScript(c,new ClassPathResource("db/migration/V2026101605__allocation_return_index.sql"));
        }
        catalog=mock(CatalogQuoteService.class);inventory=new TradeInventoryService(jdbc,catalog);
        jdbc.execute("create table seckill_activity(id bigint primary key,status int,remaining_stock int) engine=InnoDB");
        jdbc.execute("create table seckill_stock_bucket(activity_id bigint,bucket_id int,remaining_stock int,primary key(activity_id,bucket_id)) engine=InnoDB");
        jdbc.execute("create table seckill_submit_request(activity_id bigint,state varchar(16)) engine=InnoDB");
        jdbc.execute("create table seckill_cache_event(activity_id bigint) engine=InnoDB");
    }
    @AfterAll static void cleanup() throws Exception {
        if(schema!=null)try(var c=DriverManager.getConnection(base+"?allowPublicKeyRetrieval=true&useSSL=false",user,password);var s=c.createStatement()){s.execute("drop database "+schema);}
    }
    @BeforeEach void resetData() {
        jdbc.update("delete from trade_inventory_adjustment");jdbc.update("delete from seckill_inventory_allocation");jdbc.update("delete from trade_inventory");reset(catalog);
        jdbc.update("delete from seckill_activity");jdbc.update("delete from seckill_stock_bucket");jdbc.update("delete from seckill_submit_request");jdbc.update("delete from seckill_cache_event");
    }
    void quote(int stock,long version) {
        var q=ProductQuote.builder().productType(ProductQuote.SETMEAL).productId(1L).stock(stock).version(version).build();
        when(catalog.quotes(anyList())).thenReturn(Map.of("SETMEAL:1",q));when(catalog.quoteSetmeal(1L)).thenReturn(q);
    }
    int balance(){return jdbc.queryForObject("select stock from trade_inventory where product_type='SETMEAL' and product_id=1",Integer.class);}
    void debit(){tx.executeWithoutResult(s->inventory.deduct(List.of(ShoppingCart.builder().setmealId(1L).number(1).build())));}
    @Test void metadataUpdateNeverReplenishesSoldStock() {
        quote(10,1);debit();quote(10,2);debit();assertEquals(8,balance());
    }
    @Test void concurrentConsumersCannotOversell() throws Exception {
        quote(10,1);var pool=Executors.newFixedThreadPool(12);var success=new AtomicInteger();
        try {
            var jobs=new ArrayList<Future<?>>();for(int i=0;i<40;i++)jobs.add(pool.submit(()->{try{debit();success.incrementAndGet();}catch(OrderBusinessException expected){}}));
            for(var job:jobs)job.get(30,TimeUnit.SECONDS);
            assertEquals(10,success.get());assertEquals(0,balance());
        } finally {pool.shutdownNow();}
    }
    @Test void adjustmentReplayConflictAndFailureAreAtomic() {
        quote(10,1);debit();String key="inventory_adjust_001";
        tx.executeWithoutResult(s->inventory.adjust(key,"SETMEAL",1L,3));
        tx.executeWithoutResult(s->inventory.adjust(key,"SETMEAL",1L,3));assertEquals(12,balance());
        assertThrows(OrderBusinessException.class,()->tx.executeWithoutResult(s->inventory.adjust(key,"SETMEAL",1L,4)));
        assertThrows(OrderBusinessException.class,()->tx.executeWithoutResult(s->inventory.adjust("inventory_adjust_002","SETMEAL",1L,-13)));
        assertEquals(12,balance());assertEquals(1,jdbc.queryForObject("select count(*) from trade_inventory_adjustment",Integer.class));
    }
    @Test void allocationAndNormalSalesShareOneSupply() {
        quote(10,1);tx.executeWithoutResult(s->inventory.allocate(101L,1L,8));
        tx.executeWithoutResult(s->inventory.allocate(101L,1L,8));assertEquals(2,balance());
        assertThrows(OrderBusinessException.class,()->tx.executeWithoutResult(s->inventory.allocate(102L,1L,3)));
        assertEquals(1,jdbc.queryForObject("select count(*) from seckill_inventory_allocation",Integer.class));
        debit();debit();assertThrows(OrderBusinessException.class,this::debit);assertEquals(0,balance());
    }
    @Test void unusedAllocationReturnsOnceAndLaterCancellationCreditsNormalSupply() {
        quote(10,1);tx.executeWithoutResult(s->inventory.allocate(101L,1L,8));
        jdbc.update("insert into seckill_activity values(101,0,8)");jdbc.update("insert into seckill_stock_bucket values(101,0,5)");
        var cache=mock(SeckillCache.class);when(cache.drained(101L)).thenReturn(true);var release=new SeckillInventoryReturn(jdbc,cache);
        int first=tx.execute(s->release.closeout(101L));assertEquals(5,first);assertEquals(7,balance());
        int replay=tx.execute(s->release.closeout(101L));assertEquals(0,replay);assertEquals(7,balance());
        tx.executeWithoutResult(s->release.cancelledAfterReturn(101L));assertEquals(8,balance());
        assertEquals(6,jdbc.queryForObject("select returned_quantity from seckill_inventory_allocation",Integer.class));
    }
    @Test void activeAllocationDoesNotSerializeCancellations() throws Exception {
        quote(10,1);tx.executeWithoutResult(s->inventory.allocate(101L,1L,8));
        var release=new SeckillInventoryReturn(jdbc,mock(SeckillCache.class));
        var pool=Executors.newSingleThreadExecutor();
        try(var lock=ds.getConnection()) {
            lock.setAutoCommit(false);
            try(var statement=lock.createStatement()) {
                statement.executeQuery("select * from seckill_inventory_allocation where activity_id=101 for update");
                pool.submit(()->tx.executeWithoutResult(s->release.cancelledAfterReturn(101L))).get(3,TimeUnit.SECONDS);
                assertEquals(2,balance());
            } finally {lock.rollback();}
        } finally {pool.shutdownNow();}
    }
    @Test void pendingLedgerPreventsReturningSellableSupply() {
        quote(10,1);tx.executeWithoutResult(s->inventory.allocate(101L,1L,8));jdbc.update("insert into seckill_activity values(101,0,8)");
        var cache=mock(SeckillCache.class);when(cache.drained(101L)).thenReturn(false);var release=new SeckillInventoryReturn(jdbc,cache);
        assertThrows(OrderBusinessException.class,()->tx.execute(s->release.closeout(101L)));assertEquals(2,balance());
        assertEquals("ALLOCATED",jdbc.queryForObject("select state from seckill_inventory_allocation",String.class));
    }
}
