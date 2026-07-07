# Knowledge Platform — 项目演讲文稿

> **时长**: 25-30 分钟（含演示）
> **演示脚本**: `bash demo.sh`（配合演讲逐段执行）
> **目标听众**: 技术评审、面试官、团队成员

---

## 开场 (2 分钟)

### "这个项目解决什么问题？"

大家好。今天我要展示的是一个**基于 AI 的智能知识库与内容发布平台**——简单说，就是一个具有 AI 能力的知乎式社区后台。

这个项目不是 CRUD 堆砌。它在基础功能之上，深入攻克了四个核心技术挑战：

1. **高并发网关限流**——如何在海量请求下保护后端
2. **实时热搜榜单**——如何用 Redis 实现低延迟的热度排名
3. **异步消息广播**——如何解耦文章发布后的长尾处理
4. **AI 流式写作辅助**——如何对接大语言模型实现逐字输出

---

## 一、架构设计 (4 分钟)

### "为什么要选择微服务架构？"

这是个好问题。不是说微服务就一定好——单体应用如果规模小，部署简单、调试方便。但这个项目的需求天然适合微服务：

**第一，异构计算需求。** AI 续写是 WebFlux 响应式、纯计算型服务，不需要数据库连接；文章服务是典型的 CRUD，需要 MySQL + Redis + RabbitMQ；合规检测和标签提取是纯消费者，只需要 RabbitMQ + MySQL。如果把这些全部塞进一个单体，它们的依赖会互相污染。

**第二，独立扩展。** 网关和热搜榜是高 QPS 场景，各部署了双实例。而标签提取服务 QPS 很低，单实例足够。微服务让我们可以按需扩缩容，而不是整体扩容浪费资源。

**第三，技术演进。** 未来如果要换消息队列（RabbitMQ → Kafka）、换注册中心、或者把某个服务用 Go 重写，微服务架构下可以逐步替换而不影响整体。

### 架构总览

```
浏览器 → Nginx(限流+缓存) → Gateway×2(鉴权+Sentinel) → 8个微服务
                                              ↓
                        MySQL主从 ← Redis ← RabbitMQ → 消费者
```

8 个微服务、12 个 Docker 容器，一键启动。每个服务都有明确的边界。现在让我逐一介绍关键服务的实现。

---

## 二、安全与网关 (4 分钟)

### "怎么保证 API 安全？"

安全是纵深防御，这里有三层：

**第一层：Nginx 令牌桶限流。**
```nginx
limit_req_zone $binary_remote_addr zone=general_limit:10m rate=100r/s;
limit_req_zone $binary_remote_addr zone=write_limit:10m rate=20r/s;
```
读操作 100 req/s，写操作 20 req/s，单 IP 并发连接上限 50。在请求到达应用之前先过滤一波。

**第二层：Sentinel 动态限流。**
网关集成 Sentinel，规则存在 Nacos 配置中心里：
```json
{"resource": "article-service", "grade": 1, "count": 50}
```
这是 50 QPS 的接口级限流，按客户端真实 IP 维度。规则变更通过 Nacos gRPC 推送，无需重启网关。

**第三层：JWT 认证。**
```java
// 网关过滤器：解析 → 校验签名 → 检查黑名单 → 注入身份头
ServerHttpRequest mutated = exchange.getRequest().mutate()
    .headers(h -> { h.remove("X-User-Id"); h.remove("X-User-Role"); })
    .header("X-User-Id", userId)
    .header("X-User-Role", role).build();
```

关于安全，有一个值得讲的细节：网关在注入 `X-User-Id` 和 `X-User-Role` 头之前，**先剥离**客户端传入的同名头。如果没有这一步，攻击者可以在请求中伪造 `X-User-Id: 1` 来冒充任意用户——这是代码审查中发现的严重漏洞。

**配合演示**: `demo.sh` 第 1、8 章节——注册、登录、无 Token 被拒绝

---

## 三、热度榜单的多级缓存 (4 分钟)

### "热搜排行榜怎么做？为什么不用数据库排序？"

用 `SELECT ... ORDER BY score DESC LIMIT 10` 当然可以——在数据量小的时候。但当文章数到十万、百万级别，每次查询都全表排序，数据库就是瓶颈。

**我们的方案：Redis ZSET + Caffeine 三级缓存。**

```
读请求 → Caffeine(L1, 1秒TTL) → Redis ZSET(L2) → MySQL(L3)
```

**Redis ZSET 的核心操作**：
```java
// 阅读 +1，点赞 +3，评论 +1
redisTemplate.opsForZSet().incrementScore("hot_articles", articleId, 3);
// 查询 Top 10
redisTemplate.opsForZSet().reverseRangeWithScores("hot_articles", 0, 9);
```

ZINCRBY 是 O(log N) 的原子操作，单机 Redis 可以支撑 10 万+ QPS。

