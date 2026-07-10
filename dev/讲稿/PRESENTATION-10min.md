# 10 分钟精简版讲稿 — 技术选型 + 项目演示

> **配合脚本**: `bash quick-demo.sh`（10 个步骤，约 5 分钟演示 + 5 分钟演讲）
> **目标**: 快速展示技术栈、架构决策和关键功能

---

## 0:00-0:30 — 一句话定位

Knowledge Platform，具有 AI 能力的知识社区后台。8 个微服务、12 个 Docker 容器、一键启动。

```
Spring Boot 3.4 + Spring Cloud Gateway + Nacos + Redis + RabbitMQ + WebFlux SSE
```

---

## 0:30-2:00 — 技术选型 (90秒)

### 为什么是这组技术栈？

**Gateway 而不是 Zuul/Kong？** Spring Cloud Gateway 是响应式的，和 Sentinel 限流集成零配置。双实例部署通过 Nginx `least_conn` 负载均衡。

**Nacos 而不是 Eureka？** 注册中心 + 配置中心合一。Sentinel 规则通过 Nacos gRPC 推送，改限流阈值无需重启。

**Redis 而不仅是数据库？** ZSET 的 `ZINCRBY` 是 O(log N) 原子操作，单机 10 万 QPS。配合 Caffeine 本地缓存 1 秒 TTL，热搜榜命中率 > 99%。

**RabbitMQ 而不是 Kafka？** 当前规模下，Topic Exchange 的灵活路由（`article.publish` → 双队列并行消费）刚好够用。管理界面友好，运维成本低。

**SSE 而不是 WebSocket？** AI 续写是单向数据流。SSE 基于 HTTP/1.1，无需握手，代理配置简单，浏览器 `EventSource` 原生支持。

**演示配合**: 终端执行 `bash quick-demo.sh`，每步 30-60 秒。

---

## 2:00-3:30 — 安全：多层防御 (90秒)

```bash
# [演示: quick-demo.sh 步骤 2-3]
```

**三层防御**：
1. Nginx 令牌桶 — 读 100r/s, 写 20r/s
2. Sentinel — 接口级 QPS 限流, Nacos 动态下发
3. JWT — HMAC-SHA384, 7天过期, Redis 黑名单注销

**两个安全加固细节**（代码审查中实际发现并修复的）：
- 注册时即使传 `"role":"admin"` 也会被强制为 `"user"` ——防止权限提升
- 网关在注入 `X-User-Id` 头之前先剥离客户端传入的同名头 ——防止身份伪造

---

## 3:30-5:00 — 异步处理：发布一篇文章的背后 (90秒)

```bash
# [演示: quick-demo.sh 步骤 4-6]
```

用户点"发布"后发生了什么？

1. `articleMapper.publishById()` — 更新状态为 PUBLISHED
2. `rabbitTemplate.convertAndSend()` — 发消息到 `article.topic.exchange`
3. **并行消费**：
   - TagExtractConsumer（5 线程）→ 分词 + 关键词匹配 → 写回 `tags` 字段
   - ComplianceCheckConsumer（5-10 线程）→ 敏感词检测 → 写回 `auditResult`

**踩过的坑**：代码审查发现标签和合规从来没工作过。根因是消费者缺少 `Jackson2JsonMessageConverter` ——生产者发 JSON，消费者收 `byte[]`，类型不匹配，消息静默失败。修复后立即生效。

---

## 5:00-6:30 — 热搜：为什么不用 SQL 排序？(90秒)

```bash
# [演示: quick-demo.sh 步骤 7-8]
```

```
SELECT ... ORDER BY score DESC LIMIT 10  — 10 万文章时，这就是灾难
```

**ZSET 方案**：`ZINCRBY hot_articles {id} {score}` — O(log N)。阅读 +1，点赞 +3，评论 +1。每日 3:00 分数 ×0.9 衰减。

**三级缓存**：Caffeine(L1, 1s) → Redis ZSET(L2) → MySQL(L3)。1000 并发请求，999 个命中 Caffeine。

**缓存一致性**：每次 ZSET 变更后通过 Redis Pub/Sub 通知所有实例失效本地缓存。即使消息丢失，Caffeine TTL 保证 1 秒后最终一致。

---

## 6:30-7:30 — AI：为什么是 SSE？(60秒)

```bash
# [演示: quick-demo.sh 步骤 9]
```

**当前运行 Demo 模式**— 8 句预设中文，200ms 逐字推送，无需 API Key。

真实模式只需一行配置：`OPENAI_API_KEY=sk-xxx`

**熔断保护**：Resilience4j — 10 次窗口, 50% 失败率 → 30 秒断路。降级返回"AI 服务暂时不可用"。

51 个 data 块，以 `[DONE]` 结束，15 秒心跳防超时。

## 7:00-7:30 — 压测：QPS 与瓶颈

```
场景              500并发        800并发       1000并发
热搜榜(缓存)     100% · 2ms    100% · 2ms    100% · 2ms  ✅
文章列表(MySQL)  200并发 100% · 8ms  —           —
```

**JMeter 极限压测**：缓存层 1000 并发全绿（avg 2ms），MySQL 层 200 并发全绿。**这是 JMeter 测试环境的极限，不是服务的极限。** 3 实例水平扩展 + Nacos 心跳调优 + Nginx 容错已验证生效。

---

## 7:30-8:30 — 数据：读写分离 + 原子防重 (60秒)

```bash
# [演示: quick-demo.sh 步骤 10]
```

**读写分离**：`@Transactional(readOnly=true)` → 自动路由到从库。其余 → 主库。

**点赞防重**：`SETNX like:article:{id}:user:{uid}` — Redis 原子操作，永久去重。

**逻辑删除**：MyBatis-Plus `@TableLogic` — `deleted=1` 自动过滤。

---

## 8:30-10:00 — 演进 + 总结 (90秒)

**已完成的**：
- ✅ Stage 14: 性能压测（15/25/50/100 并发）+ 水平扩展 3 实例
- ✅ 503 诊断 → Nacos 心跳调优 → Nginx 容错 → 验证闭环
- ✅ 16 个代码审查问题全部修复

**下一步**：
- Druid 连接池扩容 50→200 → MySQL 层 50 并发恢复 100%
- 真实 LLM 替换 Demo 模式
- K8s 迁移 + HPA 自动扩缩容

**这个项目展示了**：
- 微服务全栈：网关 → 业务 → 消息 → 缓存 → 数据库 → 压测
- 不是 CRUD 堆砌：有限流、热榜、异步、AI 四个深度场景
- 完整的工程闭环：发现 503 → 诊断根因 → 扩容验证 → 数据说话
- 可演示、可测试、可交付

---

## 演示对照表

| 演讲时间 | 内容 | 演示步骤 |
|---------|------|---------|
| 0:00-0:30 | 定位 | 步骤 1 启动检查 |
| 0:30-2:00 | 技术选型 | 步骤 2 登录(JWT) |
| 2:00-3:30 | 安全防御 | 步骤 3 注册 admin 拒绝 |
| 3:30-5:00 | 异步处理 | 步骤 4-6 发布+越权+防重 |
| 5:00-6:30 | 热搜排行 | 步骤 7-8 ZSET+分页 |
| 6:30-7:30 | AI 续写 | 步骤 9 SSE 流 |
| 7:30-8:30 | 数据一致性 | 步骤 10 注销拦截 |
| 8:30-10:00 | 总结 | — |
