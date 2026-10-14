package com.sky.service.impl;

import com.alibaba.fastjson.JSON;
import com.sky.dto.SeckillOrderMessageDTO;
import com.sky.dto.SeckillOrderSubmitDTO;
import com.sky.entity.SeckillActivity;
import com.sky.exception.OrderBusinessException;
import com.sky.mapper.SeckillOrderGuardMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real Redis/Lua, isolated keys. Run with -Dseckill.integration=true -Dseckill.redis.port=16479. */
@EnabledIfSystemProperty(named="seckill.integration",matches="true")
class SeckillStockIntegrationTest {
    static LettuceConnectionFactory connection;
    static StringRedisTemplate redis;
    static SeckillCache cache;
    static String prefix;
    @BeforeAll static void setup() {
        connection=new LettuceConnectionFactory("127.0.0.1",Integer.getInteger("seckill.redis.port",16479));
        connection.afterPropertiesSet();
        redis=new StringRedisTemplate(connection);
        cache=new SeckillCache();
        prefix="seckill_test_"+UUID.randomUUID()+":";
        ReflectionTestUtils.setField(cache,"redis",redis);
        ReflectionTestUtils.setField(cache,"guards",mock(SeckillOrderGuardMapper.class));
        ReflectionTestUtils.setField(cache,"prefix",prefix);
        ReflectionTestUtils.setField(cache,"maxPending",10000);
    }
    @AfterAll static void cleanup() { if(redis!=null) redis.delete(cache.keys(1L)); if(connection!=null) connection.destroy(); }
    @BeforeEach void reset() {
        redis.delete(cache.keys(1L));
        SeckillActivity a=new SeckillActivity(); a.setId(1L);a.setSetmealId(48L);a.setStatus(1);
        a.setBeginTime(LocalDateTime.now().minusHours(1));a.setEndTime(LocalDateTime.now().plusHours(1));
        a.setSeckillPrice(new BigDecimal("8.90"));a.setRemainingStock(20);
        cache.initialize(a);cache.enable(1L);
    }
    SeckillOrderMessageDTO command(long user,String request,String number) {
        SeckillOrderSubmitDTO r=new SeckillOrderSubmitDTO();r.setActivityId(1L);r.setSetmealId(48L);r.setAddressBookId(user);r.setRequestId(request);
        return SeckillOrderMessageDTO.builder().activityId(1L).setmealId(48L).userId(user).orderNumber(number).request(r).requestHash("hash").build();
    }
    void parallel(int count,java.util.function.IntConsumer action) throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(16);
        try { List<Future<?>> jobs=new ArrayList<>(); for(int i=0;i<count;i++){int n=i;jobs.add(pool.submit(()->action.accept(n)));} for(var job:jobs) job.get(30,TimeUnit.SECONDS); }
        finally {pool.shutdownNow();}
    }
    void initializeBuckets(int count,int stock) {
        redis.delete(cache.keys(1L));var a=new SeckillActivity();a.setId(1L);a.setSetmealId(48L);a.setStatus(1);a.setBucketCount(count);
        a.setBeginTime(LocalDateTime.now().minusHours(1));a.setEndTime(LocalDateTime.now().plusHours(1));
        a.setSeckillPrice(new BigDecimal("8.90"));a.setRemainingStock(stock);cache.initialize(a);cache.enable(1L);
    }
    @Test void bucketReservationsRemainGloballyUniqueAndCannotOversell() throws Exception {
        initializeBuckets(8,20);var accepted=new AtomicInteger();
        parallel(100,i->{try{cache.accept(command(i+1,"bucket_req"+i,"SKbucket"+i));accepted.incrementAndGet();}catch(OrderBusinessException e){assertEquals("库存不足",e.getMessage());}});
        assertEquals(20,accepted.get());assertEquals(0,cache.stock(1L));
        assertEquals(0,redis.opsForHash().values(cache.keys(1L).get(7)).stream().mapToInt(n->Integer.parseInt(n.toString())).sum());
        for(var pending:cache.pending(1L).values())assertEquals(8,JSON.parseObject(pending).getIntValue("bucketCount"));
    }
    @Test void bucketCompensationRejectsWrongBucketAndRestoresExactlyOnce() throws Exception {
        initializeBuckets(8,20);cache.accept(command(9007199254740993L,"bucket_req","SKbucket"));
        int bucket=JSON.parseObject(cache.pending(1L).get("SKbucket")).getIntValue("bucketId");assertEquals(1,bucket);
        assertThrows(IllegalStateException.class,()->cache.finish(1L,9007199254740993L,"bucket_req","SKbucket","FAILED","",(bucket+1)%8));assertEquals(19,cache.stock(1L));
        parallel(20,i->cache.finish(1L,9007199254740993L,"bucket_req","SKbucket","FAILED","",bucket));assertEquals(20,cache.stock(1L));
        assertEquals(20,redis.opsForHash().values(cache.keys(1L).get(7)).stream().mapToInt(n->Integer.parseInt(n.toString())).sum());
    }
    @Test void corruptedBucketLedgerRejectsWithoutAnyPartialDebit() {
        initializeBuckets(8,20);redis.opsForHash().put(cache.keys(1L).get(7),"0","bad");
        assertThrows(OrderBusinessException.class,()->cache.accept(command(1,"bucket_req","SKbucket")));assertEquals(20,cache.stock(1L));assertTrue(cache.pending(1L).isEmpty());assertEquals(0L,redis.opsForHash().size(cache.keys(1L).get(6)));
    }
    @Test void bulkHandoffValidationChecksEveryRequestIdentityAndFingerprint() {
        var valid=command(1,"bulk1","SKbulk1");var badHash=command(2,"bulk2","SKbulk2");var missing=command(3,"bulk3","SKbulk3");
        cache.accept(valid);cache.accept(badHash);badHash.setRequestHash("tampered");
        var records=cache.findBatch(1L,List.of(valid,badHash,missing));assertEquals(Set.of("SKbulk1"),records.keySet());
        assertEquals("SKbulk1",records.get("SKbulk1").getOrderNumber());assertEquals(18,cache.stock(1L));
    }
    @Test void pipelinedResultsKeepFailedItemsAndRemainIdempotentAfterScriptLoss() {
        initializeBuckets(8,20);cache.accept(command(1,"pipe1","SKpipe1"));cache.accept(command(2,"pipe2","SKpipe2"));
        var events=List.<Map<String,Object>>of(Map.of("activity_id",1L,"user_id",1L,"request_id","pipe1","order_number","SKpipe1","state","FAILED","bucket_id",1),
            Map.of("activity_id",1L,"user_id",2L,"request_id","pipe2","order_number","SKpipe2","state","FAILED","bucket_id",3));
        assertEquals(List.of(true,false),cache.finishBatch(events));assertEquals(19,cache.stock(1L));
        assertEquals(List.of(true,false),cache.finishBatch(events));assertEquals(19,cache.stock(1L));
        redis.execute((org.springframework.data.redis.core.RedisCallback<Object>)connection->{connection.scriptingCommands().scriptFlush();return null;});
        assertThrows(org.springframework.dao.DataAccessException.class,()->cache.finishBatch(events));
        assertEquals(List.of(true,false),cache.finishBatch(events));assertEquals(19,cache.stock(1L));
    }
    @Test void identicalUserCannotBuyAcrossDifferentBuckets() throws Exception {
        initializeBuckets(8,20);var accepted=new AtomicInteger();
        parallel(30,i->{try{cache.accept(command(1,"bucket_req"+i,"SKbucket"+i));accepted.incrementAndGet();}catch(OrderBusinessException e){assertEquals("您已抢购过该秒杀活动",e.getMessage());}});
        assertEquals(1,accepted.get());assertEquals(19,cache.stock(1L));
    }
    @Test void hundredBuyersOnlyReserveTwentyAndEveryReservationHasATask() throws Exception {
        var script=(org.springframework.data.redis.core.script.DefaultRedisScript<?>)ReflectionTestUtils.getField(SeckillCache.class,"ACCEPT");
        assertTrue(script.getScriptAsString().contains("库存不足"));
        assertEquals("库存不足",redis.execute(new org.springframework.data.redis.core.script.DefaultRedisScript<>("return '库存不足'",String.class),List.of()));
        AtomicInteger accepted=new AtomicInteger();
        parallel(100,i->{try {cache.accept(command(i+1,"request_"+i,"SK"+i));accepted.incrementAndGet();}
            catch(OrderBusinessException e){assertEquals("库存不足",e.getMessage());}});
        assertEquals(20,accepted.get());assertEquals(0,cache.stock(1L));assertEquals(20,cache.pending(1L).size());
        assertEquals(20,redis.opsForHash().size(cache.keys(1L).get(2)));
    }
    @Test void outstandingLimitSurvivesBridgeAndReleasesOnlyOnTerminalResult() {
        ReflectionTestUtils.setField(cache,"maxOutstanding",2);
        try {
            cache.accept(command(1,"req1","SK1"));cache.accept(command(2,"req2","SK2"));
            assertEquals("SK1",cache.accept(command(1,"req1","SKreplacement")).getOrderNumber());
            cache.handedOffBatch(1L,List.of("SK1","SK2"));assertTrue(cache.pending(1L).isEmpty());
            assertThrows(OrderBusinessException.class,()->cache.accept(command(3,"req3","SK3")));
            assertEquals(18,cache.stock(1L));
            cache.finish(1L,1L,"req1","SK1","SUCCEEDED",null);
            cache.finish(1L,1L,"req1","SK1","SUCCEEDED",null);
            cache.accept(command(3,"req3","SK3"));assertEquals(17,cache.stock(1L));
            assertEquals(2L,redis.opsForHash().size(cache.keys(1L).get(6)));
            assertEquals("2",redis.opsForHash().get(cache.keys(1L).get(0),"outstandingCount"));
        } finally {ReflectionTestUtils.setField(cache,"maxOutstanding",500);}
    }
    @Test void lostOutstandingLedgerFailsClosedInsteadOfResettingCapacity() {
        cache.accept(command(1,"req1","SK1"));redis.delete(cache.keys(1L).get(6));
        assertThrows(OrderBusinessException.class,()->cache.accept(command(2,"req2","SK2")));
        assertEquals(19,cache.stock(1L));
    }
    @Test void concurrentOutstandingAdmissionNeverExceedsItsLimit() throws Exception {
        ReflectionTestUtils.setField(cache,"maxOutstanding",3);var accepted=new AtomicInteger();
        try {
            parallel(40,user->{try{cache.accept(command(user+1,"req"+user,"SK"+user));accepted.incrementAndGet();}
                catch(OrderBusinessException rejection){assertEquals("秒杀建单繁忙，请稍后重试",rejection.getMessage());}});
            assertEquals(3,accepted.get());assertEquals(17,cache.stock(1L));
            assertEquals("3",redis.opsForHash().get(cache.keys(1L).get(0),"outstandingCount"));
        } finally {ReflectionTestUtils.setField(cache,"maxOutstanding",500);}
    }
    @Test void duplicateRequestReturnsOriginalNumberWithoutAnotherDebit() throws Exception {
        Set<String> numbers=ConcurrentHashMap.newKeySet();
        parallel(40,i->numbers.add(cache.accept(command(1,"same_request","SK"+i)).getOrderNumber()));
        assertEquals(1,numbers.size());assertEquals(19,cache.stock(1L));assertEquals(1,cache.pending(1L).size());
        assertThrows(OrderBusinessException.class,()->cache.accept(command(1,"new_request","SKnew")));
        var conflict=command(1,"same_request","SKconflict");conflict.setRequestHash("different");
        assertThrows(OrderBusinessException.class,()->cache.accept(conflict));
    }
    @Test void compensationOnceAndOldCompensationCannotReleaseNewReservation() throws Exception {
        cache.accept(command(1,"first","SKfirst"));
        parallel(30,i->cache.finish(1L,1L,"first","SKfirst","FAILED","business rejection"));
        assertEquals(20,cache.stock(1L));
        cache.accept(command(1,"second","SKsecond"));
        cache.finish(1L,1L,"first","SKfirst","FAILED","duplicate");
        assertEquals(19,cache.stock(1L));assertEquals("SKsecond",redis.opsForHash().get(cache.keys(1L).get(2),"1"));
    }
    @Test void initializationNeverOverwritesPendingDebitAndLossFailsClosed() {
        cache.accept(command(1,"request","SK1"));
        SeckillActivity a=new SeckillActivity();a.setId(1L);a.setSetmealId(48L);a.setStatus(1);a.setRemainingStock(20);
        a.setBeginTime(LocalDateTime.now().minusHours(1));a.setEndTime(LocalDateTime.now().plusHours(1));a.setSeckillPrice(BigDecimal.TEN);
        cache.initialize(a);assertEquals(19,cache.stock(1L));
        redis.delete(cache.keys(1L).get(1));
        assertThrows(OrderBusinessException.class,()->cache.accept(command(2,"other","SK2")));
        assertThrows(OrderBusinessException.class,()->cache.enable(1L));
        assertEquals(1,cache.pending(1L).size());
    }
    @Test void metadataAndBacklogRejectWithoutDebit() {
        var meta=cache.keys(1L).get(0);
        redis.opsForHash().put(meta,"begin",String.valueOf(System.currentTimeMillis()+60000));
        assertThrows(OrderBusinessException.class,()->cache.accept(command(1,"early","SK1")));
        redis.opsForHash().put(meta,"begin","0");redis.opsForHash().put(meta,"end","1");
        assertThrows(OrderBusinessException.class,()->cache.accept(command(1,"late","SK1")));
        redis.opsForHash().put(meta,"end",String.valueOf(System.currentTimeMillis()+60000));
        cache.pause(1L);assertThrows(OrderBusinessException.class,()->cache.accept(command(1,"paused","SK1")));
        redis.opsForHash().put(meta,"status","1");
        var wrong=command(1,"wrong","SK1");wrong.setSetmealId(49L);
        assertThrows(OrderBusinessException.class,()->cache.accept(wrong));
        ReflectionTestUtils.setField(cache,"maxPending",1);
        cache.accept(command(1,"first","SKfirst"));
        assertThrows(OrderBusinessException.class,()->cache.accept(command(2,"second","SKsecond")));
        assertEquals(19,cache.stock(1L));ReflectionTestUtils.setField(cache,"maxPending",10000);
    }
    @Test void taskPreservesLargeLongIdsAndServerPrice() {
        long user=9007199254740993L;
        cache.accept(command(user,"large","SKlarge"));
        var envelope=JSON.parseObject(cache.pending(1L).get("SKlarge"));
        var task=JSON.parseObject(envelope.getString("command"),SeckillOrderMessageDTO.class);
        assertEquals(user,task.getUserId());assertEquals(new BigDecimal("8.90"),envelope.getBigDecimal("price"));
        assertNotNull(envelope.getLong("acceptedAt"));
    }
}
