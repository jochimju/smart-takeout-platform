package com.sky.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.cloud.openfeign.FeignAutoConfiguration;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.io.HttpClientConnectionManager;
import static org.assertj.core.api.Assertions.assertThat;

class FeignPoolTest {
    @Test void actualFeignCallsReuseAnHttpConnection() throws Exception {
        var ports=new java.util.HashSet<Integer>();
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{synchronized(ports){ports.add(exchange.getRemoteAddress().getPort());}
            var body="ok".getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,body.length);
            try(var output=exchange.getResponseBody()){output.write(body);} });server.start();
        try {
            new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(FeignAutoConfiguration.class))
                .withUserConfiguration(FeignPoolConfiguration.class).run(context->{
                    var client=context.getBean(feign.Client.class);
                    for(int i=0;i<5;i++) {
                        var request=feign.Request.create(feign.Request.HttpMethod.GET,"http://127.0.0.1:"+server.getAddress().getPort()+"/",
                            java.util.Map.of(),(byte[])null,java.nio.charset.StandardCharsets.UTF_8);
                        try(var response=client.execute(request,new feign.Request.Options(1000,1000))) {
                            assertThat(new String(response.body().asInputStream().readAllBytes())).isEqualTo("ok");
                        } catch(java.io.IOException failure){throw new AssertionError(failure);}
                    }
                    assertThat(ports).hasSize(1);
                });
        } finally {server.stop(0);}
    }
    @Test void autoConfigurationSelectsHC5AndAppliesTheBoundedPool() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(FeignAutoConfiguration.class))
            .withUserConfiguration(FeignPoolConfiguration.class)
            .withPropertyValues("spring.cloud.openfeign.httpclient.hc5.enabled=true",
                "spring.cloud.openfeign.httpclient.max-connections=512",
                "spring.cloud.openfeign.httpclient.max-connections-per-route=256",
                "spring.cloud.openfeign.httpclient.hc5.connection-request-timeout=1",
                "spring.cloud.openfeign.httpclient.hc5.connection-request-timeout-unit=seconds")
            .run(context->{
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(feign.Client.class)).isInstanceOf(feign.hc5.ApacheHttp5Client.class);
                var pool=(PoolingHttpClientConnectionManager)context.getBean(HttpClientConnectionManager.class);
                assertThat(pool.getMaxTotal()).isEqualTo(512);
                assertThat(pool.getDefaultMaxPerRoute()).isEqualTo(256);
            });
    }
}
