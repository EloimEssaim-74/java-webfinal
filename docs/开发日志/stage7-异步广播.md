# Stage 7: 异步广播处理 (RabbitMQ Topic + 独立消费者)

> **日期**: 2026-07-02  
> **工时**: 2天 (自主完成)  
> **里程碑**: 文章发布后并行触发标签提取与合规检测，两个独立消费者同时处理

---

## 1. 技术选型

### 1.1 为什么选 RabbitMQ Topic 交换机

| 对比维度 | Topic Exchange | Fanout Exchange | Direct Exchange | Kafka |
|----------|---------------|-----------------|-----------------|-------|
| 选择性路由 | ✅ 按 routing key 模式匹配 | ❌ 全量广播 | ❌ 精确匹配 | ⚠️ 分区路由 |
| 多消费者独立处理 | ✅ 不同 queue 不同 routing key | ✅ 所有 queue 收到相同消息 | ⚠️ 需多个 binding | ✅ 不同 consumer group |
| 消息优先级 | ✅ 支持 | ✅ 支持 | ✅ 支持 | ❌ 仅分区内有序 |
| 运维复杂度 | 低（已有实例） | 低 | 低 | 高（需 ZooKeeper） |
| ACK 粒度 | 单条 | 单条 | 单条 | 批量 offset |

**结论**: 项目已部署 RabbitMQ 3，Topic 交换机允许通过 routing key 灵活路由——`article.tag` → 标签提取队列，`article.compliance` → 合规检测队列，两个消费者并行独立处理，互不影响。

### 1.2 关键依赖

两个消费者模块共用相同依赖集：

```xml
<!-- RabbitMQ 客户端（Spring AMQP） -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-amqp</artifactId>
</dependency>

<!-- MyBatis-Plus — 回写 MySQL 标签/审核结果 -->
<dependency>
    <groupId>com.baomidou</groupId>
    <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
</dependency>

<!-- Hutool — 文本处理工具 -->
<dependency>
    <groupId>cn.hutool</groupId>
    <artifactId>hutool-all</artifactId>
</dependency>
```

---

## 2. 核心架构

### 2.1 消息流向

```
ArticleServiceImpl.publish()
  │
  ▼
rabbitTemplate.convertAndSend(
    "article.topic.exchange",   ← Topic 交换机
    "article.publish",           ← routing key
    {articleId, title, content, authorId, publishedAt}
)
  │
  ▼ Topic Exchange 路由
  │  binding: article.publish → article.tag.queue       ✅
  │  binding: article.publish → article.compliance.queue ✅ (Stage 7 新增)
  │
  ├─────────────────────────────────────────┐
  ▼                                         ▼
┌───────────────────┐              ┌─────────────────────┐
│ article.tag.queue │              │ article.compliance. │
│                   │              │       queue          │
└───────┬───────────┘              └──────────┬──────────┘
        │                                     │
        ▼                                     ▼
┌───────────────────┐              ┌─────────────────────┐
│ TagExtractConsumer│              │ComplianceCheck      │
│ (5 并发线程)      │              │Consumer (5-10 并发)  │
│                   │              │                     │
│ 1. 标题关键词提取  │              │ 1. 敏感词检测       │
│ 2. 内容领域匹配    │              │ 2. 可疑词检测       │
│ 3. 合并去重取Top5  │              │ 3. 内容长度判断     │
│ 4. 回写 tags 字段  │              │ 4. 回写 audit_result│
│ 5. 手动 ACK       │              │ 5. 违规自动删除     │
└───────────────────┘              └─────────────────────┘
```

### 2.2 交换机与绑定关系

```
                    article.topic.exchange (Topic, durable)
                              │
          ┌───────────────────┼───────────────────┐
          │                   │                   │
   routing: article.tag  routing: article.   routing: article.publish
          │              compliance               │
          ▼                   │                   ▼
   ┌──────────────┐           │          ┌──────────────────┐
   │ tag.queue    │◄──────────┘          │ compliance.queue  │
   │ (durable)    │                      │ (durable)         │
   └──────────────┘        ┌─────────────┴──────────────────┘
                           │
                     routing: article.publish (Stage 7 新增)
```

