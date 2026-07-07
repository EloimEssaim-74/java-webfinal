# Stage 10: 集成测试

> **日期**: 2026-07-02  
> **工时**: 3天 (自主完成)  
> **里程碑**: 全链路测试通过，无阻塞问题

---

## 1. 测试环境

### 1.1 环境要求

| 组件 | 版本 | 端口 | 说明 |
|------|------|------|------|
| Docker | 24+ | - | 容器运行时 |
| Docker Compose | 2.x | - | 服务编排 |
| Nginx | alpine | 80 | 前置代理入口 |
| MySQL (主) | 8.0 | 3306 | 读写库 |
| MySQL (从) | 8.0 | 3307 | 只读库 |
| Redis | 7-alpine | 6379 | 缓存/排行榜 |
| RabbitMQ | 3-management | 5672/15672 | 消息队列 |
| Nacos | 2.2 | 8848 | 注册/配置中心 |
| Gateway ×2 | Spring Cloud | - | 双实例 |
| user-service | Spring Boot | - | 用户服务 |
| article-service | Spring Boot | - | 文章服务 |
| interact-service | Spring Boot | - | 互动服务 |
| tag-extract-service | Spring Boot | - | 标签提取 |
| compliance-service | Spring Boot | - | 合规检测 |
| ai-assistant-service | Spring Boot | - | AI 续写 |

### 1.2 环境启动

```bash
cd /home/elesm/workspace/java/webfinal

# 1. 编译所有模块
mvn clean package -DskipTests -q

# 2. 启动基础设施
docker-compose up -d mysql redis rabbitmq nacos

# 3. 等待 MySQL 就绪
until docker exec kb-mysql mysqladmin ping -h localhost --silent; do sleep 2; done

# 4. 启动全部微服务（含双网关实例）
docker-compose up -d

# 5. 验证所有服务注册到 Nacos
curl -s http://localhost:8848/nacos/v1/ns/service/list?namespaceId=kb-platform \
  | python3 -c "import sys,json; [print(s) for s in json.load(sys.stdin)['doms']]"

# 预期输出:
# gateway-service
# user-service
# article-service
# interact-service
# tag-extract-service
# compliance-service
# ai-assistant-service

# 6. 验证 Nginx 可达
curl -s -o /dev/null -w "%{http_code}" http://localhost/api/trending
# 预期: 200

# 7. 查看全部容器状态
docker-compose ps
```

---

## 2. 测试范围

### 2.1 测试矩阵

| # | 模块 | 正向 | 异常 | 鉴权 | 边界 | 性能 |
|---|------|------|------|------|------|------|
| 1 | 用户服务 | ✅ | ✅ | ✅ | ✅ | - |
| 2 | 文章服务 | ✅ | ✅ | ✅ | ✅ | ✅ |
| 3 | 互动服务 | ✅ | ✅ | ✅ | ✅ | - |
| 4 | 热搜榜单 | ✅ | - | ✅ | ✅ | ✅ |
| 5 | 网关限流 | - | ✅ | - | - | ✅ |
| 6 | 异步广播 | ✅ | ✅ | - | - | - |
| 7 | AI 续写 | ✅ | ✅ | ✅ | ✅ | - |
| 8 | Nginx 代理 | ✅ | ✅ | - | ✅ | ✅ |

### 2.2 未覆盖范围（说明原因）

| 场景 | 原因 |
|------|------|
| MySQL 主从故障切换 | 需额外中间件（Orchestrator/MHA），超出当前架构范围 |
| Redis 集群故障 | 单实例部署，集群模式见 Stage 9 后续优化 |
| DDoS 级别的流量攻击 | 需云厂商 WAF/高防 IP |

---

## 3. 全链路测试场景

### 场景 1: 新用户注册→创作→发布→互动→热搜 全流程

```
步骤1: 注册用户 A 和用户 B
步骤2: 用户 A 登录 → 创建文章 → 发布
步骤3: 用户 B 登录 → 查看文章详情 → 评论 → 点赞
步骤4: 用户 A 查看文章 — 验证 tags 已被提取、audit_result 已设置
步骤5: 查看热搜榜 — 验证用户 B 的阅读/点赞/评论已计入热度
步骤6: 用户 A 注销
```

### 场景 2: 权限边界测试

```
步骤1: 用户 A 创建文章（草稿）
步骤2: 用户 B 尝试发布用户 A 的文章 → 预期 403
步骤3: 用户 B 尝试修改用户 A 的文章 → 预期 403
步骤4: 用户 B 尝试删除用户 A 的文章 → 预期 403
步骤5: 管理员登录 → 删除用户 A 的文章 → 预期 200
步骤6: 无 Token 访问需要认证的接口 → 预期 401
```

