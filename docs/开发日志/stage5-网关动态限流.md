# Stage 5: 网关动态限流 (Sentinel + Nacos)

> **日期**: 2026-06-30  
> **工时**: 2天 (AI辅助 → 自主完成过渡)  
> **里程碑**: 限流规则动态生效，无需重启网关

---

## 1. 技术选型

### 1.1 为什么选 Sentinel

| 对比维度 | Sentinel | Hystrix | Resilience4j | Guava RateLimiter |
|----------|----------|---------|--------------|-------------------|
| 网关集成 | ✅ 原生 Gateway 适配器 | ❌ 已停维 | ⚠️ 需手动适配 | ❌ 单机令牌桶 |
| 动态规则 | ✅ Nacos/Apollo/ZK | ❌ 需重启 | ❌ 需重启 | ❌ 硬编码 |
| 控制台 | ✅ Dashboard 实时监控 | ✅ Dashboard | ❌ 无 | ❌ 无 |
| 响应式支持 | ✅ WebFlux 原生 | ❌ Servlet | ✅ | N/A |
| 中文社区 | ✅ 阿里开源 | ⚠️ Netflix 归档 | ✅ | ✅ |

**结论**: 项目技术栈为 Spring Cloud Alibaba + Nacos，Sentinel 是唯一提供 **Gateway 响应式适配 + Nacos 动态数据源** 双重支持的方案。

### 1.2 关键依赖

```xml
<!-- Sentinel 网关适配器（响应式） -->
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-alibaba-sentinel-gateway</artifactId>
</dependency>

<!-- Sentinel Nacos 数据源（动态规则推送） -->
<dependency>
    <groupId>com.alibaba.csp</groupId>
    <artifactId>sentinel-datasource-nacos</artifactId>
</dependency>
```

版本由父 POM 的 `spring-cloud-alibaba-dependencies` BOM（`2023.0.1.2`）统一管理，对应 Sentinel `1.8.6`。

**依赖传递链**:
```
spring-cloud-alibaba-sentinel-gateway
  ├── sentinel-spring-cloud-gateway-adapter  → 注册 SentinelGatewayFilter (GlobalFilter)
  ├── sentinel-reactor-adapter               → Reactor/Mono 上下文支持
  ├── sentinel-core                           → 限流引擎
  └── spring-cloud-starter-alibaba-sentinel  → Spring Boot 自动配置

sentinel-datasource-nacos
  └── NacosDataSource → 从 Nacos 拉取规则 → GatewayRuleManager
```

---

## 2. 核心架构

### 2.1 过滤器链顺序

```
Client Request
  │
  ▼
┌─────────────────────────────────────┐
│ SentinelGatewayFilter               │  order = HIGHEST_PRECEDENCE (Integer.MIN_VALUE)
│ - 匹配路由级限流规则                  │
│ - 解析 X-Real-IP Header 限流维度     │
│ - 触发限流 → BlockException          │
└──────────────┬──────────────────────┘
               │ 通过
               ▼
┌─────────────────────────────────────┐
│ JwtAuthFilter                       │  order = -100
│ - 路径白名单检查                     │
│ - JWT 解析与校验                     │
│ - Redis 黑名单检查                    │
│ - 角色权限控制                       │
└──────────────┬──────────────────────┘
               │ 通过
               ▼
┌─────────────────────────────────────┐
│ Route → lb://service-name           │
└─────────────────────────────────────┘
```

**设计原因**: 限流必须先于鉴权执行。恶意流量应在最外层被丢弃，避免消耗 JWT 解析、Redis 查询等昂贵的鉴权计算资源。

### 2.2 动态规则推送链路

```
Nacos Console (修改配置)
  │
  ▼ (UDP/HTTP 通知)
NacosDataSource
  │
  ▼ (Converter: JSON → GatewayFlowRule)
SentinelProperty
  │
  ▼ (PropertyListener 回调)
GatewayRuleManager (内存)
  │
  ▼ (路由匹配)
SentinelGatewayFilter (生效)
```

规则变更后 **秒级生效**，无需重启网关。

---

## 3. 关键代码实现

### 3.1 Sentinel 配置 (`application.yml`)