**关键修复 (Stage 7)**: 原有 `publishBinding` 仅将 `article.publish` 绑定到 `tagQueue`，导致合规队列无法收到发布消息。Stage 7 新增 `publishToComplianceBinding`，使两个队列都能收到发布事件。

### 2.3 并发模型

| 消费者 | 并发配置 | 线程模型 | 依据 |
|--------|---------|---------|------|
| TagExtractConsumer | `5` (固定) | CPU 密集型，文本分词 | 5 线程足够 |
| ComplianceCheckConsumer | `5-10` (弹性) | IO 密集型，模拟 API 调用 | 可根据消息积压自动扩容 |

```java
// TagExtractConsumer — 固定 5 线程
@RabbitListener(queues = "article.tag.queue", concurrency = "5", ackMode = "MANUAL")

// ComplianceCheckConsumer — 弹性 5-10 线程
@RabbitListener(queues = "article.compliance.queue", concurrency = "5-10", ackMode = "MANUAL")
```

`prefetch = 1` 确保公平分发——每个消费者一次只取一条消息，处理快的消费者可以取更多。

---

## 3. 关键代码实现

### 3.1 标签提取消费者 (`TagExtractConsumer.java`)

**核心标签提取算法**（模拟 AI，生产环境替换为 NLP 服务调用）：

```java
private String extractTags(String title, String content) {
    Set<String> tags = new LinkedHashSet<>();

    // 1. 标题分词：按标点/空格切分 → 去停用词 → 保留 ≥2 字词
    String[] titleWords = title.split("[，,。\\s、：:；;！!？?\\-—()（）【】\\[\\]《》/\\\\|]+");
    for (String word : titleWords) {
        String trimmed = word.trim();
        if (trimmed.length() >= 2 && !STOP_WORDS.contains(trimmed)) {
            tags.add(trimmed);
        }
    }

    // 2. 内容领域匹配：从 15+ 技术领域特征词中匹配
    String lowerContent = content.toLowerCase();
    for (Map.Entry<String, String> entry : DOMAIN_PATTERNS.entrySet()) {
        if (lowerContent.contains(entry.getKey().toLowerCase())) {
            tags.add(entry.getValue());
        }
    }

    // 3. 去重、截取 Top 5
    return tags.stream().limit(5).collect(Collectors.joining(","));
}
```

**手动 ACK 策略**:
```java
try {
    Article article = articleMapper.selectById(articleId);
    article.setTags(tags);
    articleMapper.updateById(article);
    channel.basicAck(deliveryTag, false);     // 成功 → ACK
} catch (Exception e) {
    channel.basicNack(deliveryTag, false, false); // 失败 → Nack (不重新入队)
}
```

失败后不重新入队的考虑：如果标签提取逻辑本身有问题（如 NPE），重新入队只会反复失败形成死循环。生产环境应引入死信队列 (DLQ) 将失败消息转移至人工处理。

### 3.2 合规检测消费者 (`ComplianceCheckConsumer.java`)

**三级审核结果**:
| 结果 | 条件 | 处理 |
|------|------|------|
| `PASS` | 无敏感词，内容 > 10 字 | 正常展示 |
| `REVIEW` | 含可疑词（广告/推广等）或内容过短 | 标记待人工复核 |
| `BLOCK` | 含敏感词（违法/赌博等） | 标记 + 自动逻辑删除 |

```java
private AuditResult audit(String title, String content) {
    String fullText = (title + " " + content).toLowerCase();

    if (content == null || content.trim().length() < 10) {
        return AuditResult.REVIEW.withReason("内容过短");
    }
    for (String word : SENSITIVE_WORDS) {
        if (fullText.contains(word)) {
            return AuditResult.BLOCK.withReason("敏感词: " + word);
        }
    }
    for (String word : SUSPICIOUS_WORDS) {
        if (fullText.contains(word.toLowerCase())) {
            return AuditResult.REVIEW.withReason("可疑词: " + word);
        }
    }
    return AuditResult.PASS;
}
```