### 场景 3: 限流测试

```
步骤1: 快速连续请求文章列表 100 次 → 部分返回 429
步骤2: 等待 1 秒 → 限流恢复
步骤3: 通过 Nacos 动态修改限流阈值 → 验证规则自动生效
```

### 场景 4: 消息队列测试

```
步骤1: 停止 tag-extract-service 和 compliance-service
步骤2: 发布一篇新文章 → 消息堆积在队列
步骤3: RabbitMQ 管理界面确认消息 Ready=1
步骤4: 启动消费者
步骤5: 确认消息被消费 → tags 和 audit_result 已回写
```

### 场景 5: AI 流式续写测试

```
步骤1: 登录获取 Token
步骤2: POST /api/ai/continue → 验证 SSE 流式推送
步骤3: 中途断开连接 → 服务端日志确认 doOnCancel 触发
步骤4: Demo 模式 → 验证模拟流式输出
```

### 场景 6: 多实例与负载均衡

```
步骤1: 启动 article-service 实例 2
步骤2: 多次请求 /api/articles → Nginx 日志确认请求分发到不同网关
步骤3: 停止 gateway-1 → 验证 Nginx 自动切换到 gateway-2
步骤4: 热搜榜缓存验证 → X-Cache-Status: HIT
```

---

## 4. 接口测试用例清单

### 4.1 用户服务 (6 条)

| ID | 用例 | 方法 | 路径 | 预期 |
|----|------|------|------|------|
| U01 | 注册新用户 | POST | /api/user/register | 200, 返回用户信息 |
| U02 | 重复注册 | POST | /api/user/register | 400, "已存在" |
| U03 | 参数校验 | POST | /api/user/register | 400, 校验失败 |
| U04 | 正常登录 | POST | /api/user/login | 200, 返回 JWT |
| U05 | 错误密码 | POST | /api/user/login | 401, "错误" |
| U06 | 注销 | POST | /api/user/logout | 200, Token 加入黑名单 |

### 4.2 文章服务 (9 条)

| ID | 用例 | 方法 | 路径 | 预期 |
|----|------|------|------|------|
| A01 | 创建草稿 | POST | /api/articles | 200, status=DRAFT |
| A02 | 发布文章 | PUT | /api/articles/{id}/publish | 200, status=PUBLISHED |
| A03 | 修改文章 | PUT | /api/articles/{id} | 200 |
| A04 | 越权修改 | PUT | /api/articles/{id} | 403 |
| A05 | 逻辑删除 | DELETE | /api/articles/{id} | 200 |
| A06 | 文章列表 | GET | /api/articles?page=1&size=10 | 200, 数组 |
| A07 | 文章详情 | GET | /api/articles/{id} | 200, 含 tags |
| A08 | 无认证创建 | POST | /api/articles | 401 |
| A09 | 发布后消息发送 | PUT | /api/articles/{id}/publish | RabbitMQ 队列有消息 |

### 4.3 互动服务 (5 条)

| ID | 用例 | 方法 | 路径 | 预期 |
|----|------|------|------|------|
| I01 | 添加评论 | POST | /api/comments | 200 |
| I02 | 点赞 | POST | /api/articles/{id}/like | 200 |
| I03 | 重复点赞 | POST | /api/articles/{id}/like | 400, "已点赞" |
| I04 | 评论后热度 | GET | /api/trending | heatScore 含评论+1 |
| I05 | 点赞后热度 | GET | /api/trending | heatScore 含点赞+3 |

### 4.4 热搜榜单 (6 条)

| ID | 用例 | 方法 | 路径 | 预期 |
|----|------|------|------|------|
| T01 | 公开访问 | GET | /api/trending | 200, 无需 Token |
| T02 | 榜单排序 | GET | /api/trending | heatScore 降序 |
| T03 | 阅读热度 | GET | /api/articles/{id} | ZSET score +1 |
| T04 | 阅读防重 | GET×2 | /api/articles/{id} | 第2次不增加 |
| T05 | 空榜单 | GET | /api/trending | data=[] |
| T06 | 缓存头 | GET | /api/trending | X-Cache-Status 存在 |

### 4.5 网关限流 (3 条)

| ID | 用例 | 方法 | 路径 | 预期 |
|----|------|------|------|------|
| G01 | 触发限流 | GET×N | /api/articles | 部分 429 |
| G02 | 限流恢复 | GET | /api/articles | 1 秒后 200 |
| G03 | 公开路径不限流 | GET | /api/trending | 始终 200 |

### 4.6 异步广播 (3 条)

