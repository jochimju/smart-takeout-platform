package com.sky.config;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods=false)
public class SeckillAsyncConfiguration {
    @Bean public org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler normalPublisherScheduler() {
        var scheduler=new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        scheduler.setPoolSize(3);scheduler.setThreadNamePrefix("normal-publish-");return scheduler;
    }
    // Keep unnamed legacy @Scheduled jobs on their own scheduler.
    @Bean(name="taskScheduler")
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(name="taskScheduler")
    public org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler taskScheduler(
        @Value("${spring.task.scheduling.pool.size:1}") int size) {
        var scheduler=new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        scheduler.setPoolSize(size);scheduler.setThreadNamePrefix("scheduling-");return scheduler;
    }

    @Bean public org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler seckillCacheScheduler() {
        var scheduler=new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);scheduler.setThreadNamePrefix("seckill-cache-");scheduler.setRemoveOnCancelPolicy(true);return scheduler;
    }

    @Bean
    public org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler seckillScheduler() {
        var scheduler=new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        scheduler.setPoolSize(5); scheduler.setThreadNamePrefix("seckill-worker-");
        scheduler.setRemoveOnCancelPolicy(true);return scheduler;
    }

    @Bean
    public SimpleRabbitListenerContainerFactory seckillListenerFactory(ConnectionFactory connection,
        MessageConverter converter,@Value("${sky.seckill.consumer-concurrency:3}") int concurrency,
        @Value("${sky.seckill.consumer-prefetch:10}") int prefetch) {
        return listenerFactory(connection,converter,concurrency,prefetch);
    }
    private SimpleRabbitListenerContainerFactory listenerFactory(ConnectionFactory connection,MessageConverter converter,int concurrency,int prefetch) {
        SimpleRabbitListenerContainerFactory factory=new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connection);
        factory.setMessageConverter(converter);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setConcurrentConsumers(concurrency);
        factory.setMaxConcurrentConsumers(concurrency);
        factory.setPrefetchCount(prefetch);
        factory.setObservationEnabled(true);
        return factory;
    }

    @Bean public SimpleRabbitListenerContainerFactory seckillBucketListenerFactory(ConnectionFactory connection,
        MessageConverter converter,@Value("${sky.seckill.fulfill-batch-size:8}") int size,
        @Value("${sky.seckill.fulfill-batch-wait-ms:50}") long wait) {
        return seckillBatchListenerFactory(connection,converter,1,16,size,wait);
    }

    @Bean public SimpleRabbitListenerContainerFactory seckillBatchListenerFactory(ConnectionFactory connection,
        MessageConverter converter,@Value("${sky.seckill.consumer-concurrency:3}") int concurrency,
        @Value("${sky.seckill.consumer-prefetch:10}") int prefetch,@Value("${sky.seckill.fulfill-batch-size:8}") int size,
        @Value("${sky.seckill.fulfill-batch-wait-ms:50}") long wait) {
        if(size<1 || size>16 || wait<1 || wait>1000)throw new IllegalArgumentException("Invalid small-batch configuration");
        var factory=listenerFactory(connection,converter,concurrency,Math.max(prefetch,size));
        factory.setBatchListener(true);factory.setConsumerBatchEnabled(true);factory.setBatchSize(size);
        factory.setReceiveTimeout(wait);factory.setBatchReceiveTimeout(wait);return factory;
    }
}