**违规自动删除**:
```java
case BLOCK -> {
    article.setAuditResult("BLOCK");
    article.setDeleted(1);            // MyBatis-Plus @TableLogic 逻辑删除
    articleMapper.updateById(article);
}
```

### 3.3 RabbitMQ 绑定修复 (`RabbitMQConfig.java`)

```java
// 原有：仅 tagQueue 收到 publish 消息
@Bean
public Binding publishToTagBinding() {
    return BindingBuilder.bind(tagQueue())
            .to(articleTopicExchange())
            .with(PUBLISH_ROUTING_KEY);          // article.publish → tagQueue
}

// Stage 7 新增：complianceQueue 也收到 publish 消息
@Bean
public Binding publishToComplianceBinding() {
    return BindingBuilder.bind(complianceQueue())
            .to(articleTopicExchange())
            .with(PUBLISH_ROUTING_KEY);          // article.publish → complianceQueue
}
```

**设计原理**: 发送方使用 `article.publish` 路由键发送一条消息，Topic 交换机根据 binding 规则将消息同时路由到 `tagQueue` 和 `complianceQueue`。这与 Fanout 交换机的广播效果相同，但保留了未来按 routing key 精确路由的灵活性（如仅重新提取标签而不触发合规检测）。

---

## 4. 配置说明

### 4.1 RabbitMQ 连接池配置

两个消费者的 `application.yml` 中 RabbitMQ 配置：

```yaml
spring:
  rabbitmq:
    listener:
      simple:
        prefetch: 1          # 公平分发：每次只取 1 条
        retry:
          enabled: false      # 禁用 Spring 自动重试（手动 ACK/Nack）
        default-requeue-rejected: false  # Nack 后不重新入队
```

**`prefetch = 1` 的重要性**: 配合 `concurrency = "5"`，5 个线程各自一次只取一条消息。处理快的线程可以更快地取到下一条，实现自然的负载均衡。

### 4.2 数据库连接池（轻量配置）

消费者服务不需要高并发数据库连接：

```yaml
druid:
  initial-size: 2    # 标签提取/合规检测是低频操作
  min-idle: 2
  max-active: 10     # 最大 10 连接（两个消费者共享）
```

---

## 5. 验证方式

### 5.1 服务启动验证

```bash
# 1. 启动 RabbitMQ 管理界面
# 访问 http://localhost:15672 (guest/guest)
# 确认 Exchanges 中有 article.topic.exchange
# 确认 Queues 中有 article.tag.queue 和 article.compliance.queue

# 2. 启动消费者服务
docker-compose up -d tag-extract-service compliance-service
docker-compose logs -f tag-extract-service compliance-service
```

### 5.2 端到端测试

```bash
# 1. 登录获取 Token
TOKEN=$(curl -s -X POST http://localhost:8080/api/user/login \
  -H "Content-Type: application/json" \
  -d '{"username":"testuser","password":"test123456"}' | jq -r '.data.token')

# 2. 创建并发布一篇文章
ARTICLE_ID=$(curl -s -X POST http://localhost:8080/api/articles \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"title":"Spring Boot微服务架构实践","content":"本文介绍如何使用Spring Boot和Docker构建微服务架构，包括服务注册、配置中心、API网关等核心组件的实践方法。"}' \
  | jq -r '.data.id')

curl -s -X PUT "http://localhost:8080/api/articles/$ARTICLE_ID/publish" \
  -H "Authorization: Bearer $TOKEN"

# 3. 等待消费者处理（约 1-2 秒）
sleep 2

# 4. 查看文章 — 验证标签已提取
curl -s "http://localhost:8080/api/articles/$ARTICLE_ID" \
  -H "Authorization: Bearer $TOKEN" | jq '.data | {title, tags, auditResult}'
# 预期:
# {
#   "title": "Spring Boot微服务架构实践",
#   "tags": "Spring,微服务,Spring Boot,后端开发,Docker",
#   "auditResult": "PASS"
# }
```

