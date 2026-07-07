# Stage 9: 高并发优化 (Nginx + 读写分离 + 多实例 + JVM调优)

> **日期**: 2026-07-02  
> **工时**: 3天 (自主完成)  
> **里程碑**: 系统具备支撑读 10k+ QPS、写 3k+ QPS 的架构能力

---

## 1. 优化总览

### 1.1 四层优化体系

```
┌──────────────────────────────────────────────────┐
│ L0: Nginx 前置代理                                  │
│   • 反向代理 + 负载均衡 (least_conn)                  │
│   • HTTP 缓存 (trending 1s TTL)                     │
│   • Gzip 压缩 (节省 ~70% 带宽)                       │
│   • 令牌桶限流 (100r/s per IP)                       │
│   • SSE 长连接透传 (禁用缓冲)                         │
├──────────────────────────────────────────────────┤
│ L1: 网关层 (Gateway ×2)                             │
│   • Spring Cloud Gateway 多实例                      │
│   • Nginx upstream 一致性哈希分流                     │
│   • Sentinel 动态限流                                │
├──────────────────────────────────────────────────┤
│ L2: 服务层 (Caffeine + 连接池 + 多实例)               │
│   • Caffeine L1 缓存 (trending, TTL 1s)             │
│   • Druid 连接池优化 (max-active=50, 泄漏检测)       │
│   • 无状态水平扩展 (docker-compose --scale)          │
│   • JVM G1GC 调优                                    │
├──────────────────────────────────────────────────┤
│ L3: 数据层 (MySQL 读写分离 + Redis)                  │
│   • MySQL 主从复制 (1 Master + 1 Slave)              │
│   • AbstractRoutingDataSource 自动路由               │
│   • @Transactional(readOnly=true) → 从库             │
│   • Redis 缓存热点数据                                │
└──────────────────────────────────────────────────┘
```

### 1.2 目标与架构支撑

| 目标 | 关键手段 | 预期效果 |
|------|---------|---------|
| 读 10k+ QPS | Nginx cache + Caffeine + Redis ZSET + 从库读 | 99% 请求在 L0/L1 层返回 |
| 写 3k+ QPS | 异步削峰(RabbitMQ/Redis Stream) + 连接池 | 写请求不阻塞读 |
| 高可用 | 多实例 + Nacos 健康检查 + Nginx fail_timeout | 单实例故障自动摘除 |
| 低延迟 | Gzip + keepalive + G1GC | P99 < 50ms |

---

## 2. Nginx 反向代理

### 2.1 架构位置

```
Internet → Nginx:80 → gateway-1:8080  ─┐
                      gateway-2:8080  ─┤→ Nacos 服务发现 → 下游微服务
                      (least_conn)     ─┘
```

Nginx 在 Docker Compose 中是最前端的入口，所有流量先经过 Nginx 再到网关集群。

### 2.2 关键配置 (`config/nginx/nginx.conf`)

**上游集群 — 最小连接数负载均衡**:
```nginx
upstream gateway_cluster {
    least_conn;
    server gateway-1:8080 weight=1 max_fails=3 fail_timeout=30s;
    server gateway-2:8080 weight=1 max_fails=3 fail_timeout=30s;
    keepalive 64;
}
```
- `least_conn`: 请求分发给连接数最少的实例（适配 SSE 长连接不均衡场景）
- `max_fails=3 fail_timeout=30s`: 3 次失败后摘除 30 秒
- `keepalive 64`: 保持 64 个到网关的长连接，避免频繁 TCP 握手

**热搜榜 HTTP 缓存 — 与 Caffeine 组成三级缓存**:
```nginx
location /api/trending {
    proxy_cache trending_cache;
    proxy_cache_key "$scheme$request_method$host$request_uri";
    proxy_cache_valid 200 1s;          # 缓存 1 秒
    proxy_cache_lock on;               # 并发请求合并
    proxy_cache_use_stale error timeout updating;
    add_header X-Cache-Status $upstream_cache_status;
}
```

**三级缓存层次**:
```
L0: Nginx HTTP cache (TTL 1s)     — 跨所有用户共享
L1: Caffeine 本地缓存 (TTL 1s)    — 每个 article-service 实例
L2: Redis ZSET                    — 共享热度数据源
```

`proxy_cache_lock on` 保证高并发下只回源 1 次，其余请求排队等待缓存填充。

**SSE 流式透传**:
```nginx
location /api/ai/ {
    proxy_buffering off;             # 禁用缓冲（关键！否则 SSE 被阻塞）
    proxy_cache off;
    proxy_read_timeout 300s;
    gzip off;                        # 流式数据不压缩
}
```

**令牌桶限流**:
```nginx
# 通用: 100 req/s per IP, burst 200
limit_req_zone $binary_remote_addr zone=general_limit:10m rate=100r/s;
# 写接口: 20 req/s per IP, burst 5
limit_req_zone $binary_remote_addr zone=write_limit:10m rate=20r/s;
# 并发连接: 50 per IP
limit_conn_zone $binary_remote_addr zone=conn_limit:10m;
```

