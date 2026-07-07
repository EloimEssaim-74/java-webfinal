# Stage 10: 全流程测试 — 问题排查报告

> **日期**: 2026-07-04  
> **状态**: 前端已完成，发现 5 个后端问题待修复

---

## 当前进度

### ✅ 已完成

| 项目 | 状态 | 说明 |
|------|------|------|
| 前端 SPA (5文件) | ✅ | index.html + CSS + 3 JS，知乎风格 |
| Nginx 静态服务 | ✅ | `location /` → `frontend/`，SPA fallback |
| Docker 部署 | ✅ | docker-compose nginx 挂载 frontend volume |
| 文章公开浏览 | ✅ | GET /api/articles 无需登录（JwtAuthFilter 已修复） |
| Dockerfile 镜像 | ✅ | `eclipse-temurin:17-jdk-alpine` 替代已废弃的 openjdk |
| docker-compose | ✅ | 移除废弃 `version` 字段 |
| CORS | ✅ | 网关全开放，前端同源无需跨域 |

### ❌ 待修复

| # | 问题 | 严重级 | 影响模块 |
|---|------|--------|---------|
| 1 | **Like 返回 500** | P0 | interact-service |
| 2 | **AI 服务启动失败** | P0 | ai-assistant-service |
| 3 | **HTTP 状态码不正确** | P1 | user/article/interact |
| 4 | **tags/auditResult 始终为 null** | P2 | tag-extract/compliance |
| 5 | **注销后黑名单失效** | P3 | gateway(JwtAuthFilter) |

---

## 问题详情

### 🔴 P0-1: Like 接口返回 HTTP 500

**现象**: `POST /api/articles/{id}/like` 始终返回 `{"code":500,"message":"internal server error"}`

**测试命令**:
```bash
TOKEN=$(curl -s -X POST http://localhost/api/user/login -H "Content-Type: application/json" -d '{"username":"itest_a","password":"itest123"}' | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['token'])")
curl -s -X POST "http://localhost/api/articles/1/like" -H "Authorization: Bearer $TOKEN"
# → {"code":500,"message":"internal server error"}
```

**根因分析**: `LikeServiceImpl.likeArticle()` 中调用 `recordLike` → `HotArticleService.recordLike()` → `publishRefresh()` 时，`convertAndSend` 操作可能抛出未捕获异常。或者 `redisTemplate.opsForZSet().incrementScore()` 返回的 Key 空间不是 String 类型导致类型冲突。

**修复方向**:
1. 在 `LikeServiceImpl` 的 `publishRefresh` 中已有 try-catch，但 `ZINCRBY` 操作没有
2. 检查 `StringRedisTemplate.opsForZSet()` 与 Redis 数据类型兼容性
3. 添加日志输出具体异常堆栈

---

### 🔴 P0-2: AI Assistant Service 启动失败

**现象**: `kb-ai-assistant` 容器反复退出，状态 `Exited(1)`

**错误日志**:
```
APPLICATION FAILED TO START
Description:
Failed to configure a DataSource: 'url' attribute is not specified and no embedded datasource could be configured.
```

**根因**: `ai-assistant-service` 依赖了 common 模块，common 模块包含 `mybatis-plus-spring-boot3-starter`。MyBatis-Plus 自动配置会尝试创建 DataSource，但 AI 服务不需要数据库（纯 WebFlux + LLM API 调用）。

**修复方案**: 已在 `AiAssistantApplication.java` 中排除 `DataSourceAutoConfiguration`：
```java
@SpringBootApplication(
    scanBasePackages = "com.kb",
    exclude = {DataSourceAutoConfiguration.class}
)
```
**需要**: 重新 `mvn package -pl ai-assistant-service` + `docker-compose up -d --build ai-assistant-service`

---

### 🟡 P1: HTTP 状态码与业务码不一致

**现象**: 业务错误时 HTTP 状态码始终为 200，仅 JSON body 中的 `code` 字段正确

**测试用例**:

| 场景 | 预期 HTTP | 实际 HTTP | Body code |
|------|----------|----------|-----------|
| 重复注册 | 400 | 200 | 400 |
| 错误密码 | 401 | 200 | 401 |
| 越权修改 | 403 | 200 | 403 |
| 重复点赞 | 400 | 200 | 400 |

**根因**: `GlobalExceptionHandler` 在各服务中捕获 `BusinessException` 后调用 `Result.error()`，但 `@ExceptionHandler` 方法没有设置 `HttpServletResponse.setStatus()`。

**当前代码**（user/article/interact 三处相同）:
```java
@ExceptionHandler(BusinessException.class)
public Result<Void> handleBusinessException(BusinessException e) {
    return Result.error(e.getCode(), e.getMessage());  // ← 未设置 HTTP status
}
```