### 5.3 合规检测 — 违规内容测试

```bash
# 发布含敏感词的文章
BAD_ID=$(curl -s -X POST http://localhost:8080/api/articles \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"title":"测试文章","content":"这是一篇包含违规和赌博内容的测试文章"}' \
  | jq -r '.data.id')

curl -s -X PUT "http://localhost:8080/api/articles/$BAD_ID/publish" \
  -H "Authorization: Bearer $TOKEN"

sleep 2

# 查看文章 — 验证被拦截
curl -s "http://localhost:8080/api/articles/$BAD_ID" \
  -H "Authorization: Bearer $TOKEN" | jq '.data.auditResult'
# 预期: "BLOCK"

# 验证文章列表不再包含此文（已逻辑删除）
curl -s "http://localhost:8080/api/articles?page=1&size=20" | jq '.data[] | select(.id == '$BAD_ID')'
# 预期: 无结果
```

### 5.4 RabbitMQ 管理界面验证

1. 访问 `http://localhost:15672` → Queues
2. 查看 `article.tag.queue` 和 `article.compliance.queue`：
   - 发布文章前：Ready = 0, Total = 0
   - 发布文章后：Ready 短暂变为 1 → 消费者处理 → 变为 0
3. 查看 Message rates 图表确认消费速率

### 5.5 并发验证

```bash
# 快速连续发布 10 篇文章
for i in $(seq 1 10); do
  AID=$(curl -s -X POST http://localhost:8080/api/articles \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $TOKEN" \
    -d "{\"title\":\"并发测试文章$i\",\"content\":\"这是第${i}篇并发测试文章的内容。\"}" \
    | jq -r '.data.id')
  curl -s -X PUT "http://localhost:8080/api/articles/$AID/publish" \
    -H "Authorization: Bearer $TOKEN" > /dev/null &
done
wait

# 查看消费者日志 — 5 个线程并发处理
docker-compose logs tag-extract-service | grep "标签提取完成"
docker-compose logs compliance-service | grep "合规检测通过"
```

### 5.6 消息可靠性验证

```bash
# 1. 停止消费者
docker-compose stop tag-extract-service compliance-service

# 2. 发布文章（消息堆积在队列）
curl -s -X PUT "http://localhost:8080/api/articles/$ARTICLE_ID/publish" \
  -H "Authorization: Bearer $TOKEN"

# 3. RabbitMQ 管理界面查看 Queues → Ready = 1

# 4. 重启消费者 — 消息被消费
docker-compose start tag-extract-service compliance-service
# 预期: 日志显示消费者处理了积压消息
```

---

## 6. 架构决策记录 (ADR)

### ADR-006: 消息路由 — Topic Exchange vs Fanout Exchange

**决策**: 使用 Topic Exchange + 显式 binding，而非 Fanout。

**理由**:
1. Topic Exchange 在保持广播能力的同时，支持按 routing key 精确路由
2. 未来可能需要仅重新提取标签而不触发合规检测（发送到 `article.tag`）
3. 未来可能新增第三条消费者链路（如统计/推荐），只需新增 binding 即可
4. 向后兼容：已有 `article.publish` 路由键的发送逻辑无需改动

**代价**:
1. 需要维护多个 binding 声明（当前 4 个 binding）
2. routing key 需保持一致性

### ADR-007: 手动 ACK vs 自动 ACK

**决策**: 手动 ACK (`ackMode = "MANUAL"`)。

**理由**:
1. 自动 ACK 在消息投递到 Listener 方法后立即确认——如果方法执行失败（DB 异常），消息已丢失
2. 手动 ACK 允许在 DB 回写成功后才确认，保证 at-least-once 语义
3. 失败时使用 `basicNack(deliveryTag, false, false)` 不重新入队，避免死循环

**代价**:
1. 需要在每个 Listener 方法中显式调用 `channel.basicAck()`
2. 需处理 `basicNack` 的 IOException

