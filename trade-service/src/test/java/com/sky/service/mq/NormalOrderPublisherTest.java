package com.sky.service.mq;
import com.sky.task.NormalOrderPublisherTask;
import com.sky.entity.MqFailMessage;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.core.MessagePostProcessor;
import java.util.List;
import static org.mockito.Mockito.*;
class NormalOrderPublisherTest {
 @Test void acceptedCommandDoesNotPublishInCallingThread() {
  var rabbit=mock(RabbitTemplate.class);var mapper=mock(com.sky.mapper.MqFailMessageMapper.class);
  new OrderMessagePublisher(rabbit,mapper).submitAfterCommit(com.sky.dto.OrderSubmitMessageDTO.builder().orderNumber("normal").build());
  verify(mapper).insert(any(MqFailMessage.class));verifyNoInteractions(rabbit);
 }
 @Test void confirmationDatabaseFailureIsRetainedUntilSuccessfulSettlement() {
  var rabbit=mock(RabbitTemplate.class);var store=mock(SeckillPublishStore.class);var task=new NormalOrderPublisherTask(store,rabbit);
  when(store.claimNormal(100)).thenReturn(List.of(MqFailMessage.builder().id(1L).claimToken("claim").exchangeName("orders").routingKey("order.submit").messageBody("{}").build()));
  doAnswer(call->{((CorrelationData)call.getArgument(4)).getFuture().complete(new CorrelationData.Confirm(true,null));return null;})
   .when(rabbit).convertAndSend(anyString(),anyString(),any(),any(MessagePostProcessor.class),any(CorrelationData.class));
  doThrow(new IllegalStateException("database offline")).doNothing().when(store).complete(anyList());
  task.publish();task.settle();task.settle();task.settle();verify(store,times(2)).complete(argThat(rows->rows.size()==1 && rows.get(0).acknowledged() && "claim".equals(rows.get(0).token())));
 }
}
