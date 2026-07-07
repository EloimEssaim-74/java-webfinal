# Stage 8: AI流式创作辅助 (WebFlux + SSE + Resilience4j)

> **日期**: 2026-07-02  
> **工时**: 3天 (自主完成)  
> **里程碑**: SSE 流式逐字推送 AI 续写内容，含熔断降级与 Demo 模式

---

## 1. 技术选型

### 1.1 为什么是 WebFlux 而非 MVC

| 维度 | Spring MVC (Tomcat) | Spring WebFlux (Netty) |
|------|---------------------|------------------------|
| 线程模型 | 每请求一线程 (200 上限) | 事件驱动 (少量线程) |
| SSE 长连接 | 线程阻塞等待，耗尽线程池 | 事件循环，天然非阻塞 |
| 流式处理 | 需轮询或 CompletableFuture | Reactor Stream 原生支持 |
| 内存消耗 | 每连接 ~1MB 栈 | 每连接 ~KB 级 |
| 适用场景 | CRUD REST API | 流式/长连接/高并发 |

**结论**: AI 续写接口需要维持 SSE 长连接（数十秒到数分钟），Tomcat 线程模型会在几十个并发连接时耗尽线程池。WebFlux 基于 Netty 事件循环，数千 SSE 连接仅需少量线程。

### 1.2 为什么是 SSE 而非 WebSocket

| 维度 | SSE | WebSocket |
|------|-----|-----------|
| 通信方向 | 单向（服务端→客户端） | 双向 |
| 协议 | HTTP/1.1 长连接 | 升级为 ws:// |
| 浏览器 API | `EventSource`（自动重连） | `WebSocket`（手动处理） |
| 网关兼容 | ✅ SCG 透明代理 | ⚠️ 需配置 WebSocket 路由 |
| 本场景匹配 | ✅ AI 只需推送续写文本 | ❌ 不需要客户端回传 |

### 1.3 关键依赖

```xml
<!-- WebFlux: 响应式 Web 框架 -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webflux</artifactId>
</dependency>

<!-- Resilience4j: 响应式熔断器 -->
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-circuitbreaker-reactor-resilience4j</artifactId>
</dependency>

<!-- Sentinel: 响应式并发限流适配器 -->
<dependency>
    <groupId>com.alibaba.csp</groupId>
    <artifactId>sentinel-reactor-adapter</artifactId>
</dependency>
```

---

## 2. 核心架构

### 2.1 请求流

```
Client
  │  POST /api/ai/continue  {"context":"上文..."}
  │  Accept: text/event-stream
  ▼
┌─────────────────────────────────────────────────┐
│ API Gateway                                      │
│ - JWT 校验（路径不在白名单，自动拦截）            │
│ - 路由到 lb://ai-assistant-service              │
│ - response-timeout: 300s（覆盖默认超时）          │
│ - SSE 透明转发（Gateway 不缓冲响应体）            │
└──────────────────┬──────────────────────────────┘
                   │
                   ▼
┌──────────────────────────────────────────────────┐
│ ai-assistant-service (Netty, port 9012)          │
│                                                   │
│  AiContinueController                             │
│    │ produces = text/event-stream                 │
│    ▼                                              │
│  OpenAiStreamServiceImpl                          │
│    │                                               │
│    ├─ 1. 构建 Prompt                              │
│    │     system: "你是专业的内容创作助手..."       │
│    │     user:   context                           │
│    │                                               │
│    ├─ 2. WebClient POST /v1/chat/completions      │
│    │     {stream:true}                             │
│    │                                               │
│    ├─ 3. bodyToFlux → 逐行解析 SSE                 │
│    │     data: {...} → delta.content               │
│    │                                               │
│    ├─ 4. Flux<ServerSentEvent> 推送                │
│    │     + heartbeat(15s)                          │
│    │     + timeout(120s)                           │
│    │     + circuitBreaker                          │
│    │                                               │
│    └─ 5. doOnCancel → 取消 LLM 请求                │
│                                                   │
│  降级: Demo 模式 / 熔断降级                        │
└──────────────────────────────────────────────────┘
```

### 2.2 Demo 模式 vs 真实模式

```
ai.demo-mode = false 且 api-key 非空
  → WebClient → OpenAI API → 流式解析 → SSE

ai.demo-mode = true 或 api-key 为空
  → 预置文本 → 每 200ms 推送 2-4 字 → SSE
```

**Demo 模式价值**:
- 无 API Key 时依然可启动和测试
- 前端开发无需等待 LLM 响应
- 演示/教学场景

### 2.3 熔断状态机