**修复方案**: 在各服务的 `GlobalExceptionHandler` 中：
```java
@ExceptionHandler(BusinessException.class)
public ResponseEntity<Result<Void>> handleBusinessException(BusinessException e) {
    return ResponseEntity
            .status(e.getCode())          // 设置 HTTP 状态码
            .body(Result.error(e.getCode(), e.getMessage()));
}
```

**影响文件**:
- `user-service/.../config/GlobalExceptionHandler.java`
- `article-service/.../config/GlobalExceptionHandler.java`
- `interact-service/.../config/GlobalExceptionHandler.java`

---

### 🟡 P2: 标签提取和合规检测结果始终为 null

**现象**: 文章发布后 `tags` 和 `auditResult` 字段始终为 `null`

**测试命令**:
```bash
curl -s "http://localhost/api/articles/13" -H "Authorization: Bearer $TOKEN" | jq '.data | {tags,auditResult}'
# → {"tags":null,"auditResult":null}
```

**根因分析**: 两种可能：
1. `tag-extract-service` 和 `compliance-service` 消费消息时写 DB 失败（MySQL 连接问题），但异常被 `basicNack` 不重入队处理了
2. RabbitMQ 绑定问题 — `article.publish` routing key 没有正确路由到两个消费者队列

**修复方向**:
1. 排查 `tag-extract-service` 和 `compliance-service` 的容器日志
2. 检查 RabbitMQ 管理界面确认队列有消费者
3. 验证 binding 关系是否生效（`article.publish → compliance.queue` binding 是 Stage 7 新增的）

---

### 🟢 P3: JWT 注销后 Token 验证失效

**现象**: 注销后的 Token 仍可通过网关认证

**根因**: `JwtAuthFilter` 中的黑名单检查使用了 `reactiveRedisTemplate.hasKey()`，但 Redis 中的 key 为 `token:blacklist:<full_jwt>`。如果 `hasKey` 返回空 Mono，后续 `flatMap` 的 `isBlacklisted` 分支不会执行，Token 被认为是有效的。这是一个响应式编程的 null 处理问题。

**当前代码**:
```java
return redisTemplate.hasKey(blacklistKey)
    .flatMap(isBlacklisted -> { ... });  
    // 如果 hasKey 返回空，flatMap 中的代码不会执行 → Token 验证"通过"
```

**修复方案**: 使用 `defaultIfEmpty(false)` 处理空 Mono：
```java
return redisTemplate.hasKey(blacklistKey)
    .defaultIfEmpty(false)
    .flatMap(isBlacklisted -> {
        if (Boolean.TRUE.equals(isBlacklisted)) { ... }
        ...
    });
```

**影响文件**: `gateway-service/.../filter/JwtAuthFilter.java`

---

## 修改汇总

### 需要改代码的文件

| # | 文件 | 修改内容 | 优先级 |
|---|------|---------|--------|
| 1 | `ai-assistant-service/.../AiAssistantApplication.java` | 排除 DataSourceAutoConfiguration | P0 |
| 2 | `interact-service/.../LikeServiceImpl.java` | 排查 500 根因，加固异常处理 | P0 |
| 3 | `user-service/.../GlobalExceptionHandler.java` | ResponseEntity 设置 HTTP status | P1 |
| 4 | `article-service/.../GlobalExceptionHandler.java` | 同上 | P1 |
| 5 | `interact-service/.../GlobalExceptionHandler.java` | 同上 | P1 |
| 6 | `gateway-service/.../JwtAuthFilter.java` | `defaultIfEmpty(false)` 修复黑名单 | P3 |
| 7 | `gateway-service/.../JwtAuthFilter.java` | GET /api/articles 公开浏览（已改需编译） | P0 |

### 不需要改代码的验证项

| # | 验证项 | 方法 |
|---|--------|------|
| 8 | tag-extract/compliance 消费者 | 检查容器日志，确认 RabbitMQ binding 生效 |

---

## 已验证通过的项目

| # | 测试项 | 结果 |
|---|--------|------|
| 1 | Nginx 静态文件 (HTML/CSS/JS) | ✅ HTTP 200 |
| 2 | SPA fallback (/any → index.html) | ✅ HTTP 200 |
| 3 | GET /api/articles 公开访问 | ✅ HTTP 200 (9篇文章) |
| 4 | GET /api/trending 公开访问 | ✅ HTTP 200 (5条热搜) |
| 5 | GET /api/articles/1 公开访问 | ✅ HTTP 200 |
| 6 | POST /api/user/login | ✅ HTTP 200, JWT返回 |
| 7 | POST /api/user/register | ✅ HTTP 200 |
| 8 | POST /api/articles (创建文章) | ✅ HTTP 200 |
| 9 | PUT /api/articles/{id}/publish | ✅ HTTP 200 |
| 10 | POST like 无认证 → 401 | ✅ (网关正确拦截) |
| 11 | Gzip 压缩 | ✅ Content-Encoding: gzip |
| 12 | 前端 JS 文件加载 | ✅ /js/app.js 200 |