```yaml
spring:
  cloud:
    sentinel:
      # 启动时立即加载规则（默认懒加载，首次请求才初始化）
      eager: true
      # Sentinel Dashboard 地址（可视化监控，可选）
      transport:
        dashboard: ${SENTINEL_DASHBOARD:localhost:8080}
      # Nacos 规则数据源
      datasource:
        gw-flow:                          # 数据源名称（自定义）
          nacos:
            server-addr: ${NACOS_SERVER_ADDR:localhost:8848}
            namespace: ${NACOS_NAMESPACE:kb-platform}
            data-id: gateway-sentinel-rules     # Nacos 配置 Data ID
            group-id: DEFAULT_GROUP
            data-type: json                      # 规则格式
            rule-type: gw-flow                   # 规则类型：网关流控
      # 网关级降级配置（兜底 — 当 BlockRequestHandler bean 不存在时）
      scg:
        fallback:
          mode: response
          response-status: 429
          response-body: '{"code":429,"message":"请求过于频繁，请稍后再试"}'
```

**关键设计点**:
- `eager: true` — 避免首个请求的冷启动延迟
- `data-type: json` — Sentinel 内置 `JsonConverter` 自动解析
- `rule-type: gw-flow` — 规则注入 `GatewayRuleManager`（而非普通 `FlowRuleManager`）
- `scg.fallback` — 声明式兜底方案，即使自定义 `BlockRequestHandler` bean 注册失败也有 JSON 响应

### 3.2 自定义限流响应 (`SentinelBlockHandlerConfig.java`)

Sentinel 默认限流返回 `"Blocked by Sentinel"` 纯文本，与项目统一的 `Result` JSON 格式不一致。通过实现 `BlockRequestHandler` 接口覆盖：

```java
@Slf4j
@Configuration
public class SentinelBlockHandlerConfig {

    @Bean
    public BlockRequestHandler blockRequestHandler() {
        return (exchange, ex) -> {
            // 记录限流日志，包含路径和规则信息
            if (ex instanceof BlockException blockEx) {
                log.debug("Sentinel blocked: path={}, resource={}",
                        exchange.getRequest().getURI().getPath(),
                        blockEx.getRule().getResource());
            }

            // 返回统一的 Result JSON 格式
            return ServerResponse
                    .status(HttpStatus.TOO_MANY_REQUESTS)   // HTTP 429
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Result.error(
                        ResultCode.TOO_MANY_REQUESTS,
                        "请求过于频繁，请稍后再试"));
        };
    }
}
```

**设计要点**:
- `ServerResponse` 是 Spring WebFlux 的响应式响应构建器，与 Gateway 的响应式架构一致
- `.bodyValue()` 自动调用 Jackson 序列化 `Result` 对象
- `BlockRequestHandler` bean 由 Sentinel 自动配置的 `SentinelGatewayBlockExceptionHandler` 自动发现并调用
- 返回体复用 `ResultCode.TOO_MANY_REQUESTS(429, "too many requests")`

### 3.3 编程式兜底 (`SentinelRuleBootstrap.java`)

当声明式数据源自动配置由于版本兼容性问题未生效时，提供编程式兜底方案：

```java
@Slf4j
@Configuration
@ConditionalOnProperty(name = "sentinel.datasource.bootstrap.enabled", havingValue = "true")
public class SentinelRuleBootstrap {

    @Value("${NACOS_SERVER_ADDR:localhost:8848}")
    private String nacosAddr;

    @Value("${NACOS_NAMESPACE:kb-platform}")
    private String namespace;

    @PostConstruct
    public void initGatewayRules() {
        // 1. 定义 JSON → Set<GatewayFlowRule> 转换器
        Converter<String, Set<GatewayFlowRule>> converter = source -> {
            List<GatewayFlowRule> list = new ObjectMapper().readValue(
                    source, new TypeReference<List<GatewayFlowRule>>() {});
            return new HashSet<>(list);
        };

        // 2. 配置 Nacos 连接参数
        Properties props = new Properties();
        props.setProperty("serverAddr", nacosAddr);
        props.setProperty("namespace", namespace);

        // 3. 创建 Nacos 数据源
        NacosDataSource<Set<GatewayFlowRule>> dataSource =
                new NacosDataSource<>(props, "DEFAULT_GROUP",
                        "gateway-sentinel-rules", converter);

        // 4. 注册到 GatewayRuleManager（规则变更自动推送）
        GatewayRuleManager.register2Property(dataSource.getProperty());
    }
}
```

**设计要点**:
- `@ConditionalOnProperty` — 默认不启用，只在声明式方案失效时通过配置激活
- `GatewayRuleManager.register2Property()` — 数据源绑定后，Nacos 配置变更会通过 `PropertyListener` 自动推送
- 使用 Jackson（已有依赖）而非 fastjson（未引入）

---

