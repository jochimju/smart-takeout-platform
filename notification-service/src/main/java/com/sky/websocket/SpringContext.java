package com.sky.websocket;

import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

/** Gives JSR-356 endpoint configurators access to the Spring-managed JWT configuration. */
@Component
public class SpringContext implements ApplicationContextAware {
    private static ApplicationContext context;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        context = applicationContext;
    }

    public static ApplicationContext get() {
        if (context == null) throw new IllegalStateException("Spring context is not ready");
        return context;
    }
}