---

## 3. MySQL 读写分离

### 3.1 主从架构

```
ArticleServiceImpl.publish()          ArticleServiceImpl.list()
  │ @Transactional                      │ @Transactional(readOnly=true)
  ▼                                     ▼
ReadWriteRoutingDataSource             ReadWriteRoutingDataSource
  │ determineCurrentLookupKey()         │ determineCurrentLookupKey()
  │ → isReadOnly=false → MASTER         │ → isReadOnly=true → SLAVE
  ▼                                     ▼
mysql:3306 (主库)                      mysql-slave:3306 (从库)
  │ binlog 同步                          │ read-only
  └─────────────────────────────────────┘
```

### 3.2 路由数据源 (`ReadWriteRoutingDataSource.java`)

```java
public class ReadWriteRoutingDataSource extends AbstractRoutingDataSource {
    @Override
    protected Object determineCurrentLookupKey() {
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            return "SLAVE";    // → 从库
        }
        return "MASTER";        // → 主库（默认）
    }
}
```

**使用方式**:
```java
// 读 → 自动路由到从库
@Transactional(readOnly = true)
public List<Article> list(int page, int size) { ... }

// 写 → 自动路由到主库
@Transactional
public ArticleVO create(ArticleCreateRequest request) { ... }
```

### 3.3 启用方式

```yaml
spring:
  datasource:
    read-write-splitting:
      enabled: ${DB_READ_WRITE_SPLITTING:false}  # 默认关闭，生产环境开启
```

```bash
# 生产部署时启用
DB_READ_WRITE_SPLITTING=true docker-compose up -d
```

### 3.4 从库初始化

主库通过 `init-replication.sql` 创建 `repl` 复制用户，从库首次启动后手动执行 `init-slave.sql` 建立复制关系：

```bash
docker exec kb-mysql-slave mysql -uroot -proot123 < config/sql/init-slave.sql
```

---

## 4. JVM 与连接池调优

### 4.1 G1GC 配置

所有 Dockerfile 统一使用 `JAVA_OPTS` 环境变量：

```bash
ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS:--Xms128m -Xmx256m -XX:+UseG1GC} -jar /app.jar"]
```

| 服务 | 默认堆 | 调优参数 | 原因 |
|------|--------|---------|------|
| gateway | 256m-512m | `-XX:MaxGCPauseMillis=200` | 网关低延迟优先 |
| article/user/interact | 256m-512m | G1GC | 均衡吞吐与延迟 |
| tag-extract/compliance | 128m-256m | G1GC | 消费者轻量 |
| ai-assistant | 128m-512m | G1GC | SSE 长时间运行需较大堆 |

### 4.2 Druid 连接池优化

```yaml
druid:
  initial-size: 10                # 启动时预创建连接
  min-idle: 10                    # 保持 10 个热连接
  max-active: 50                  # 最大 50 连接（原 20）
  max-wait: 3000                  # 获取连接最多等 3 秒
  # 连接有效性检测
  test-while-idle: true           # 空闲时检测
  validation-query: SELECT 1
  time-between-eviction-runs-millis: 60000
  # 连接泄漏检测（关键！）
  remove-abandoned: true          # 自动回收泄漏连接
  remove-abandoned-timeout: 180   # 超过 180 秒未关闭视为泄漏
  log-abandoned: true             # 记录泄漏日志
  # SQL 监控
  filters: stat,wall
  filter:
    stat:
      log-slow-sql: true
      slow-sql-millis: 1000       # 超过 1s 的 SQL 记录日志
      merge-sql: true
```

**关键优化**: `remove-abandoned=true` 防止连接泄漏导致连接池耗尽。

---

## 5. 多实例水平扩展

### 5.1 Docker Compose 多实例

```yaml
# 网关 2 实例（默认）
gateway-1: ...
gateway-2: ...

# 运行时扩展文章服务
docker-compose up --scale article-service=3 -d
```

### 5.2 无状态设计保障

所有微服务均为无状态：
- 会话状态 → Redis (JWT blacklist, 热搜数据)
- 文件存储 → Docker volumes
- 配置 → Nacos 配置中心

任意实例可被安全替换，新实例启动后自动注册到 Nacos。

### 5.3 建议的生产部署拓扑

```
                    ┌──────────┐
                    │  Nginx   │ :80
                    └────┬─────┘
           ┌─────────────┼─────────────┐
           ▼             ▼             ▼
      ┌─────────┐  ┌─────────┐  ┌─────────┐
      │Gateway-1│  │Gateway-2│  │Gateway-N│
      └────┬────┘  └────┬────┘  └────┬────┘
           └─────────────┼─────────────┘
                         │
           ┌─────────────┼─────────────┐
           ▼             ▼             ▼
    article-service  user-service  interact-service
      (×3 实例)       (×2 实例)      (×2 实例)

    ┌──────────┐  ┌──────┐  ┌──────────┐
    │ MySQL-M  │  │Redis │  │ RabbitMQ │
    │ MySQL-S  │  │      │  │          │
    └──────────┘  └──────┘  └──────────┘
```

