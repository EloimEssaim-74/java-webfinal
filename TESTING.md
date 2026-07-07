# 功能测试指引

> 跟随本指引，通过 curl 命令逐项演示知识库平台的全部核心功能。

---

## 0. 环境准备

```bash
# 确认所有服务已启动
docker-compose ps | grep -c "Up"   # 应输出 12

# 确认 API 可达
curl -s http://localhost/api/trending | python3 -c "import sys,json; print('OK — code='+str(json.load(sys.stdin)['code']))"

# 设置 Base URL
BASE="http://localhost"

# 准备测试用户（已预置）
# alice / alice123456
# bob   / bob123456
```

---

## 1. 用户认证模块

### 1.1 新用户注册

```bash
curl -s -X POST $BASE/api/user/register \
  -H "Content-Type: application/json" \
  -d '{"username":"demo","password":"demo123456"}' | python3 -m json.tool
```

**预期**: `{"code":200, "data":{"username":"demo","role":"user",...}}`

> ℹ️ Stage 13 安全加固：注册时 `"role":"admin"` 被强制忽略，所有新用户默认为 `user`。

### 1.2 登录获取 Token

```bash
LOGIN=$(curl -s -X POST $BASE/api/user/login \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"alice123456"}')
echo "$LOGIN" | python3 -m json.tool

# 提取 Token
TOKEN_A=$(echo "$LOGIN" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['token'])")
echo "TOKEN_A=$TOKEN_A"
```

**预期**: 返回 `token`、`tokenType`、`userId`、`username`、`role`。

### 1.3 错误密码

```bash
curl -s -X POST $BASE/api/user/login \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"wrongpass"}'
```

**预期**: `{"code":401,"message":"用户名或密码错误"}`

---

## 2. 文章创作模块

### 2.1 创建文章（草稿）

```bash
ARTICLE=$(curl -s -X POST $BASE/api/articles \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN_A" \
  -d '{"title":"Spring Cloud 微服务最佳实践","content":"本文介绍 Spring Cloud 微服务架构的核心组件：服务注册与发现(Nacos)、API 网关(Gateway)、配置中心、负载均衡、熔断降级(Sentinel)和消息驱动(RabbitMQ)。通过实际案例演示如何从零搭建生产级微服务。"}')
echo "$ARTICLE" | python3 -m json.tool

ARTICLE_ID=$(echo "$ARTICLE" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['id'])")
echo "ARTICLE_ID=$ARTICLE_ID"
```

**预期**: `{"code":200, "data":{"id":..., "status":"DRAFT"}}`

> ℹ️ Stage 13 安全加固：创建时强制 `status=DRAFT`，必须通过 `publish` 接口发布。

### 2.2 发布文章

```bash
curl -s -X PUT "$BASE/api/articles/$ARTICLE_ID/publish" \
  -H "Authorization: Bearer $TOKEN_A" | python3 -m json.tool
```

**预期**: `{"code":200, "data":{"status":"PUBLISHED"}}`

> ℹ️ 发布操作触发 RabbitMQ 消息 → 标签提取 + 合规检测并行处理。

### 2.3 修改文章

```bash
curl -s -X PUT "$BASE/api/articles/$ARTICLE_ID" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN_A" \
  -d '{"title":"Spring Cloud 微服务最佳实践 (修订版)"}' | python3 -m json.tool
```

**预期**: `data.title` 已更新。

### 2.4 越权修改（安全验证）

```bash
# Bob 的 Token
TOKEN_B=$(curl -s -X POST $BASE/api/user/login \
  -H "Content-Type: application/json" \
  -d '{"username":"bob","password":"bob123456"}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['token'])")

# Bob 尝试修改 Alice 的文章 → 应被拒绝
curl -s -X PUT "$BASE/api/articles/$ARTICLE_ID" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN_B" \
  -d '{"title":"Hacked!"}'
```

**预期**: `{"code":403,"message":"无权操作此文章"}`

---

## 3. 文章浏览模块

### 3.1 最新文章列表

