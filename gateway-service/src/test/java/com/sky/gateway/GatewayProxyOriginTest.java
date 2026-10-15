package com.sky.gateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.boot.context.properties.bind.*;
import org.springframework.core.env.*;
import org.springframework.cloud.gateway.config.GatewayProperties;
import static org.junit.jupiter.api.Assertions.*;
class GatewayProxyOriginTest {
 @Test void deployedRoutesPreserveFrontendHostInsteadOfBackendHost() {
  var yaml=new YamlPropertiesFactoryBean();yaml.setResources(new ClassPathResource("application.yml"));
  var env=new StandardEnvironment();env.getPropertySources().addFirst(new PropertiesPropertySource("gateway-yaml",yaml.getObject()));
  var config=Binder.get(env).bind("spring.cloud.gateway.server.webflux",Bindable.of(GatewayProperties.class)).get();
  assertTrue(config.getDefaultFilters().stream().anyMatch(filter->filter.getName().equals("PreserveHostHeader")));
 }
}