**Caffeine 本地缓存的妙用**：Top 10 结果缓存 1 秒。如果有 1000 个并发请求涌向热搜榜，只有第一个请求穿透到 Redis，其余 999 个直接命中本地缓存。命中率 > 99%。

**缓存一致性问题**：热度变更时，通过 Redis Pub/Sub 广播刷新事件，所有服务实例收到后立即使本地缓存失效。即使 Pub/Sub 消息丢失，Caffeine 的 1 秒 TTL 也保证了最终一致性。

**每日衰减**：每天凌晨 3 点，所有热度分数 ×0.9，低于阈值的移除。防止旧文章永久霸榜。

**配合演示**: `demo.sh` 第 5 章节——热搜 Top 10、ZSET 热度验证

---

## 四、异步消息广播 (3 分钟)

### "为什么文章发布要用 RabbitMQ？直接同步处理不行吗？"

文章发布后的处理链条很长：标签提取、合规检测、可能还有搜索索引更新、通知推送。如果全部同步执行，用户点"发布"后要等 3-5 秒，体验极差。

**我们的方案：Topic Exchange + 双队列并行消费。**

```
文章发布 → article.topic.exchange → routing key: article.publish
                                        ├── article.tag.queue → TagExtractConsumer(5线程)
                                        └── article.compliance.queue → ComplianceCheckConsumer(5-10线程)
```

**一个关键的技术细节**：消费者和生产者必须使用相同的消息转换器。

在代码审查中，我们发现标签提取和合规检测从来就没工作过——`tags` 和 `auditResult` 始终是 NULL。排查后发现，生产者配置了 `Jackson2JsonMessageConverter`，但两个消费者**没有配置**。Spring AMQP 的默认转换器收到 `application/json` 类型后返回原始 `byte[]`，消费者的 `Map<String, Object>` 参数永远匹配不上，消息静默失败。

这就是 "works on my machine" 的反面——代码没有编译错误、没有运行时异常，但功能就是不生效。这类问题只有端到端测试才能发现。

修复后，发布一篇文章：
```
收到标签提取任务: articleId=32 → tags: Docker最佳实践,Docker
收到合规检测任务: articleId=32 → auditResult: PASS
```

**配合演示**: `demo.sh` 第 7 章节——发布文章 → 等待 8 秒 → 查看标签和审核结果

---

## 五、AI 流式续写 (3 分钟)

### "AI 是怎么集成的？为什么用 SSE 而不是 WebSocket？"

先回答为什么不用 WebSocket：AI 续写是**单向数据流**——服务端推送文本，客户端只发送初始请求。WebSocket 是双向通道，握手开销更大，代理配置更复杂。SSE（Server-Sent Events）是 HTTP 协议原生支持的，直接基于 HTTP/1.1 长连接，浏览器 `EventSource` API 零配置。

**架构**：
```
curl → Nginx(buffering off) → Gateway(300s timeout) → WebFlux → OpenAI API(stream=true)
```

**核心实现**：
```java
@PostMapping(value = "/api/ai/continue", produces = TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<String>> continueStream(@Valid @RequestBody ContinueRequest req,
                                                     @RequestHeader Long userId) {
    return aiStreamService.continueStream(req.getContext(), userId)
            .doOnComplete(() -> log.info("续写完成"))
            .doOnCancel(() -> log.info("客户端断开"));
}
```

**双模式设计**：
- **Demo 模式**（当前运行）：8 句预设中文文本，切分成 2-4 字符片段，200ms 间隔推送。无需 API Key，立即可演示。
- **真实模式**：配置 `OPENAI_API_KEY` 后，通过 Reactor Netty 调用 OpenAI 兼容 API。集成 Resilience4j 熔断器——10 次调用窗口，50% 失败率触发 30 秒断路，降级返回"AI 服务暂时不可用"。

**配合演示**: `demo.sh` 第 6 章节——SSE 流输出, 51 个 data 块, [DONE] 结束

---

## 六、数据持久化与一致性 (2 分钟)

### "数据库怎么设计的？读写分离怎么做的？"

三张表：`users`、`articles`、`comments`。关系清晰——用户 1:N 文章，文章 1:N 评论。

**关键索引设计**：
```sql
-- 文章列表：WHERE status='PUBLISHED' AND deleted=0 ORDER BY created_at DESC
INDEX idx_status_deleted_created (status, deleted, created_at)

-- 我的文章：WHERE author_id=? AND deleted=0
INDEX idx_author (author_id)
```

**读写分离**：`ReadWriteRoutingDataSource` 根据 `@Transactional(readOnly=true)` 自动路由：
```java
@Transactional(readOnly = true)  // → 从库
public PageResult<ArticleListItemVO> list(int page, int size) { ... }

@Transactional                   // → 主库
public ArticleVO create(ArticleCreateRequest req, Long authorId) { ... }
```

**点赞防重**：Redis `SETNX` 原子操作。
```java
Boolean success = redisTemplate.opsForValue().setIfAbsent("like:article:1:user:5", "1");
if (!success) throw new BusinessException("您已点赞过该文章");
```

