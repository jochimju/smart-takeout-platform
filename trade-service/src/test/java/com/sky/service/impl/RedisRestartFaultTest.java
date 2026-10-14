package com.sky.service.impl;
import com.sky.dto.*;
import com.sky.entity.SeckillActivity;
import com.sky.mapper.*;
import com.sky.exception.OrderBusinessException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.net.ServerSocket;
import java.time.*;
import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="redis.restart.integration",matches="true")
class RedisRestartFaultTest {
 Process process;Path folder;int port;LettuceConnectionFactory connection;StringRedisTemplate redis;SeckillCache cache;
 @BeforeEach void setup()throws Exception {
  Path root=Path.of("..",".runtime","fault-redis").toAbsolutePath().normalize();Files.createDirectories(root);
  folder=Files.createTempDirectory(root,"restart-");try(var socket=new ServerSocket(0)){port=socket.getLocalPort();}
 }
 void start(boolean persistent)throws Exception {
  Path executable=Path.of(System.getProperty("redis.restart.executable","../../Redis-x64-3.2.100/redis-server.exe")).toAbsolutePath().normalize();
  process=new ProcessBuilder(executable.toString(),"--bind","127.0.0.1","--port",String.valueOf(port),"--dir",folder.toString(),"--save","",
   "--appendonly",persistent?"yes":"no","--appendfsync","always").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(folder.resolve("redis.log").toFile())).start();
  connection=new LettuceConnectionFactory("127.0.0.1",port);connection.afterPropertiesSet();redis=new StringRedisTemplate(connection);
  boolean ready=false;for(int i=0;i<100;i++){try{redis.opsForValue().get("ready-probe");ready=true;break;}catch(Exception e){Thread.sleep(50);}}
  assertTrue(ready,"Isolated Redis did not start");cache=new SeckillCache();ReflectionTestUtils.setField(cache,"redis",redis);
  ReflectionTestUtils.setField(cache,"guards",mock(SeckillOrderGuardMapper.class));ReflectionTestUtils.setField(cache,"prefix","restart:test:");ReflectionTestUtils.setField(cache,"maxPending",100);
 }
 void crash()throws Exception {connection.destroy();process.destroyForcibly();assertTrue(process.waitFor(10,TimeUnit.SECONDS));}
 @AfterEach void cleanup()throws Exception {if(connection!=null)connection.destroy();if(process!=null&&process.isAlive()){process.destroyForcibly();process.waitFor(10,TimeUnit.SECONDS);}}
 SeckillActivity initialize() {
  var a=new SeckillActivity();a.setId(1L);a.setSetmealId(48L);a.setStatus(1);a.setRemainingStock(10);a.setSeckillPrice(BigDecimal.ONE);
  a.setBeginTime(LocalDateTime.now().minusMinutes(1));a.setEndTime(LocalDateTime.now().plusMinutes(30));cache.initialize(a);cache.enable(1L);return a;
 }
 SeckillOrderMessageDTO command() {var r=new SeckillOrderSubmitDTO();r.setActivityId(1L);r.setSetmealId(48L);r.setAddressBookId(1L);r.setRequestId("restart_request_01");
  return SeckillOrderMessageDTO.builder().activityId(1L).setmealId(48L).userId(1L).orderNumber("SKrestart").requestHash("hash").request(r).build();}
 @Test void durableRestartKeepsPredebitCommandAndRequestIdentity()throws Exception {
  start(true);initialize();cache.accept(command());assertEquals(9,cache.stock(1L));crash();start(true);
  assertEquals(9,cache.stock(1L));assertEquals(1,cache.pending(1L).size());assertEquals("SKrestart",cache.accept(command()).getOrderNumber());assertEquals(9,cache.stock(1L));
 }
 @Test void lostRedisStateFailsClosedInsteadOfReplenishingUnknownReservations()throws Exception {
  start(false);var activity=initialize();cache.accept(command());crash();start(false);activity.setAsyncInitialized(1);
  var mapper=mock(SeckillActivityMapper.class);when(mapper.lock(1L)).thenReturn(activity);
  var service=new SeckillServiceImpl();ReflectionTestUtils.setField(service,"seckillActivityMapper",mapper);ReflectionTestUtils.setField(service,"cache",cache);
  assertThrows(OrderBusinessException.class,()->service.warmUpActivity(1L));assertThrows(OrderBusinessException.class,()->cache.accept(command()));assertFalse(cache.ready(1L));
 }
}