```
         ┌──────────┐
         │  CLOSED  │ 正常调用
         └─────┬────┘
               │ 滑动窗口 10 次，失败率 >= 50%
               ▼
         ┌──────────┐
         │   OPEN   │ 熔断 — 直接降级
         └─────┬────┘
               │ 等待 30 秒
               ▼
         ┌──────────┐
         │ HALF_OPEN│ 允许 3 次探测
         └──┬───┬───┘
      成功  │   │  失败
            ▼   ▼
      CLOSED   OPEN
```

### 2.4 SSE 事件流结构

```
time ──────────────────────────────────────────────▶

data: 这是

data: 一段

:keepalive          ← heartbeat (15s, 浏览器不可见)

data: AI续写

data: 内容

data: [DONE]        ← 流结束标记
```

---

## 3. 关键代码实现

### 3.1 控制器 — SSE 端点 (`AiContinueController.java`)

```java
@PostMapping(value = "/api/ai/continue", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<String>> continueStream(
        @Valid @RequestBody ContinueRequest request,
        @RequestHeader(value = JwtConstants.HEADER_USER_ID, defaultValue = "0") Long userId) {

    return aiStreamService.continueStream(request.getContext(), userId)
            .doOnComplete(() -> log.info("续写完成"))
            .doOnCancel(() -> log.info("客户端断开"))
            .doOnError(e -> log.error("续写异常: {}", e.getMessage()));
}
```

**关键点**:
- `produces = TEXT_EVENT_STREAM_VALUE` — 设置 Content-Type 为 `text/event-stream`
- 返回 `Flux<ServerSentEvent<String>>` — Spring WebFlux 自动将 Flux 序列化为 SSE
- `doOnCancel` — 客户端断开时触发（浏览器关闭页面、网络中断等），可在此取消上游 LLM 请求

### 3.2 核心服务 — OpenAI 流式调用 (`OpenAiStreamServiceImpl.java`)

```java
private Flux<ServerSentEvent<String>> callOpenAiStream(String context) {
    return webClient.post()
            .uri("/v1/chat/completions")
            .bodyValue(buildRequestBody(context))
            .accept(MediaType.TEXT_EVENT_STREAM)
            .retrieve()
            .bodyToFlux(String.class)                          // 逐行读取 SSE
            .filter(line -> line.startsWith("data:"))          // 过滤非数据行
            .map(line -> line.substring(5).trim())            // 去掉 "data:"
            .filter(data -> !"[DONE]".equals(data))            // 过滤结束标记
            .map(this::extractContent)                         // JSON → 文本
            .filter(token -> !token.isEmpty())
            .map(token -> ServerSentEvent.<String>builder()    // 包装 SSE
                    .data(token).build())
            .mergeWith(heartbeat())                            // 合并心跳
            .timeout(Duration.ofSeconds(120))                  // 超时保护
            .doOnCancel(() -> log.debug("LLM请求取消"));
}
```

**Reactor 操作链解析**:

| 操作 | 类型 | 作用 |
|------|------|------|
| `bodyToFlux(String.class)` | 源 | 逐行获取 LLM 返回的 SSE 数据 |
| `filter("data:")` | 过滤 | 跳过空行和注释行 |
| `map(substring)` | 转换 | 去掉 `data:` 前缀 |
| `filter(!DONE)` | 过滤 | 识别流结束信号 |
| `extractContent` | 解析 | JSON → `choice.delta.content` |
| `map(build SSE)` | 包装 | 构造标准 SSE 事件 |
| `mergeWith(heartbeat)` | 合并 | 插入 15s 心跳 |
| `timeout(120s)` | 保护 | 超时自动取消 |
| `doOnCancel` | 回调 | 清理资源 |

### 3.3 Delta Content 提取

```java
private String extractContent(String data) {
    JsonNode root = objectMapper.readTree(data);
    JsonNode choices = root.path("choices");
    if (choices.isArray() && !choices.isEmpty()) {
        JsonNode delta = choices.get(0).path("delta");
        JsonNode content = delta.path("content");
        if (!content.isMissingNode()) {
            return content.asText();
        }
    }
    return "";
}
```

**输入示例**:
```json
{"id":"chatcmpl-xxx","choices":[{"delta":{"content":"这是"},"index":0}]}
```
**输出**: `"这是"`

### 3.4 Demo 模式 — 打字效果模拟