### ADR-008: 消费者独立部署 vs 集成在 article-service 中

**决策**: 独立部署为 `tag-extract-service` 和 `compliance-service`。

**理由**:
1. 独立部署可以独立扩缩容——合规检测可能是瓶颈（外部 API 调用），需要更多实例
2. 故障隔离——标签提取失败不影响文章服务主流程
3. 按 docker-compose 文档的微服务划分保持一致
4. 未来可替换为完全不同的技术栈（如 Python NLP 服务）

**代价**:
1. 增加了 2 个需要维护的微服务
2. 每个消费者都需配置数据库连接（用于回写结果）
3. 如果改为通过 REST API 回写而非直接写 DB，可减少 DB 连接数

---

## 7. 修改文件清单

| 文件 | 操作 | 说明 |
|------|------|------|
| `pom.xml` (父) | 修改 | 新增 `tag-extract-service`、`compliance-service` 模块 |
| `article-service/.../config/RabbitMQConfig.java` | 修改 | 新增 `publishToComplianceBinding` 绑定 |
| `docker-compose.yml` | 修改 | 新增两个消费者服务定义 |
| **tag-extract-service/** | **新建模块** | 含 6 个文件 |
| `├── pom.xml` | 新建 | Maven 依赖 |
| `├── Dockerfile` | 新建 | 容器镜像 |
| `├── src/.../TagExtractApplication.java` | 新建 | Spring Boot 启动类 |
| `├── src/.../consumer/TagExtractConsumer.java` | 新建 | 标签提取消费者 (5 并发) |
| `├── src/.../mapper/ArticleMapper.java` | 新建 | MyBatis-Plus Mapper |
| `└── src/.../resources/application.yml` | 新建 | 配置 (端口 9010) |
| **compliance-service/** | **新建模块** | 含 6 个文件 |
| `├── pom.xml` | 新建 | Maven 依赖 |
| `├── Dockerfile` | 新建 | 容器镜像 |
| `├── src/.../ComplianceServiceApplication.java` | 新建 | Spring Boot 启动类 |
| `├── src/.../consumer/ComplianceCheckConsumer.java` | 新建 | 合规检测消费者 (5-10 并发) |
| `├── src/.../mapper/ArticleMapper.java` | 新建 | MyBatis-Plus Mapper |
| `└── src/.../resources/application.yml` | 新建 | 配置 (端口 9011) |
| `开发日志/stage7-异步广播.md` | 新建 | 开发日志 |

**总计: 3 修改 + 2 新模块 (12 文件) + 1 日志**

---

## 8. 后续优化方向 (Stage 9 配合)

- **死信队列 (DLQ)**: 消费失败的消息转入 DLQ，避免直接丢弃
- **消息重试策略**: 指数退避重试 + 最大重试次数
- **外部 AI 服务对接**: 标签提取接入 NLP API（HanLP/Jieba），合规检测接入阿里云/网易内容安全
- **结果通知**: 审核结果为 BLOCK 时通过 WebSocket/站内信通知作者
- **监控接入**: 队列深度、消费速率、处理延迟上报 Prometheus
- **幂等保证**: 消息去重（Redis 记录已处理的 articleId + version），防止重复消费

---

## 9. 总结

Stage 7 完成了基于 **RabbitMQ Topic Exchange** 的长文异步广播处理系统。核心特性：

1. **并行独立消费**: 标签提取和合规检测两个队列独立处理，TagExtractConsumer 5 线程 + ComplianceCheckConsumer 5-10 弹性线程
2. **消息可靠性**: 队列/消息持久化 + 手动 ACK，保证 at-least-once 语义
3. **规则化标签提取**: 标题分词 + 内容领域匹配，Top 5 关键词回写 `tags` 字段
4. **三级内容审核**: PASS / REVIEW / BLOCK，违规内容自动逻辑删除
5. **容错设计**: 消费失败不重新入队（避免死循环），Nack 后记录日志

文章发布后，标签提取和合规检测在 **亚秒级** 内并行完成，互不影响。