| ID | 用例 | 方法 | 路径 | 预期 |
|----|------|------|------|------|
| M01 | 发布后标签提取 | PUT | /api/articles/{id}/publish | tags 非空 |
| M02 | 合规检测 PASS | PUT | /api/articles/{id}/publish | auditResult=PASS |
| M03 | 合规检测 BLOCK | PUT | /api/articles/{id}/publish | 违规文章被逻辑删除 |

### 4.7 AI 续写 (4 条)

| ID | 用例 | 方法 | 路径 | 预期 |
|----|------|------|------|------|
| AI01 | Demo 模式流式 | POST | /api/ai/continue | SSE 流, 含 [DONE] |
| AI02 | 上下文为空 | POST | /api/ai/continue | 400 校验失败 |
| AI03 | 无认证 | POST | /api/ai/continue | 401 |
| AI04 | 断连测试 | POST→Ctrl+C | /api/ai/continue | 日志 "客户端断开" |

### 4.8 Nginx 代理 (3 条)

| ID | 用例 | 方法 | 路径 | 预期 |
|----|------|------|------|------|
| N01 | Nginx 可达 | GET | /api/trending | 200, 经 Nginx |
| N02 | Gzip 压缩 | GET | /api/articles | Content-Encoding: gzip |
| N03 | 限流头 | GET | /api/trending | X-Cache-Status 存在 |

---

## 5. 数据一致性验证

### 5.1 Redis ↔ MySQL 一致性

```bash
# 1. 点赞后验证 MySQL like_count
ARTICLE_ID=1
docker exec kb-redis redis-cli ZSCORE hot_articles $ARTICLE_ID
docker exec kb-mysql mysql -uroot -proot123 kb_platform \
  -e "SELECT id, like_count FROM articles WHERE id=$ARTICLE_ID"

# 2. 评论异步持久化验证（等待消费者处理）
sleep 10
docker exec kb-mysql mysql -uroot -proot123 kb_platform \
  -e "SELECT COUNT(*) FROM comments WHERE article_id=$ARTICLE_ID"
```

### 5.2 RabbitMQ 消息可靠性

```bash
# 访问 RabbitMQ 管理界面 http://localhost:15672
# 确认:
#   - article.tag.queue 和 article.compliance.queue 状态正常
#   - 无消息积压 (Ready = 0)
#   - ACK 率 100%
```

---

## 6. 性能基准测试

### 6.1 压测场景

| 场景 | 工具 | 并发 | 持续时间 | 目标 QPS | 目标 P99 |
|------|------|------|---------|---------|---------|
| 热搜榜查询 | wrk | 200 | 60s | 10,000+ | < 5ms |
| 文章列表 | wrk | 100 | 30s | 2,000+ | < 50ms |
| 文章详情 | wrk | 100 | 30s | 1,000+ | < 100ms |
| 用户登录 | wrk | 50 | 30s | 500+ | < 200ms |
| 混合场景 | JMeter | 200 | 120s | 8:2 读写比 | - |

### 6.2 压测命令

```bash
# 热搜榜（三级缓存全命中）
wrk -t8 -c200 -d60s --latency http://localhost/api/trending

# 文章列表（Nginx → Gateway → article-service → MySQL 从库）
wrk -t4 -c100 -d30s http://localhost/api/articles?page=1\&size=20

# 登录（BCrypt 计算密集）
wrk -t4 -c50 -d30s -s login.lua http://localhost/api/user/login
```

### 6.3 系统资源监控

```bash
# 容器资源使用
docker stats --no-stream

# JVM GC 日志
docker-compose logs article-service | grep "GC"

# 慢 SQL
docker-compose logs article-service | grep "slow sql"
```

---

## 7. 测试检查清单

### 7.1 启动检查

| # | 检查项 | 命令 | 通过标准 |
|---|--------|------|---------|
| 1 | 所有容器运行 | `docker-compose ps` | 全部 Up/healthy |
| 2 | Nacos 服务注册 | `curl nacos:8848/...` | 7 个服务在线 |
| 3 | MySQL 主从就绪 | `docker exec kb-mysql mysqladmin ping` | 两个实例均 ok |
| 4 | Redis 响应 | `docker exec kb-redis redis-cli ping` | PONG |
| 5 | RabbitMQ 可访问 | `curl -s localhost:15672` | 管理界面返回 |
| 6 | Nginx 可达 | `curl -s localhost/api/trending` | HTTP 200 |

### 7.2 功能检查