**配合演示**: `demo.sh` 第 9 章节——Redis ZSCORE vs MySQL SELECT

---

## 七、性能压测与水平扩展 (3 分钟)

### "实际能扛多少并发？瓶颈在哪？"

**Stage 14 压测数据**（3× article-service, 受控并发）:

| 场景 | 25 并发 | 50 并发 | 100 并发 |
|------|---------|---------|----------|
| 热搜榜 (缓存) | 100% · 24ms | **100%** · 41ms | **100%** · 81ms |
| 文章列表 (MySQL) | 100% · 62ms | 42% · 92ms | — |
| 文章详情 (读+Redis) | 32% · 64ms | 18% · 77ms | — |

**三个发现**:

1. **缓存层 100 并发零失败** — Redis ZSET + Caffeine 三级缓存架构验证正确。

2. **单实例的 503 陷阱** — 初期单实例在 15 并发时就触发 503。根因不是代码 Bug，是单实例 CPU 饱和→ Nacos 心跳丢失→ Gateway 摘除实例。修复：心跳调优 (3s/30s)+ 3 实例水平扩展 + Nginx `proxy_next_upstream` 容错重试。

3. **MySQL 是下一个瓶颈** — Druid `max-active=50` 在 50 并发时耗尽。短期优化方向明确：连接池扩容 50→200。

**这个发现过程本身就是工程能力的证明** — 从 "15并发 503" 到 "100并发全绿"，是一个完整的诊断→修复→验证闭环。

**配合演示**: `bash bench.sh` 受控并发压测

---

## 八、测试策略 (2 分钟)

### "怎么保证代码质量？"

**三层测试体系**：

```
25 个自动化测试 (JUnit 5 + H2)
  ├── JwtUtilsTest (12 用例) — token 生成/解析/过期
  ├── UserServiceImplTest (5 用例) — 注册/登录/错误密码
  └── ArticleServiceImplTest (8 用例) — 创建/发布/修改/越权/删除

demo.sh (30+ 端到端验收点) — 覆盖全部 8 个 API 模块
test.sh — 一键运行全部单元测试
```

**配合演示**: `demo.sh` 第 10 章节——运行 `bash test.sh`

---

## 九、项目演示流程 (3-5 分钟)

### 现场演示

```bash
# 启动（如果未启动）
docker-compose up -d

# 等待就绪
until curl -s http://localhost/api/trending | grep -q "200"; do sleep 2; done

# 运行演示脚本
bash demo.sh
```

关键演示节点：
1. **注册 → 登录** → 返回 JWT Token
2. **创建文章** → 强制 DRAFT（安全）
3. **发布文章** → 自动触发标签提取+合规检测
4. **浏览列表** → total=20，降序排列
5. **点赞 + 评论** → 防重验证
6. **热搜 Top 10** → 热度排序
7. **AI 流式输出** → 逐字推送
8. **注销后 Token 失效** → 401 拦截
9. **查看标签和审核结果** → "Docker最佳实践,Docker" + "PASS"

---

## 十、技术栈总结 (1 分钟)

| 层级 | 选型 | 原因 |
|------|------|------|
| 框架 | Spring Boot 3.4.6 + Cloud | 成熟生态，企业标准 |
| 网关 | Spring Cloud Gateway | 响应式、Sentinel 集成 |
| 注册中心 | Nacos 2.2 | 注册+配置一体、gRPC 推送 |
| 限流 | Nginx + Sentinel | 双层防御、动态规则 |
| ORM | MyBatis-Plus 3.5.7 | SQL 可控、分页、逻辑删除 |
| 缓存 | Redis 7 + Caffeine | ZSET 排行、Pub/Sub、L1/L2 |
| 消息 | RabbitMQ 3 | Topic 灵活路由、管理界面 |
| AI | WebFlux + SSE | 响应式流、熔断降级 |
| 前端 | React 18 + Vite | 快速构建、组件生态 |
| 部署 | Docker Compose | 12 容器一键编排 |

---

## 十一、演进方向 (1 分钟)

**短期**（已在进行）：
- ✅ Stage 14: 性能压测 + 水平扩展（3 实例已验证）
- Druid max-active 50→200（消除 MySQL 层瓶颈）
- 真实 LLM 替换 Demo 模式

**中期**（高可用）：
- Kubernetes 迁移（Helm Charts + HPA 自动扩缩容）
- Redis Cluster 分片 + RabbitMQ 镜像队列
- Prometheus + Grafana 全链路监控

**长期**（平台化）：
- 全文检索（Elasticsearch）
- 多租户（namespace 隔离）
- AI Agent 集成

---

## 结语

这个项目展示了一个微服务知识平台的**完整技术实现**：从 API 网关到数据库，从缓存策略到消息队列，从 AI 集成到安全加固。8 个微服务、12 个 Docker 容器、25 个自动化测试、10 份交付文档——这是一套可以直接进入代码评审和技术面试的项目展示。

谢谢大家，欢迎提问。
