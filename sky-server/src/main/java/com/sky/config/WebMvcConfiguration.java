package com.sky.config;

import com.sky.interceptor.JwtTokenAdminInterceptor;
import com.sky.interceptor.JwtTokenUserInterceptor;
import com.sky.json.JacksonObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurationSupport;
import springfox.documentation.builders.ApiInfoBuilder;
import springfox.documentation.builders.PathSelectors;
import springfox.documentation.builders.RequestHandlerSelectors;
import springfox.documentation.service.ApiInfo;
import springfox.documentation.spi.DocumentationType;
import springfox.documentation.spring.web.plugins.Docket;

import java.util.List;

/**
 * 闁板秶鐤嗙猾浼欑礉濞夈劌鍞絯eb鐏炲倻娴夐崗宕囩矋娴?
 */
@Configuration
@Slf4j
public class WebMvcConfiguration extends WebMvcConfigurationSupport {

    @Autowired
    private JwtTokenAdminInterceptor jwtTokenAdminInterceptor;

    @Autowired
    private JwtTokenUserInterceptor jwtTokenUserInterceptor;

    /**
     * 濞夈劌鍞介懛顏勭暰娑斿瀚ら幋顏勬珤
     *
     * @param registry
     */
    protected void addInterceptors(InterceptorRegistry registry) {
        log.info("瀵偓婵鏁為崘宀冨殰鐎规矮绠熼幏锔藉焻閸?..");
        registry.addInterceptor(jwtTokenAdminInterceptor)
                .addPathPatterns("/admin/**")
                .excludePathPatterns("/admin/employee/login");
        registry.addInterceptor(jwtTokenUserInterceptor)
                .addPathPatterns("/user/**")
                .excludePathPatterns("/user/user/login")
                .excludePathPatterns("/user/shop/status")
                .excludePathPatterns("/user/seckill/activity/list");
    }

    /**
     * 闁俺绻僰nife4j閻㈢喐鍨氶幒銉ュ經閺傚洦銆?
     * @return
     */
    @Bean
    public Docket docket() {
        ApiInfo apiInfo = new ApiInfoBuilder()
                .title("Sky Take Out API")
                .version("2.0")
                .description("Sky Take Out API documentation")
                .build();
        Docket docket = new Docket(DocumentationType.SWAGGER_2)
                .apiInfo(apiInfo)
                .select()
                .apis(RequestHandlerSelectors.basePackage("com.sky.controller"))
                .paths(PathSelectors.any())
                .build();
        return docket;
    }

    /**
     * 鐠佸墽鐤嗛棃娆愨偓浣界カ濠ф劖妲х亸?
     * @param registry
     */
    protected void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/doc.html").addResourceLocations("classpath:/META-INF/resources/");
        registry.addResourceHandler("/webjars/**").addResourceLocations("classpath:/META-INF/resources/webjars/");
    }
    /**
     * 閹碘晛鐫峉pring MVC濡楀棙鐏﹂惃鍕Х閹垵娴嗛崠鏍ф珤
     * @param converters
     */
    protected void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        log.info("閹碘晛鐫嶅☉鍫熶紖鏉烆剚宕查崳?..");
        //閸掓稑缂撴稉鈧稉顏呯Х閹垵娴嗛幑銏犳珤鐎电钖?
        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter();
        //闂団偓鐟曚椒璐熷☉鍫熶紖鏉烆剚宕查崳銊啎缂冾喕绔存稉顏勵嚠鐠灺ゆ祮閹广垹娅掗敍灞筋嚠鐠灺ゆ祮閹广垹娅掗崣顖欎簰鐏忓捈ava鐎电钖勬惔蹇撳灙閸栨牔璐焜son閺佺増宓?
        converter.setObjectMapper(new JacksonObjectMapper());//JacksonObjectMapper瀹歌尙绮￠崘娆忋偨娴滃棴绱濋柈鑺ユЦ閸ュ搫鐣鹃惃鍕敩閻降鈧倸褰х憰浣虹叀闁挷鍞惍浣烘畱娴ｆ粎鏁ょ亸杈攽娴滃棎鈧倷绗夐悽銊ょ窗閸?
        //鐏忓棜鍤滃杈╂畱濞戝牊浼呮潪顒€瀵查崳銊ュ閸忋儱顔愰崳銊よ厬
        converters.add(0,converter); //0閺勵垰绨崚妤嬬礉鐏忚鲸妲哥拠缈犵喘閸忓牅濞囬悽銊﹀灉娴狀剝鍤滃鍗炵暰娑斿娈戞潪顒佸床閸?
    }
    @Bean
    public Docket docket1(){
        log.info("閸戝棗顦悽鐔稿灇閹恒儱褰涢弬鍥ㄣ€?..");
        ApiInfo apiInfo = new ApiInfoBuilder()
                .title("Sky Take Out API")
                .version("2.0")
                .description("Sky Take Out API documentation")
                .build();

        Docket docket = new Docket(DocumentationType.SWAGGER_2)
                .groupName("admin api")
                .apiInfo(apiInfo)
                .select()
                //閹稿洤鐣鹃悽鐔稿灇閹恒儱褰涢棁鈧憰浣瑰閹诲繒娈戦崠?
                .apis(RequestHandlerSelectors.basePackage("com.sky.controller.admin"))
                .paths(PathSelectors.any())
                .build();

        return docket;
    }

    @Bean
    public Docket docket2(){
        log.info("閸戝棗顦悽鐔稿灇閹恒儱褰涢弬鍥ㄣ€?..");
        ApiInfo apiInfo = new ApiInfoBuilder()
                .title("Sky Take Out API")
                .version("2.0")
                .description("Sky Take Out API documentation")
                .build();

        Docket docket = new Docket(DocumentationType.SWAGGER_2)
                .groupName("user api")
                .apiInfo(apiInfo)
                .select()
                //閹稿洤鐣鹃悽鐔稿灇閹恒儱褰涢棁鈧憰浣瑰閹诲繒娈戦崠?
                .apis(RequestHandlerSelectors.basePackage("com.sky.controller.user"))
                .paths(PathSelectors.any())
                .build();

        return docket;
    }

}
