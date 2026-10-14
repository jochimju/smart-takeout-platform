package com.sky.observability;

import com.alibaba.druid.filter.FilterAdapter;
import com.alibaba.druid.filter.FilterChain;
import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.proxy.jdbc.ConnectionProxy;
import com.alibaba.druid.proxy.jdbc.PreparedStatementProxy;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.sql.SQLException;
import java.util.ArrayList;

/** Diagnostic JDBC call timing; disabled in ordinary deployments. */
@Configuration
@ConditionalOnProperty(name="sky.seckill.profile-enabled", havingValue="true")
public class SeckillJdbcTimingConfiguration {
    @Bean
    static BeanPostProcessor seckillJdbcTimingFilterInstaller() {
        return new BeanPostProcessor() {
            @Override public Object postProcessBeforeInitialization(Object bean, String name) {
                if (bean instanceof DruidDataSource source) {
                    var filters = new ArrayList<>(source.getProxyFilters());
                    filters.add(new FilterAdapter() {
                        private void sql(PreparedStatementProxy statement,long started) {
                            String sql=String.valueOf(statement.getSql()).stripLeading().toLowerCase(java.util.Locale.ROOT);
                            String kind=sql.startsWith("select")?"select":sql.startsWith("insert")?"insert":sql.startsWith("update")?"update":sql.startsWith("delete")?"delete":"other";
                            long elapsed=System.nanoTime()-started;
                            SeckillTiming.record("jdbc_sql",elapsed);SeckillTiming.record("jdbc_"+kind,elapsed);
                            SeckillTiming.count("jdbc_calls",1);SeckillTiming.count("jdbc_"+kind+"_calls",1);
                        }
                        @Override public boolean preparedStatement_execute(FilterChain chain,PreparedStatementProxy statement)throws SQLException {
                            long started=System.nanoTime();try{return chain.preparedStatement_execute(statement);}finally{sql(statement,started);}
                        }
                        @Override public com.alibaba.druid.proxy.jdbc.ResultSetProxy preparedStatement_executeQuery(FilterChain chain,PreparedStatementProxy statement)throws SQLException {
                            long started=System.nanoTime();try{return chain.preparedStatement_executeQuery(statement);}finally{sql(statement,started);}
                        }
                        @Override public int preparedStatement_executeUpdate(FilterChain chain,PreparedStatementProxy statement)throws SQLException {
                            long started=System.nanoTime();try{return chain.preparedStatement_executeUpdate(statement);}finally{sql(statement,started);}
                        }
                        @Override public int[] statement_executeBatch(FilterChain chain,com.alibaba.druid.proxy.jdbc.StatementProxy statement)throws SQLException {
                            long started=System.nanoTime();try{return chain.statement_executeBatch(statement);}finally{if(statement instanceof PreparedStatementProxy prepared)sql(prepared,started);}
                        }
                        @Override public void connection_commit(FilterChain chain, ConnectionProxy connection) throws SQLException {
                            long started=System.nanoTime();
                            try { chain.connection_commit(connection); }
                            finally { SeckillTiming.record("jdbc_commit",System.nanoTime()-started); }
                        }
                        @Override public void connection_setAutoCommit(FilterChain chain, ConnectionProxy connection, boolean autoCommit) throws SQLException {
                            long started=System.nanoTime();
                            try { chain.connection_setAutoCommit(connection,autoCommit); }
                            finally { SeckillTiming.record(autoCommit?"jdbc_auto_commit_true":"jdbc_auto_commit_false",System.nanoTime()-started); }
                        }
                    });
                    source.setProxyFilters(filters);
                }
                return bean;
            }
        };
    }
}
