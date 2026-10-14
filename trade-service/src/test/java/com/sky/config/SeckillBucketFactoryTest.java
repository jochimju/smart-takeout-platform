package com.sky.config;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
class SeckillBucketFactoryTest {
 @Test void bucketFactoryHasOneConsumerAndIsIndependentOfLegacyFactory() {
  new ApplicationContextRunner().withUserConfiguration(SeckillAsyncConfiguration.class)
   .withBean(ConnectionFactory.class,()->mock(ConnectionFactory.class))
   .withBean(MessageConverter.class,()->mock(MessageConverter.class)).run(context->{
    assertThat(context).hasNotFailed();
    var bucket=context.getBean("seckillBucketListenerFactory",SimpleRabbitListenerContainerFactory.class);
    var legacy=context.getBean("seckillBatchListenerFactory",SimpleRabbitListenerContainerFactory.class);
    assertThat(bucket).isNotSameAs(legacy);
    assertThat(ReflectionTestUtils.getField(bucket,"concurrentConsumers")).isEqualTo(1);
    assertThat(ReflectionTestUtils.getField(legacy,"concurrentConsumers")).isEqualTo(3);
   });
 }
}
