package com.sky.account;

import com.sky.AccountApplication;
import com.sky.utils.JwtUtil;
import com.sky.utils.properties.JwtProperties;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.DriverManager;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real Spring Security chain + controllers + MyBatis + Flyway on a disposable InnoDB schema. */
@EnabledIfSystemProperty(named="security.mysql.integration", matches="true")
@SpringBootTest(classes=AccountApplication.class, properties={
        "spring.config.import=", "spring.cloud.nacos.config.enabled=false", "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.nacos.config.import-check.enabled=false", "sky.wechat.mock-login-enabled=true",
        "sky.jwt.admin-secret-key=test-admin-secret", "sky.jwt.user-secret-key=test-user-secret",
        "sky.internal.token=integration-internal", "sky.security.docs-enabled=false"})
@AutoConfigureMockMvc @ActiveProfiles("test")
class SecurityMysqlIntegrationTest {
    static final String schema="sky_security_test_"+UUID.randomUUID().toString().replace("-", "");
    static final String base=System.getProperty("security.mysql.url", "jdbc:mysql://127.0.0.1:3306/");
    static final String user=System.getProperty("security.mysql.user", "root");
    static final String password=System.getenv().getOrDefault("SECURITY_TEST_DB_PASSWORD", "1234");
    static final String options="?serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true";
    static boolean created;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) throws Exception {
        try(var connection=DriverManager.getConnection(base+options,user,password);var statement=connection.createStatement()) {
            statement.execute("CREATE DATABASE "+schema); created=true;
        }
        registry.add("spring.datasource.druid.url", () -> base+schema+options);
        registry.add("spring.datasource.druid.username", () -> user);
        registry.add("spring.datasource.druid.password", () -> password);
    }
    @AfterAll static void cleanup() throws Exception {
        if (created && schema.matches("sky_security_test_[0-9a-f]{32}"))
            try(var connection=DriverManager.getConnection(base+options,user,password);var statement=connection.createStatement()) {
                statement.execute("DROP DATABASE "+schema);
            }
    }
    @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; @Autowired ObjectMapper json; @Autowired JwtProperties jwt;
    @BeforeEach void fixtures() {
        jdbc.update("delete from employee_role"); jdbc.update("delete from employee"); jdbc.update("delete from revoked_token");
        jdbc.update("delete from address_book"); jdbc.update("delete from user");
        jdbc.update("insert into employee(id,name,username,password,phone,sex,id_number,status) values(1,'Admin','admin','123456','12345678901','1','test',1),(2,'Reader','reader','123456','12345678902','1','test',1),(3,'None','none','123456','12345678903','1','test',1)");
        jdbc.update("insert into employee_role select 1,id from role where code='SECURITY_ADMIN'");
        jdbc.update("insert into employee_role select 2,id from role where code='SECURITY_READONLY'");
    }
    String login(String username, String password) throws Exception {
        var response=mvc.perform(post("/admin/employee/login").contentType("application/json")
                .content(json.writeValueAsString(java.util.Map.of("username",username,"password",password))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(1)).andExpect(jsonPath("$.data.userName").value(username))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response).path("data").path("token").asText();
    }
    @Test void realLoginTemplateRbacAndReadOnlyRole() throws Exception {
        assertEquals(6,jdbc.queryForObject("select count(*) from permission where path like '/admin/payment/%'",Integer.class));
        assertEquals(6,jdbc.queryForObject("select count(*) from role_permission rp join role r on r.id=rp.role_id join permission p on p.id=rp.permission_id where r.code='SECURITY_ADMIN' and p.path like '/admin/payment/%'",Integer.class));
        assertEquals(3,jdbc.queryForObject("select count(*) from role_permission rp join role r on r.id=rp.role_id join permission p on p.id=rp.permission_id where r.code='SECURITY_READONLY' and p.path like '/admin/payment/%'",Integer.class));
        String admin=login("admin","123456"), reader=login("reader","123456"), none=login("none","123456");
        assertTrue(jdbc.queryForObject("select password from employee where id=1",String.class).startsWith("$argon2id$"));
        mvc.perform(get("/admin/employee/1").header("token",admin)).andExpect(status().isOk()).andExpect(jsonPath("$.data.password").value("****"));
        mvc.perform(get("/admin/employee/1").header("token",reader)).andExpect(status().isOk());
        mvc.perform(put("/admin/employee").header("token",reader).contentType("application/json").content("{\"id\":1,\"name\":\"hacked\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/admin/employee/1").header("token",none)).andExpect(status().isForbidden());
        mvc.perform(get("/admin/not-configured").header("token",admin)).andExpect(status().isForbidden());
        mvc.perform(get("/admin/employee/1")).andExpect(status().isUnauthorized());
    }
    @Test void logoutAndPasswordChangeInvalidatePreviouslyIssuedTokens() throws Exception {
        String old=login("admin","123456");
        mvc.perform(post("/admin/employee/logout").header("token",old)).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(1));
        mvc.perform(get("/admin/employee/1").header("token",old)).andExpect(status().isUnauthorized());
        String current=login("admin","123456");
        mvc.perform(put("/admin/employee/editPassword").header("token",current).contentType("application/json")
                .content("{\"oldPassword\":\"123456\",\"newPassword\":\"changed-password-123\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(1));
        mvc.perform(get("/admin/employee/1").header("token",current)).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/employee/1").header("token",login("admin","changed-password-123"))).andExpect(status().isOk());
    }
    @Test void disablingAndReenablingAccountNeverRevivesOldToken() throws Exception {
        String admin=login("admin","123456"), reader=login("reader","123456");
        mvc.perform(post("/admin/employee/status/0").param("id","2").header("token",admin)).andExpect(status().isOk());
        mvc.perform(get("/admin/employee/1").header("token",reader)).andExpect(status().isUnauthorized());
        mvc.perform(post("/admin/employee/status/1").param("id","2").header("token",admin)).andExpect(status().isOk());
        mvc.perform(get("/admin/employee/1").header("token",reader)).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/employee/1").header("token",login("reader","123456"))).andExpect(status().isOk());
    }
    @Test void userLoginOwnershipInternalAndRevocation() throws Exception {
        var login=mvc.perform(post("/user/user/login").contentType("application/json").content("{\"code\":\"test-code\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.openid").exists()).andReturn().getResponse().getContentAsString();
        String token=json.readTree(login).path("data").path("token").asText();
        long id=json.readTree(login).path("data").path("id").asLong();
        jdbc.update("insert into address_book(id,user_id,phone,consignee) values(99,?,'12345678901','foreign')",id+100);
        mvc.perform(get("/user/addressBook/99").header("authentication",token)).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        mvc.perform(post("/internal/account/sessions/check").contentType("application/json").content(json.writeValueAsString(new com.sky.contract.account.SessionCheck(token,false))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/internal/account/sessions/check").header("X-Internal-Token","integration-internal")
                .contentType("application/json").content(json.writeValueAsString(new com.sky.contract.account.SessionCheck(token,false))))
                .andExpect(status().isOk()).andExpect(content().string("true"));
        mvc.perform(post("/user/user/logout").header("authentication",token)).andExpect(status().isOk());
        mvc.perform(get("/user/addressBook/list").header("authentication",token)).andExpect(status().isUnauthorized());
    }
}