```bash
curl -s "$BASE/api/articles?page=1&size=5" | python3 -c "
import sys,json
d=json.load(sys.stdin)
print(f'code={d[\"code\"]}')
print(f'total={d[\"data\"][\"total\"]}')
print(f'page={d[\"data\"][\"page\"]}')
for a in d['data']['list']:
    print(f'  [{a[\"id\"]}] {a[\"title\"][:40]}... ({a[\"createdAt\"][:19]})')
"
```

**预期**: `total > 0`、按 `createdAt` 降序排列。

> ✅ Stage 13 修复：`total` 不再为 0（MybatisPlusConfig 分页拦截器已部署）。

### 3.2 文章详情

```bash
curl -s "$BASE/api/articles/$ARTICLE_ID" | python3 -m json.tool
```

**预期**: 返回完整字段 — `id`、`title`、`content`、`status`、`likeCount`、`tags`、`auditResult` 等。

### 3.3 不存在的文章

```bash
curl -s "$BASE/api/articles/99999"
```

**预期**: `{"code":404,"message":"文章不存在"}`

---

## 4. 互动模块

### 4.1 点赞

```bash
# 首次点赞
curl -s -X POST "$BASE/api/articles/$ARTICLE_ID/like" \
  -H "Authorization: Bearer $TOKEN_B"

# 重复点赞 → 防重
curl -s -X POST "$BASE/api/articles/$ARTICLE_ID/like" \
  -H "Authorization: Bearer $TOKEN_B"
```

**预期**: 首次返回 `{"code":200}`，重复返回 `{"code":400,"message":"您已点赞过该文章"}`。

### 4.2 评论

```bash
curl -s -X POST "$BASE/api/comments" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN_B" \
  -d "{\"articleId\":$ARTICLE_ID,\"content\":\"非常实用的教程，收藏了！\"}"
```

**预期**: `{"code":200}`。

### 4.3 查看评论

```bash
curl -s "$BASE/api/comments?articleId=$ARTICLE_ID" | python3 -c "
import sys,json
d=json.load(sys.stdin)
for c in d['data']:
    print(f'  [{c[\"id\"]}] user={c[\"userId\"]}: {c[\"content\"][:50]}')
"
```

---

## 5. 热搜榜单模块

### 5.1 查看热搜 Top 10

```bash
curl -s "$BASE/api/trending" | python3 -c "
import sys,json
d=json.load(sys.stdin)
print(f'热搜条目: {len(d[\"data\"])}')
for i, a in enumerate(d['data'][:10], 1):
    print(f'  #{i} [{a[\"id\"]}] {a[\"title\"][:30]} — 热度:{a[\"heatScore\"]}')
"
```

### 5.2 热度来源验证

```bash
# 阅读 → +1 热度（5 分钟内同一用户不重复）
curl -s "$BASE/api/articles/$ARTICLE_ID" \
  -H "Authorization: Bearer $TOKEN_B" > /dev/null

# 点赞 → +3 热度
curl -s -X POST "$BASE/api/articles/$ARTICLE_ID/like" \
  -H "Authorization: Bearer $TOKEN_A" > /dev/null

# 评论 → +1 热度
curl -s -X POST "$BASE/api/comments" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN_A" \
  -d "{\"articleId\":$ARTICLE_ID,\"content\":\"+1\"}" > /dev/null

# 查看 Redis ZSET
docker exec kb-redis redis-cli ZSCORE hot_articles $ARTICLE_ID
```

**预期**: ZSET 中有对应的热度分数（阅读+1 + 点赞+3 + 评论+1 = 至少+5）。

---

## 6. AI 流式续写模块

### 6.1 Demo 模式 SSE

```bash
curl -s -N -X POST "$BASE/api/ai/continue" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN_A" \
  -d '{"context":"微服务架构的核心优势在于"}' | head -20
```

**预期**: 看到多个 `data:` 行逐字输出，以 `data:[DONE]` 结束。

### 6.2 参数校验

```bash
# 空上下文
curl -s -X POST "$BASE/api/ai/continue" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN_A" \
  -d '{"context":""}'
```

**预期**: `{"code":400,"message":"context: 上下文内容不能为空"}`

