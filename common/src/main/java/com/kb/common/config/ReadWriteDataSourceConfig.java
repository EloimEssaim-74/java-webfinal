package com.kb.common.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.util.Map;

/**
 * 读写分离数据源配置.
 *
 * <p>通过 {@code spring.datasource.read-write-splitting.enabled=true} 启用.
 * 启用后，spring.datasource 的 url 指向主库，
 * spring.datasource.slave 配置从库连接.</p>
 *
 * <h3>application.yml 示例</h3>
 * <pre>
 * spring:
 *   datasource:
 *     read-write-splitting:
 *       enabled: true
 *     # 主库（写）
 *     url: jdbc:mysql://mysql:3306/kb_platform?...
 *     username: root
 *     password: root123
 *     # 从库（读）
 *     slave:
 *       url: jdbc:mysql://mysql-slave:3306/kb_platform?...
 *       username: root
 *       password: root123
 * </pre>
 *
 * <h3>路由规则</h3>
 * <p>{@link ReadWriteRoutingDataSource} 通过事务的 readOnly 属性判断:
 * {@code @Transactional(readOnly=true)} → 从库, 否则 → 主库.</p>
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "spring.datasource.read-write-splitting.enabled", havingValue = "true")
public class ReadWriteDataSourceConfig {

    /**
     * 主库数据源（写操作）.
     *
     * <p>复用 spring.datasource.* 标准配置项，兼容 Druid/HikariCP 自动检测.</p>
     */
    @Bean
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties masterDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    public DataSource masterDataSource(DataSourceProperties masterProperties) {
        return masterProperties.initializeDataSourceBuilder().build();
    }

    /**
     * 从库数据源（读操作）.
     */
    @Bean
    @ConfigurationProperties("spring.datasource.slave")
    @ConditionalOnProperty(name = "spring.datasource.slave.url")
    public DataSourceProperties slaveDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @ConditionalOnProperty(name = "spring.datasource.slave.url")
    public DataSource slaveDataSource(DataSourceProperties slaveProperties) {
        return slaveProperties.initializeDataSourceBuilder().build();
    }

    /**
     * 读写分离路由数据源 — 替换默认 DataSource.
     */
    @Bean
    @Primary
    public DataSource routingDataSource(DataSource masterDataSource,
                                         DataSource slaveDataSource) {
        ReadWriteRoutingDataSource router = new ReadWriteRoutingDataSource();
        router.setTargetDataSources(Map.of(
                ReadWriteRoutingDataSource.MASTER, masterDataSource,
                ReadWriteRoutingDataSource.SLAVE, slaveDataSource
        ));
        router.setDefaultTargetDataSource(masterDataSource);
        log.info("读写分离数据源已启用: MASTER(写) ←→ SLAVE(读)");
        return router;
    }
}