```java
private Flux<ServerSentEvent<String>> demoStream() {
    String text = String.join("", DEMO_SENTENCES);

    return Flux.fromStream(chunkText(text, 2, 4).stream())
            .delayElements(Duration.ofMillis(200))        // 每 200ms 推一个片段
            .map(chunk -> ServerSentEvent.<String>builder()
                    .data(chunk).build())
            .concatWith(Mono.just(
                    ServerSentEvent.<String>builder()
                            .data("[DONE]").build()));
}
```

### 3.5 心跳保活

```java
private Flux<ServerSentEvent<String>> heartbeat() {
    return Flux.interval(Duration.ofSeconds(15))
            .map(i -> ServerSentEvent.<String>builder()
                    .comment("keepalive")    // SSE 注释（浏览器不可见）
                    .build());
}
```

**为什么需要心跳**: LLM 在开始生成前有 2-10 秒的"思考时间"，此期间无数据输出。如果网关/代理的 idle timeout < 思考时间，连接会被断开。15 秒间隔的心跳注释可以保持 TCP 连接活跃。

### 3.6 熔断降级

```java
return circuitBreaker.run(
    callOpenAiStream(context),          // 正常路径
    throwable -> fallbackStream(throwable)  // 降级路径
);
```

降级输出单条 SSE 事件后自动结束流。

---

## 4. 配置说明

### 4.1 环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `OPENAI_BASE_URL` | `https://api.openai.com` | LLM API 地址 |
| `OPENAI_API_KEY` | (空) | API 密钥，空时自动启用 Demo |
| `OPENAI_MODEL` | `gpt-3.5-turbo` | 模型名称 |
| `AI_DEMO_MODE` | `false` | 强制 Demo 模式 |

**兼容的 LLM 端点**（所有 OpenAI 兼容 API）:
- OpenAI: `https://api.openai.com`
- DeepSeek: `https://api.deepseek.com`
- Moonshot: `https://api.moonshot.cn`
- 本地 Ollama: `http://localhost:11434` (model: `qwen2:7b` 等)

### 4.2 Resilience4j 熔断参数

```yaml
resilience4j:
  circuitbreaker:
    instances:
      openai-api:
        sliding-window-size: 10          # 统计最近 10 次调用
        failure-rate-threshold: 50        # 50% 失败率触发熔断
        wait-duration-in-open-state: 30s  # 熔断 30 秒
        permitted-number-of-calls-in-half-open-state: 3
```

---

## 5. 验证方式

### 5.1 Demo 模式（无需 API Key）

```bash
# 启动服务
docker-compose up -d ai-assistant-service

# 测试流式续写（Demo 模式自动启用）
curl -N -X POST http://localhost:8080/api/ai/continue \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <TOKEN>" \
  -d '{"context":"人工智能技术正在深刻改变"}'
```

**预期输出**:
```
data:让我来为你展开分析这个问题。

data:从多个角度来看，这个主题有着...

data:[DONE]
```
每个 `data:` 行间隔约 200ms，模拟打字效果。

### 5.2 真实模式（需 API Key）

```bash
# 设置环境变量
export OPENAI_API_KEY="sk-..."

# 启动
OPENAI_API_KEY=$OPENAI_API_KEY docker-compose up -d ai-assistant-service

# 测试
curl -N -X POST http://localhost:8080/api/ai/continue \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <TOKEN>" \
  -d '{"context":"请介绍一下微服务架构的核心设计原则"}'
```

### 5.3 超时/断连测试

```bash
# 发送请求后按 Ctrl+C 中断
# 观察日志: "客户端断开连接"
docker-compose logs -f ai-assistant-service | grep "断开"
```

### 5.4 熔断触发测试

```bash
# 配置错误的 API Key
OPENAI_API_KEY="sk-invalid" docker-compose up -d ai-assistant-service

# 连续发送 5+ 次请求（触发 50% 失败率）
for i in $(seq 1 5); do
  curl -N -X POST http://localhost:8080/api/ai/continue \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer <TOKEN>" \
    -d '{"context":"test"}' --max-time 10 &
done

# 第 6 次请求应返回降级文案:
# data:AI 服务暂时不可用，请稍后再试。
```

### 5.5 浏览器测试

```html
<script>
const eventSource = new EventSource('/api/ai/continue');  // 不支持 POST
// 使用 fetch + ReadableStream 替代:
fetch('/api/ai/continue', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ context: '今天天气真好' })
}).then(response => {
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  function read() {
    reader.read().then(({done, value}) => {
      if (done) return;
      console.log(decoder.decode(value));  // 逐块输出
      read();
    });
  }
  read();
});
</script>
```

---

## 6. 架构决策记录 (ADR)

### ADR-009: 响应式架构 — WebFlux vs MVC