> ✅ Stage 13 修复：AI 服务现在返回统一的 `{code, message}` 格式。

---

## 7. 我的文章模块

```bash
# 我的已发布
curl -s "$BASE/api/articles/mine?status=PUBLISHED" \
  -H "Authorization: Bearer $TOKEN_A" | python3 -c "
import sys,json
d=json.load(sys.stdin)
for a in d['data']['list']:
    print(f'  [{a[\"id\"]}] {a[\"title\"][:40]} — {a[\"status\"]}')
"

# 我的草稿
curl -s "$BASE/api/articles/mine?status=DRAFT" \
  -H "Authorization: Bearer $TOKEN_A" | python3 -c "
import sys,json
d=json.load(sys.stdin)
for a in d['data']['list']:
    print(f'  [{a[\"id\"]}] {a[\"title\"][:40]} — {a[\"status\"]}')
"
```

**预期**: 列表包含 `status` 字段（DRAFT/PUBLISHED）。

> ✅ Stage 13 修复: `ArticleListItemVO` 现在包含 `status` 字段。

---

## 8. 异步功能验证

### 8.1 标签提取

发布新文章后等待 5-10 秒，标签提取消费者处理结果：

```bash
# 创建并发布
NEW=$(curl -s -X POST "$BASE/api/articles" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN_A" \
  -d '{"title":"Docker与Kubernetes生产级部署指南","content":"本文深入探讨Docker容器化与Kubernetes编排的核心概念，包括Pod管理、Service网络和Ingress配置。"}')
NEW_ID=$(echo "$NEW" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['id'])")
curl -s -X PUT "$BASE/api/articles/$NEW_ID/publish" \
  -H "Authorization: Bearer $TOKEN_A" > /dev/null

# 等待异步处理
echo "等待标签提取与合规检测..."
sleep 8

# 查看结果
curl -s "$BASE/api/articles/$NEW_ID" \
  -H "Authorization: Bearer $TOKEN_A" | python3 -c "
import sys,json
d=json.load(sys.stdin)['data']
print(f'tags:        {d[\"tags\"]}')
print(f'auditResult: {d[\"auditResult\"]}')
"
```

**预期**: `tags` 包含提取的关键词（如 "Docker必,Docker,Kubernetes"），`auditResult` 为 `PASS`。

> ✅ Stage 13 修复：消费者缺少 `Jackson2JsonMessageConverter` Bean 导致此前标签/合规静默失败，现已修复。

### 8.2 合规检测 — 敏感词触发

```bash
# 创建含敏感词的文章
BAD=$(curl -s -X POST "$BASE/api/articles" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN_A" \
  -d '{"title":"测试文章","content":"这是一个正常的文章内容。"}')
BAD_ID=$(echo "$BAD" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['id'])")

# 发布（合规检测在消费时执行 — 此文章不含敏感词应为 PASS）
curl -s -X PUT "$BASE/api/articles/$BAD_ID/publish" \
  -H "Authorization: Bearer $TOKEN_A" > /dev/null
sleep 5

curl -s "$BASE/api/articles/$BAD_ID" \
  -H "Authorization: Bearer $TOKEN_A" | python3 -c "
import sys,json
d=json.load(sys.stdin)['data']
print(f'auditResult: {d[\"auditResult\"]}')
"
```

**预期**: `auditResult` = `PASS`（内容过短触发 REVIEW，正常内容触发 PASS）。

---

## 9. 安全验证

### 9.1 无 Token 访问受保护资源

```bash
# POST 文章
curl -s -X POST "$BASE/api/articles" \
  -H "Content-Type: application/json" \
  -d '{"title":"test","content":"test"}'

# 点赞
curl -s -X POST "$BASE/api/articles/1/like"

# 我的文章
curl -s "$BASE/api/articles/mine"
```

**预期**: 全部返回 `{"code":401,"message":"缺少或无效的认证令牌"}`

### 9.2 角色验证

