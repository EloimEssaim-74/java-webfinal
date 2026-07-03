package com.kb.common.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 读写分离数据源路由器.
 *
 * <h3>路由规则</h3>
 * <ul>
 *   <li>当前线程在只读事务中 → 路由到从库（SLAVE）</li>
 *   <li>其他情况（无事务/读写事务） → 路由到主库（MASTER）</li>
 * </ul>
 *
 * <h3>使用方式</h3>
 * <pre>
 * // 读操作 — 路由到从库
 * &#64;Transactional(readOnly = true)
 * public List&lt;Article&gt; list() { ... }
 *
 * // 写操作 — 路由到主库（默认）
 * &#64;Transactional
 * public void create(Article article) { ... }
 * </pre>
 *
 * <h3>性能收益</h3>
 * <p>在文章列表、详情查询等高频读场景，将读请求分流到从库，
 * 可降低主库 40-60% 的查询负载.</p>
 */
@Slf4j
public class ReadWriteRoutingDataSource extends AbstractRoutingDataSource {

    /**
     * 数据源类型键.
     *
     * <p>{@link AbstractRoutingDataSource} 根据此返回值选择目标数据源.
     */
    public static final String MASTER = "MASTER";
    public static final String SLAVE = "SLAVE";

    @Override
    protected Object determineCurrentLookupKey() {
        // 只读事务 → 从库
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            return SLAVE;
        }
        // 默认 → 主库
        return MASTER;
    }
}