| # | 检查项 | 通过标准 |
|---|--------|---------|
| 7 | 用户注册/登录 | JWT 返回有效 Token |
| 8 | 文章 CRUD 全流程 | 创建→发布→修改→删除 均 200 |
| 9 | 权限控制 | 越权操作返回 403 |
| 10 | 评论异步入库 | 评论 10 秒后 MySQL 可查 |
| 11 | 点赞防重 | 重复点赞返回 400 |
| 12 | 热搜公开访问 | 无 Token 返回 200 |
| 13 | 三级缓存生效 | Nginx X-Cache-Status 出现 HIT |
| 14 | 网关限流 | 高频请求返回 429 |
| 15 | 标签提取 | 发布后 tags 字段非空 |
| 16 | 合规检测 | 发布后 audit_result = PASS |
| 17 | AI 流式 | SSE data 块逐字推送 |

### 7.3 可靠性检查

| # | 检查项 | 通过标准 |
|---|--------|---------|
| 18 | 单网关故障 | 停止 gateway-1 后 Nginx 自动切到 gateway-2 |
| 19 | 消息积压恢复 | 消费者重启后处理积压消息 |
| 20 | Redis 断连恢复 | 服务自动重连 Redis |
| 21 | 优雅停止 | `docker-compose stop` 无数据丢失 |

---

## 8. 测试数据准备

```bash
#!/bin/bash
# 快速准备测试数据

BASE="http://localhost"

# 注册测试用户
curl -s -X POST $BASE/api/user/register \
  -H "Content-Type: application/json" \
  -d '{"username":"tester1","password":"test123"}' > /dev/null

curl -s -X POST $BASE/api/user/register \
  -H "Content-Type: application/json" \
  -d '{"username":"tester2","password":"test123"}' > /dev/null

# 获取 Token
TOKEN1=$(curl -s -X POST $BASE/api/user/login \
  -H "Content-Type: application/json" \
  -d '{"username":"tester1","password":"test123"}' | jq -r '.data.token')

TOKEN2=$(curl -s -X POST $BASE/api/user/login \
  -H "Content-Type: application/json" \
  -d '{"username":"tester2","password":"test123"}' | jq -r '.data.token')

# 创建并发布 5 篇测试文章
for i in $(seq 1 5); do
  AID=$(curl -s -X POST $BASE/api/articles \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $TOKEN1" \
    -d "{\"title\":\"测试文章 $i - Spring Boot微服务实践\",\"content\":\"这是第${i}篇测试文章的正文内容。讨论了微服务架构、Docker容器化和Redis缓存等主题。\"}" \
    | jq -r '.data.id')
  curl -s -X PUT "$BASE/api/articles/$AID/publish" \
    -H "Authorization: Bearer $TOKEN1" > /dev/null
  echo "文章 $i 已发布: id=$AID"
done

# 用户2 阅读+点赞+评论
ARTICLES=$(curl -s "$BASE/api/articles?page=1&size=5" | jq -r '.data[].id')
for AID in $ARTICLES; do
  curl -s "$BASE/api/articles/$AID" -H "Authorization: Bearer $TOKEN2" > /dev/null
  curl -s -X POST "$BASE/api/articles/$AID/like" -H "Authorization: Bearer $TOKEN2" > /dev/null
  curl -s -X POST $BASE/api/comments \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $TOKEN2" \
    -d "{\"articleId\":$AID,\"content\":\"非常好的文章！\"}" > /dev/null
done

echo "测试数据准备完成"
```

---

## 9. 缺陷跟踪模板

| 字段 | 格式 |
|------|------|
| ID | BUG-XXX |
| 严重级 | P0-Critical / P1-High / P2-Medium / P3-Low |
| 模块 | user/article/interact/trending/gateway/mq/ai/nginx |
| 场景 | 操作步骤 |
| 预期 | 期望行为 |
| 实际 | 实际行为 |
| 复现率 | 每次/偶尔/特定条件 |
| 日志 | 相关错误日志片段 |
| 状态 | Open/Fixed/Verified/Closed |

---

## 10. 测试报告摘要模板

```
========== 集成测试报告 ==========
日期: 2026-07-__
环境: Docker Compose (10 容器, 8 微服务)

--- 测试统计 ---
总用例数: 42
通过: __
失败: __
阻塞: __
通过率: __%

--- 性能基准 ---
/api/trending: __ req/s (目标 10,000)
/api/articles (列表): __ req/s (目标 2,000)
/api/articles (详情): __ req/s (目标 1,000)

--- 遗留问题 ---
1. [P?] ...
2. [P?] ...

--- 结论 ---
[ ] 通过 — 可进入交付阶段
[ ] 有条件通过 — 遗留问题不影响核心功能
[ ] 不通过 — 存在阻塞性问题
========== 报告结束 ==========
```