## 4. Nacos 规则配置

在 Nacos 控制台 (`http://localhost:8848/nacos`) 命名空间 `kb-platform` 下创建：

| 字段 | 值 |
|------|-----|
| Data ID | `gateway-sentinel-rules` |
| Group | `DEFAULT_GROUP` |
| 格式 | JSON |

规则 JSON：

```json
[
  {
    "resource": "user-service",
    "resourceMode": 0,
    "count": 100.0,
    "grade": 1,
    "intervalSec": 1,
    "controlBehavior": 0,
    "burst": 0,
    "paramItem": {
      "parseStrategy": 3,
      "fieldName": "X-Real-IP"
    }
  },
  {
    "resource": "article-service",
    "resourceMode": 0,
    "count": 50.0,
    "grade": 1,
    "intervalSec": 1,
    "controlBehavior": 0,
    "burst": 0,
    "paramItem": {
      "parseStrategy": 3,
      "fieldName": "X-Real-IP"
    }
  },
  {
    "resource": "interact-service",
    "resourceMode": 0,
    "count": 30.0,
    "grade": 1,
    "intervalSec": 1,
    "controlBehavior": 0,
    "burst": 0,
    "paramItem": {
      "parseStrategy": 3,
      "fieldName": "X-Real-IP"
    }
  }
]
```

### 字段说明

| 字段 | 值 | 含义 |
|------|-----|------|
| `resource` | `"user-service"` | 对应 `spring.cloud.gateway.routes[].id`（路由 ID） |
| `resourceMode` | `0` | `0` = 路由 ID 模式 |
| `grade` | `1` | `0` = 线程数限流, `1` = QPS 限流 |
| `count` | `100.0` | 阈值：每秒允许 100 次请求 |
| `intervalSec` | `1` | 统计窗口：1 秒 |
| `controlBehavior` | `0` | `0` = 直接拒绝, `1` = Warm Up, `2` = 匀速排队 |
| `paramItem.parseStrategy` | `3` | `0` = 来源 IP, `1` = RemoteAddr, `2` = URL 参数, `3` = Header |
| `paramItem.fieldName` | `"X-Real-IP"` | 当 `parseStrategy=3` 时，按此 Header 值限流 |

---

## 5. 验证方式

### 5.1 规则创建
```bash
# 通过 Nacos API 创建限流规则
curl -X POST 'http://localhost:8848/nacos/v1/cs/configs' \
  -d 'dataId=gateway-sentinel-rules' \
  -d 'group=DEFAULT_GROUP' \
  -d 'namespaceId=kb-platform' \
  -d 'type=json' \
  -d 'content=[{"resource":"article-service","resourceMode":0,"count":2.0,"grade":1,"intervalSec":1,"controlBehavior":0,"burst":0,"paramItem":{"parseStrategy":3,"fieldName":"X-Real-IP"}}]'
```

### 5.2 限流触发测试
```bash
# 快速连续发送请求（count=2，第3次触发限流）
for i in 1 2 3; do
  curl -s -w "\nHTTP %{http_code}\n" http://localhost:8080/api/articles?page=1\&size=10
done

# 预期输出：
# HTTP 200  (第1次)
# HTTP 200  (第2次)
# HTTP 429  (第3次 — 触发限流)
# Body: {"code":429,"message":"请求过于频繁，请稍后再试"}
```

### 5.3 动态刷新测试
```bash
# 修改 count 从 2 提高到 100
curl -X POST 'http://localhost:8848/nacos/v1/cs/configs' \
  -d 'dataId=gateway-sentinel-rules' \
  -d 'group=DEFAULT_GROUP' \
  -d 'namespaceId=kb-platform' \
  -d 'type=json' \
  -d 'content=[{"resource":"article-service","resourceMode":0,"count":100.0,"grade":1,"intervalSec":1,"controlBehavior":0,"burst":0,"paramItem":{"parseStrategy":3,"fieldName":"X-Real-IP"}}]'

# 等待数秒后再次测试 — 限流应自动解除
```

### 5.4 Fail-Open 测试
```bash
# 删除 Nacos 配置
curl -X DELETE 'http://localhost:8848/nacos/v1/cs/configs' \
  -d 'dataId=gateway-sentinel-rules' \
  -d 'group=DEFAULT_GROUP' \
  -d 'namespaceId=kb-platform'

# 所有请求恢复正常通过（fail-open）
```

---

## 6. 架构决策记录 (ADR)

### ADR-001: 限流在网关层 vs 服务层