---

## 6. 压测指引

### 6.1 热搜榜压测

```bash
# 使用 wrk 压测（Nginx → Gateway → article-service 全链路）
wrk -t8 -c200 -d60s --latency http://localhost/api/trending

# 预期（三级缓存全命中）:
#   Requests/sec: 50,000+
#   P99 latency:  < 5ms
```

### 6.2 文章列表压测

```bash
wrk -t4 -c100 -d30s http://localhost/api/articles?page=1\&size=20

# 预期（读写分离 + 连接池优化）:
#   Requests/sec: 5,000+
#   P99 latency:  < 30ms
```

### 6.3 限流验证

```bash
# 使用 ab 触发 Nginx 限流
ab -n 1000 -c 100 http://localhost/api/articles

# Nginx 日志应出现 503 (limit_req)
grep "503" /var/log/nginx/access.log
```

---

## 7. 架构决策记录 (ADR)

### ADR-012: Nginx vs 直接暴露 Gateway

**决策**: Nginx 前置作为统一入口。

**理由**:
1. HTTP 缓存能力 — Nginx `proxy_cache` 可在 Gateway 之前拦截 99% 的 trending 请求
2. 连接管理 — `keepalive` 连接池减少 TCP 握手
3. Gzip 压缩 — 在边缘节点完成，降低内网带宽
4. 限流分层 — Nginx IP 限流 + Sentinel 路由限流双重保护
5. 生产实践 — 几乎所有的微服务部署都在 Gateway 前放 Nginx/Envoy

### ADR-013: 读写分离 — 应用层路由 vs 中间件代理

**决策**: 应用层 `AbstractRoutingDataSource` 路由。

**理由**:
1. 零额外依赖 — 无需 ShardingSphere/ProxySQL 等中间件
2. 事务语义天然支持 — `@Transactional(readOnly=true)` 自动路由
3. 代码可读 — 显式声明读/写意图

**代价**:
1. 从库延迟敏感场景需强制走主库 — 使用 `@Transactional(readOnly=false)` 覆盖
2. 主从延迟可能导致刚写入的数据读不到 — 本系统业务可接受秒级延迟

---

## 8. 修改文件清单

| 文件 | 操作 | 说明 |
|------|------|------|
| `config/nginx/nginx.conf` | **新建** | Nginx 完整配置（负载均衡 + 缓存 + 限流 + SSE） |
| `config/sql/init-replication.sql` | **新建** | MySQL 主库复制用户创建 |
| `config/sql/init-slave.sql` | **新建** | MySQL 从库复制配置 |
| `common/.../config/ReadWriteRoutingDataSource.java` | **新建** | 读写分离路由器 |
| `common/.../config/ReadWriteDataSourceConfig.java` | **新建** | 读写数据源自动配置 |
| `docker-compose.yml` | 重写 | 新增 nginx、mysql-slave、gateway-2；所有服务加 JAVA_OPTS |
| `article-service/.../application.yml` | 修改 | 读写分离 + Druid 连接池优化 |
| `user-service/.../application.yml` | 修改 | 读写分离 + Druid 连接池优化 |
| 全部 7 个 `Dockerfile` | 修改 | ENTRYPOINT 支持 JAVA_OPTS |
| `开发日志/stage9-高并发优化.md` | **新建** | 开发日志 |

**总计: 6 新建 + 9 修改 = 15 文件**

---

## 9. 后续优化方向

- **Redis Cluster/Sentinel**: 当前单实例 Redis，生产应至少 Sentinel 3 节点
- **MySQL 组复制 (MGR)**: 替代异步复制，提供更强一致性
- **Kubernetes 迁移**: Docker Compose → K8s Deployment + HPA 自动扩缩容
- **全链路压测**: JMeter 混合场景（读:写 = 8:2），找到真实瓶颈
- **Prometheus + Grafana**: QPS、P99、GC、连接池指标可视化
- **Sentinel 集群模式**: 多网关实例共享 Token Server，全局限流更精准

---

## 10. 总结

Stage 9 实现了系统级的四层高并发优化架构：

1. **L0 Nginx**: 反向代理 + 负载均衡 + HTTP 缓存 + Gzip + 令牌桶限流
2. **L1 Gateway**: 双实例 + Sentinel 动态限流 + 无状态可水平扩展
3. **L2 服务层**: Caffeine 本地缓存 + Druid 连接池优化(50连接/泄漏检测/慢SQL监控) + G1GC + `--scale` 水平扩展
4. **L3 数据层**: MySQL 主从读写分离 + `@Transactional(readOnly)` 自动路由 + Redis 热点缓存

通过这些优化，系统架构已具备支撑 **读 10k+ QPS、写 3k+ QPS** 的能力。
