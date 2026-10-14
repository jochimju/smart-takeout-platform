package com.sky.service.mq;
import com.sky.constant.MqConstant;
import com.sky.entity.MqFailMessage;
import com.sky.task.MqFailMessageRetryTask;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
@EnabledIfSystemProperty(named="order.rabbit",matches="true")
class TimeoutRabbitIntegrationTest {
 @Test void realBrokerExpiresRemainingTtlAndDeadLettersToCloseQueue()throws Exception {
  var yaml=new YamlPropertiesFactoryBean();yaml.setResources(new ClassPathResource("application-dev.yml"));var p=yaml.getObject();
  String host=p.getProperty("sky.rabbitmq.host"),user=p.getProperty("sky.rabbitmq.username"),password=p.getProperty("sky.rabbitmq.password"),vhost="sky_arch_timeout_"+UUID.randomUUID().toString().replace("-","");
  String auth="Basic "+Base64.getEncoder().encodeToString((user+":"+password).getBytes(StandardCharsets.UTF_8));
  var client=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();String url="http://"+host+":15672/api";
  CachingConnectionFactory connection=null;
  try {
   int status=client.send(HttpRequest.newBuilder(URI.create(url+"/vhosts/"+vhost)).header("Authorization",auth).PUT(HttpRequest.BodyPublishers.ofString("{}")).build(),HttpResponse.BodyHandlers.discarding()).statusCode();assertTrue(status==201||status==204);
   client.send(HttpRequest.newBuilder(URI.create(url+"/permissions/"+vhost+"/"+URLEncoder.encode(user,StandardCharsets.UTF_8))).header("Authorization",auth).header("Content-Type","application/json").PUT(HttpRequest.BodyPublishers.ofString("{\"configure\":\".*\",\"write\":\".*\",\"read\":\".*\"}")).build(),HttpResponse.BodyHandlers.discarding());
   connection=new CachingConnectionFactory(host,Integer.parseInt(p.getProperty("sky.rabbitmq.port")));connection.setUsername(user);connection.setPassword(password);connection.setVirtualHost(vhost);connection.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);connection.setPublisherReturns(true);
   var rabbit=new RabbitTemplate(connection);rabbit.setMessageConverter(new Jackson2JsonMessageConverter());rabbit.setMandatory(true);var admin=new RabbitAdmin(connection);
   var exchange=new DirectExchange(MqConstant.ORDER_DELAY_EXCHANGE,true,false);var dead=new org.springframework.amqp.core.Queue(MqConstant.ORDER_DEAD_QUEUE,true);
   var delay=QueueBuilder.durable(MqConstant.ORDER_DELAY_QUEUE).ttl(900000).deadLetterExchange(MqConstant.ORDER_DELAY_EXCHANGE).deadLetterRoutingKey(MqConstant.ORDER_DEAD_ROUTING_KEY).build();
   admin.declareExchange(exchange);admin.declareQueue(dead);admin.declareQueue(delay);
   admin.declareBinding(BindingBuilder.bind(delay).to(exchange).with(MqConstant.ORDER_DELAY_ROUTING_KEY));admin.declareBinding(BindingBuilder.bind(dead).to(exchange).with(MqConstant.ORDER_DEAD_ROUTING_KEY));
   for(var declaration:TimeoutRouting.declarations(exchange).getDeclarables()) {
    if(declaration instanceof org.springframework.amqp.core.Queue q)admin.declareQueue(q);
    if(declaration instanceof Binding b)admin.declareBinding(b);
   }
   var store=mock(SeckillPublishStore.class);var task=new MqFailMessageRetryTask();ReflectionTestUtils.setField(task,"rabbitTemplate",rabbit);ReflectionTestUtils.setField(task,"seckillPublishStore",store);
   when(store.claim(true,200)).thenReturn(List.of(MqFailMessage.builder().id(1L).exchangeName(MqConstant.ORDER_DELAY_EXCHANGE).routingKey(MqConstant.SECKILL_TIMEOUT_ROUTING_KEY).messageBody("\"SKttl\"").deliveryDeadline(LocalDateTime.now().plusSeconds(2)).build()));
   long started=System.nanoTime();task.publishSeckillTimeouts();assertNull(rabbit.receiveAndConvert(MqConstant.ORDER_DEAD_QUEUE,200));
   assertEquals("SKttl",rabbit.receiveAndConvert(MqConstant.ORDER_DEAD_QUEUE,6000));assertTrue(Duration.ofNanos(System.nanoTime()-started).toMillis()>=1000);
   // A long timeout was enqueued first: the short one must not wait behind it.
   rabbit.convertAndSend(exchange.getName(),TimeoutRouting.route(5000),"long-first");
   rabbit.convertAndSend(exchange.getName(),TimeoutRouting.route(1000),"short-second");
   assertEquals("short-second",rabbit.receiveAndConvert(MqConstant.ORDER_DEAD_QUEUE,3000));
   assertEquals("long-first",rabbit.receiveAndConvert(MqConstant.ORDER_DEAD_QUEUE,7000));
   var retry=new org.springframework.amqp.core.Queue("fault.redelivery",true);admin.declareQueue(retry);
   rabbit.convertAndSend("","fault.redelivery","same-business-command");
   rabbit.execute(channel->{var first=channel.basicGet("fault.redelivery",false);assertNotNull(first);
    channel.basicNack(first.getEnvelope().getDeliveryTag(),false,true);
    var repeated=channel.basicGet("fault.redelivery",false);assertNotNull(repeated);assertTrue(repeated.getEnvelope().isRedeliver());
    assertArrayEquals(first.getBody(),repeated.getBody());channel.basicAck(repeated.getEnvelope().getDeliveryTag(),false);return null;});
  } finally {
   if(connection!=null)connection.destroy();
   client.send(HttpRequest.newBuilder(URI.create(url+"/vhosts/"+vhost)).header("Authorization",auth).DELETE().build(),HttpResponse.BodyHandlers.discarding());
  }
 }
}