**决策**: 网关层限流。

**理由**:
1. 统一入口，一处配置
2. 恶意流量在网关丢弃，不消耗下游资源
3. Sentinel 网关适配器原生支持路由级 + IP 级双重维度
4. 配合 Nacos 动态配置，运维只需关注一个 Data ID

**代价**:
1. 网关成为单点 — 需多实例部署 + Nginx 负载均衡（见 Stage 9）
2. 业务级细粒度限流不如服务层灵活 — 后续可在服务层补充 Sentinel 注解

### ADR-002: Nacos DataSource vs Spring Cloud Config

**决策**: 使用 Sentinel 的 `NacosDataSource` 而非 Spring Cloud Nacos Config。

**理由**:
1. Sentinel 规则生命周期在 `GatewayRuleManager` 内存中，与 Spring `Environment` 无关
2. `NacosDataSource` 通过 `PropertyListener` 直接推送规则变更到 `GatewayRuleManager`
3. 避免 `@RefreshScope` + 手动解析 JSON + 手动调用 `GatewayRuleManager.loadRules()` 的冗余代码

---

## 7. 代码审查修复 (2026-06-30)

审查发现 6 个问题，修复了以下 4 项：

### 7.1 兜底规则 — 从 fail-open 改为 fail-safe

**问题**: 原实现中如果 Nacos 不可达或配置不存在，网关以零规则启动（完全不限流）。

**修复**: 新增 `sentinel/gateway-sentinel-rules.json` 种子文件，位于 `classpath:` 下。`SentinelRuleBootstrap` 在以下场景自动加载种子规则：

| 场景 | 行为 |
|------|------|
| Nacos 正常，配置存在 | 使用 Nacos 规则（动态可刷新） |
| Nacos 正常，配置不存在 | 加载种子文件 → gRPC 监听，Nacos 配置创建后自动切换 |
| Nacos 不可达 | 捕获 `NacosException` → 加载种子文件 |
| Nacos 配置被删除 | 清空规则后重新加载种子文件 |
| 种子文件也不存在 | 日志 ERROR，零规则启动（最后兜底） |

种子规则（`sentinel/gateway-sentinel-rules.json`）:
```json
[
  {"resource":"user-service","count":100.0,"grade":1,"intervalSec":1,...},
  {"resource":"article-service","count":50.0,"grade":1,"intervalSec":1,...},
  {"resource":"interact-service","count":30.0,"grade":1,"intervalSec":1,...}
]
```

### 7.2 统一加载方式 — 纯编程式

**问题**: `application.yml` 中有声明式 `spring.cloud.sentinel.datasource.gw-flow.nacos` 配置，同时 `SentinelRuleBootstrap` 也用 `@PostConstruct` 编程加载，两者同时运行造成双重加载。

**修复**:
- 删除 `application.yml` 中 `spring.cloud.sentinel.datasource.gw-flow` 声明式配置块
- 保留 `SentinelRuleBootstrap` 编程式方案（日志更完整，fallback 逻辑可控）
- 添加 `@ConditionalOnProperty(name = "sentinel.datasource.bootstrap.enabled", ...)` 可控开关，可通过配置 `sentinel.datasource.bootstrap.enabled=false` 禁用

### 7.3 清理死代码

**问题 1**: `spring.cloud.sentinel.scg.fallback` 配置块被 `SentinelBlockHandlerConfig` 的 `BlockRequestHandler` bean 覆盖，永远不会生效。

**修复**: 删除 `scg.fallback` 配置块。

**问题 2**: `SENTINEL_DASHBOARD` 默认值 `localhost:8080` 与网关自身端口冲突。

**修复**: 默认值改为 `localhost:18080`，并添加注释说明。

### 7.4 未修复的低优先级问题

| 问题 | 不修复原因 |
|------|-----------|
| 双 Nacos 连接 (Discovery + ConfigService) | 功能正常，使用不同 namespace/用途，合并引入不必要的抽象 |
| `/api/trending` 在 `AuthPathMatcher` 重复匹配 | 无害冗余，不在此 Stage 范围内 |

---

## 8. 后续优化方向 (Stage 9 配合)

- **Sentinel Dashboard**: 部署独立 Dashboard 容器，可视化监控限流效果
- **集群限流**: 多网关实例共享 Token Server，避免单机限流不准确
- **Nginx X-Real-IP**: 前置 Nginx 时自动注入真实客户端 IP
- **自定义降级页**: 区分普通限流 vs 系统保护 vs 热点限流，返回不同提示
