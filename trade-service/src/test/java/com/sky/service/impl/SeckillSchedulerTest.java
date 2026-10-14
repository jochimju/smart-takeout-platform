package com.sky.service.impl;

import com.sky.config.SeckillAsyncConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SeckillSchedulerTest {
    @Test void legacyAndSeckillSchedulersAreSeparate() {
        try(var context=new AnnotationConfigApplicationContext()) {
            context.registerBean(ConnectionFactory.class,()->mock(ConnectionFactory.class));
            context.registerBean(MessageConverter.class,()->mock(MessageConverter.class));
            context.register(SeckillAsyncConfiguration.class);context.refresh();
            var ordinary=context.getBean("taskScheduler",ThreadPoolTaskScheduler.class);
            var seckill=context.getBean("seckillScheduler",ThreadPoolTaskScheduler.class);
            assertNotSame(ordinary,seckill);assertEquals(1,ordinary.getScheduledThreadPoolExecutor().getCorePoolSize());assertEquals(5,seckill.getScheduledThreadPoolExecutor().getCorePoolSize());
            var cache=context.getBean("seckillCacheScheduler",ThreadPoolTaskScheduler.class);
            assertNotSame(seckill,cache);assertNotSame(ordinary,cache);
            assertEquals(1,cache.getScheduledThreadPoolExecutor().getCorePoolSize());
            assertNotNull(context.getBean("seckillListenerFactory"));
            assertNotSame(context.getBean("seckillListenerFactory"),context.getBean("seckillBatchListenerFactory"));
        }
    }
}