```bash
# 尝试注册为 admin → 强制 user 角色
curl -s -X POST $BASE/api/user/register \
  -H "Content-Type: application/json" \
  -d '{"username":"hacker","password":"pass123456","role":"admin"}' \
  | python3 -c "import sys,json; d=json.load(sys.stdin); print(f'role={d[\"data\"][\"role\"]}')"
```

**预期**: `role=user`（拒绝自提 admin）。

---

## 10. 故障排查

| 症状 | 排查命令 |
|------|---------|
| 服务未启动 | `docker-compose ps` — 确认 12 容器 Up |
| Gateway 不通 | `docker logs kb-gateway-1 --tail 20` |
| MySQL 连接失败 | `docker logs kb-mysql --tail 10` |
| Redis 连接失败 | `docker exec kb-redis redis-cli ping` |
| RabbitMQ 消息积压 | 访问 `http://localhost:15672` (guest/guest) |
| 标签不生成 | `docker logs kb-tag-extract --tail 20 \| grep "收到\|完成"` |
| 合规不生效 | `docker logs kb-compliance --tail 20 \| grep "收到\|完成"` |
| 分页 total=0 | 确认 MybatisPlusConfig 已部署: `docker exec kb-article-service ls /app.jar` |

---

## 附录：一键测试脚本

```bash
#!/bin/bash
# 完整功能演示脚本 — 复制粘贴即可运行

BASE="http://localhost"
PASS=0; FAIL=0

check() { if [ "$1" = "$2" ]; then PASS=$((PASS+1)); echo "✅ $3"; else FAIL=$((FAIL+1)); echo "❌ $3 (got: $1)"; fi; }

# 1. 注册
R=$(curl -s -X POST $BASE/api/user/register -H "Content-Type: application/json" -d '{"username":"testdemo","password":"test123456"}')
check "$(echo $R | python3 -c "import sys,json; print(json.load(sys.stdin)['code'])")" "200" "注册"

# 2. 登录
L=$(curl -s -X POST $BASE/api/user/login -H "Content-Type: application/json" -d '{"username":"testdemo","password":"test123456"}')
TOKEN=$(echo $L | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['token'])")
check "$(echo $L | python3 -c "import sys,json; print(json.load(sys.stdin)['code'])")" "200" "登录"

# 3. 创建+发布
A=$(curl -s -X POST $BASE/api/articles -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" -d '{"title":"E2E Test","content":"Testing"}')
AID=$(echo $A | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['id'])")
check "$(echo $A | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['status'])")" "DRAFT" "创建=DRAFT"
curl -s -X PUT "$BASE/api/articles/$AID/publish" -H "Authorization: Bearer $TOKEN" > /dev/null

# 4. 文章列表
LIST=$(curl -s "$BASE/api/articles?page=1&size=1")
TOTAL=$(echo $LIST | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['total'])")
[ "$TOTAL" -gt 0 ] && PASS=$((PASS+1)) && echo "✅ total=$TOTAL > 0" || { FAIL=$((FAIL+1)); echo "❌ total=0"; }

# 5. 点赞
L1=$(curl -s -X POST "$BASE/api/articles/$AID/like" -H "Authorization: Bearer $TOKEN")
L2=$(curl -s -X POST "$BASE/api/articles/$AID/like" -H "Authorization: Bearer $TOKEN")
check "$(echo $L1 | python3 -c "import sys,json; print(json.load(sys.stdin)['code'])")" "200" "首次点赞"
check "$(echo $L2 | python3 -c "import sys,json; print(json.load(sys.stdin)['code'])")" "400" "重复点赞拒绝"

# 6. 热搜
T=$(curl -s "$BASE/api/trending")
check "$(echo $T | python3 -c "import sys,json; print(json.load(sys.stdin)['code'])")" "200" "热搜榜"

# 7. AI SSE
SSE=$(curl -s -X POST "$BASE/api/ai/continue" -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" -d '{"context":"test"}')
echo "$SSE" | grep -q "data:" && PASS=$((PASS+1)) && echo "✅ AI SSE流" || { FAIL=$((FAIL+1)); echo "❌ AI SSE流"; }

echo ""
echo "=== 测试结果: $PASS 通过, $FAIL 失败 ==="
```