**决策**: ai-assistant-service 使用 Spring WebFlux（响应式）。

**理由**:
1. SSE 长连接与 Tomcat 一线程一连接模型冲突
2. 流式解析 LLM 响应需要 Reactor 的 `Flux` 语义
3. 非阻塞 I/O 天然适合代理/转发场景
4. 与 Gateway（已是 WebFlux）技术栈一致

**代价**:
1. 团队需掌握响应式编程范式（与其余 5 个 MVC 服务不同）
2. 调试响应式链比同步代码复杂

### ADR-010: Demo 模式 — 本地模拟 vs 必须依赖外部 API

**决策**: 提供 Demo 模式，API Key 为空时自动启用。

**理由**:
1. 降低开发门槛 — 前端/测试可工作于无 API Key 环境
2. 演示/教学场景无需真实 LLM
3. 环境配置缺失时提供降级而非报错

**代价**:
1. Demo 输出仅 8 句预置文本循环，无法验证真实 AI 质量
2. 需要维护两套输出逻辑

### ADR-011: 熔断器 — Resilience4j vs Sentinel

**决策**: 使用 Resilience4j CircuitBreaker 做 LLM API 熔断。

**理由**:
1. 原生 Reactor 支持（`ReactiveCircuitBreaker`）
2. Spring Cloud Circuit Breaker 抽象层统一配置
3. Sentinel 在此服务中用于并发连接数限制（不同维度的保护）

**层次划分**:
- **Sentinel**: 限制对 ai-assistant-service 的并发请求数（保护服务自身）
- **Resilience4j**: 限制对 LLM API 的调用（保护下游依赖）

---

## 7. 修改文件清单

| 文件 | 操作 | 说明 |
|------|------|------|
| `pom.xml` (父) | 修改 | 新增 `ai-assistant-service` 模块 |
| `gateway-service/.../application.yml` | 修改 | 新增 `/api/ai/**` 路由（response-timeout: 300s） |
| `docker-compose.yml` | 修改 | 新增 ai-assistant-service (环境变量注入) |
| **ai-assistant-service/** | **新建模块** | 含 10 个文件 |
| `├── pom.xml` | 新建 | WebFlux + Resilience4j + Sentinel-Reactor |
| `├── Dockerfile` | 新建 | 容器镜像 |
| `├── .../AiAssistantApplication.java` | 新建 | 启动类（无 DB/Redis 依赖） |
| `├── .../dto/ContinueRequest.java` | 新建 | `{context: String}` |
| `├── .../controller/AiContinueController.java` | 新建 | `POST /api/ai/continue` SSE 端点 |
| `├── .../service/AiStreamService.java` | 新建 | 服务接口 |
| `├── .../service/impl/OpenAiStreamServiceImpl.java` | 新建 | 核心实现（~220 行） |
| `├── .../config/AiConfig.java` | 新建 | `@ConfigurationProperties` AI 配置 |
| `├── .../config/WebClientConfig.java` | 新建 | Reactor Netty 连接池 |
| `└── .../resources/application.yml` | 新建 | 端口 9012 + 熔断参数 |
| `开发日志/stage8-AI流式续写.md` | 新建 | 开发日志 |

**总计: 3 修改 + 1 新模块 (10 文件) + 1 日志**

---

## 8. 后续优化方向

- **Token 用量统计**: 记录每次调用的 prompt_tokens + completion_tokens，做成本分析
- **流式限流**: 基于 Sentinel 的 `SentinelReactorTransformer` 限制并发 SSE 连接数
- **上下文管理**: 支持多轮对话（messages 数组而非单条上下文）
- **流式文本后处理**: Markdown 渲染、敏感词过滤等后处理 pipeline
- **缓存复用**: 相同上下文返回缓存结果（Redis 缓存首次生成内容）
- **多模型支持**: 通过请求参数动态选择模型

---

## 9. 总结

Stage 8 完成了一个基于 **Spring WebFlux + SSE + Resilience4j** 的 AI 流式续写服务。核心特性：

1. **响应式非阻塞**: Netty 事件循环，单实例支撑数千 SSE 并发连接
2. **流式推送**: SSE 逐 token 推送，客户端体验"打字机效果"
3. **双层保护**: Resilience4j 熔断 LLM API + Sentinel 限流并发连接
4. **心跳保活**: 15s 间隔 SSE 注释，防止网关/代理超时断连
5. **Demo 模式**: 无 API Key 时自动降级为本地模拟输出
6. **OpenAI 兼容**: 支持所有 OpenAI 兼容端点（OpenAI/DeepSeek/Moonshot/Ollama）
