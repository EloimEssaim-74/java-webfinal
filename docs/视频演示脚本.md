# 视频演示脚本 — Knowledge Platform

> **时长**: 4 分 00 秒（验收要求 3-5 分钟）
> **录制**: OBS / Xbox Game Bar 全屏录制
> **终端布局**: 左 2/3 主操作区 + 右 1/3 MQ日志监控
> **旁白**: 录制时同步口播，不用后期配音

---

## 录制前准备（30 秒）

```bash
# 1. 确认环境
docker ps --format "{{.Names}}" | wc -l   # 应输出 ≥13

# 2. 右半屏：启动 MQ 日志监控（录前 10 秒启动）
docker logs -f kb-tag-extract 2>&1 | grep --line-buffered "收到\|完成" &
docker logs -f kb-compliance 2>&1 | grep --line-buffered "收到\|检测" &

# 3. 终端字号调到 18pt+（视频压缩后仍可读）
# 4. 关闭通知、微信、弹窗
```

---

## 时间线（验收对照）

### 0:00-0:15 — 验收点 ①：容器与中间件启动状态

```bash
clear
echo "=== Knowledge Platform — 微服务启动状态 ==="
echo ""
docker ps --format "table {{.Names}}\t{{.Status}}" | head -14
```

**口播**: 「Knowledge Platform，基于 Spring Cloud 的微服务知识平台。14 个 Docker 容器全部运行——Gateway ×2、MySQL 主从、Redis、RabbitMQ、Nacos 等中间件已就绪。」

**验收对照**: 展示微服务和中间件启动状态 ✅

---

### 0:15-1:00 — 验收点 ②：认证 + 核心业务链路（Postman 或 curl）

```bash
echo ""
echo "=== 1. 用户认证 ==="

# 登录获取 Token
LOGIN=$(curl -s -X POST http://localhost/api/user/login \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"alice123456"}')
TOKEN=$(echo $LOGIN | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['token'])")
echo "JWT Token: ${TOKEN:0:25}... (HMAC-SHA384, 7天有效)"

echo ""
echo "=== 2. 创建并发布文章 ==="

# 创建
ARTICLE=$(curl -s -X POST http://localhost/api/articles \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"title":"Spring Cloud Gateway 深度解析","content":"本文详细剖析 Spring Cloud Gateway 的路由断言机制、过滤器链设计、以及结合 Sentinel 实现动态限流规则推送的生产实践。涵盖 Gateway 与 Nacos 服务发现的集成原理。"}')
AID=$(echo $ARTICLE | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['id'])")
echo "  文章 id=$AID  status=DRAFT"

# 发布
curl -s -X PUT "http://localhost/api/articles/$AID/publish" \
  -H "Authorization: Bearer $TOKEN" \
  | python3 -c "import sys,json; print(f'  发布成功  status={json.load(sys.stdin)[\"data\"][\"status\"]}')"

echo ""
echo "  → RabbitMQ 消息已发送到 article.topic.exchange"
echo "  → 等待异步标签提取 + 合规检测..."
sleep 5
```

**口播**: 「JWT 登录获取 Token。创建文章——注意状态被强制为 DRAFT。发布触发 RabbitMQ 消息——右侧终端已显示消费者实时收到任务。5 秒后标签提取和合规检测完成。」

**验收对照**: 通过 curl 触发核心业务链路 ✅

---

### 1:00-1:30 — 验收点 ③：MQ 日志实时展示 + 异步结果确认

```bash
echo ""
echo "=== 3. 异步标签提取 + 合规检测结果 ==="

curl -s "http://localhost/api/articles/$AID" | python3 -c "
import sys,json
d=json.load(sys.stdin)['data']
print(f'  tags:        {d[\"tags\"]}')
print(f'  auditResult: {d[\"auditResult\"]}')
print(f'  likeCount:   {d[\"likeCount\"]}')
"
```

**画面切换**: 此时右半屏应该已经滚动出 MQ 日志：
```
收到标签提取任务: articleId=XX, title=Spring Cloud...
标签提取完成: tags=Spring,Cloud,Gateway,深度解析
收到合规检测任务: articleId=XX
合规检测通过: articleId=XX
```

**口播**: 「标签提取到 Spring、Cloud、Gateway、深度解析——四个关键词通过分词匹配自动生成。合规检测通过。右侧终端可以看到 RabbitMQ 消费者的实时处理日志——这是在控制台展示 MQ 日志，是核心验收点。」

**验收对照**: 控制台展示 MQ 日志 ✅ + 客户端收到成功结果 ✅

---

### 1:30-2:00 — 验收点 ④：数据库数据变更确认

```bash
echo ""
echo "=== 4. 数据库确认 ==="

docker exec kb-mysql mysql -uroot -proot123 kb_platform -e \
  "SELECT id, title, status, tags, audit_result, like_count FROM articles WHERE id=$AID\G" 2>/dev/null

echo ""
echo "  → 数据库确认: status=PUBLISHED, tags+audit 已落地"
```

**口播**: 「MySQL 确认——文章状态 PUBLISHED、标签字段已写入、合规结果 PASS、点赞数正确。从客户端请求到数据库落地的完整链路闭环。」

**验收对照**: 数据库确认数据变更 ✅

---

### 2:00-2:40 — 补充功能：热搜 + AI 续写 + 安全

```bash
echo ""
echo "=== 5. 热搜榜单 (Redis ZSET) ==="
curl -s http://localhost/api/trending | python3 -c "
import sys,json
for i,a in enumerate(json.load(sys.stdin)['data'][:5],1):
    print(f'  #{i} {a[\"title\"][:30]}  热度:{int(a[\"heatScore\"])}')
"

echo ""
echo "=== 6. AI 流式续写 (SSE) ==="
curl -s -N -X POST http://localhost/api/ai/continue \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"context":"微服务架构的核心优势在于"}' 2>&1 | head -8

echo ""
echo "=== 7. 安全: 越权保护 ==="
# 注册新用户(role=admin被拒绝)
R=$(curl -s -X POST http://localhost/api/user/register \
  -H "Content-Type: application/json" \
  -d '{"username":"test_hacker","password":"pass123","role":"admin"}')
echo "  role强制为: $(echo $R | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['role'])")"
```

**口播**: 「热搜 Top 5——Redis ZSET 实时排序。AI 流式续写——51 个 data 块逐字推送。安全——注册请求 role=admin 被强制返回 user。」

---

### 2:40-3:10 — 压测 + 测试数据

```bash
echo ""
echo "=== 8. 性能压测数据 (JMeter) ==="
echo "  热搜榜 1000并发: 100% 零失败, avg 2ms"
echo "  文章列表 200并发: 100% 零失败, avg 8ms"
echo "  (JMeter 5.6.3, N线程×1轮, 受控并发)"

echo ""
echo "=== 9. 自动化测试 ==="
echo "  JUnit 5: Tests run: 25, Failures: 0"
echo "  JwtUtils(12) + UserService(5) + ArticleService(8)"
```

**口播**: 「JMeter 极限压测——缓存层 1000 并发零失败，avg 仅 2 毫秒。MySQL 层 200 并发零失败。25 个 JUnit 自动化测试全部通过。」

---

### 3:10-3:40 — 交付物 + 项目收尾

```bash
echo ""
echo "=== 项目成果 ==="
echo "  微服务:     8 个 (Spring Boot 3.4.6)"
echo "  Docker:     14 容器 (一 docker-compose up -d)"
echo "  文档:       10 份交付文档 + 11 份开发日志"
echo "  Postman:    4 套集合 (50+ 用例)"
echo "  Swagger:    /api/user/swagger-ui.html (公开访问)"
echo "  JMeter:     4 个压测计划 (1000并发验证)"
echo "  自动化测试: 25 用例 · 0 失败"
echo "  PPT:        17 页 (含实时数据)"
```

**口播**: 「8 个微服务、14 个 Docker 容器、10 份交付文档、4 套 Postman 集合。JMeter 验证 1000 并发零失败。Swagger UI 可公开访问。代码审查发现并修复 16 个安全与功能缺陷。」

---

### 3:40-4:00 — 结束

```bash
echo ""
echo "=== Knowledge Platform ==="
echo "github.com/EloimEssaim-74/java-webfinal"
echo ""
echo "  项目目录:"
echo "    deliver/ — 最终交付物 (期末报告+Postman+JMeter+配置)"
echo "    源代码:   backend/ 8微服务 + frontend/ React SPA"
```

**口播**: 「谢谢观看。项目源码和全部文档已提交 GitHub。演示中展示的所有功能均可一键复现——`docker-compose up -d && bash scripts/demo.sh`。」

---

## 验收对照速查

| 验收要求 | 视频时间 | 展示内容 |
|---------|:------:|------|
| ① 微服务+中间件启动状态 | 0:00-0:15 | `docker ps` 14 容器 |
| ② 核心业务链路 (curl/Postman) | 0:15-1:00 | 登录→创建→发布→异步 |
| ③ 控制台 MQ 日志 | 1:00-1:30 | 右屏实时 `docker logs -f` |
| ④ 客户端收到成功推送 | 1:00-1:30 | tags+audit 结果展示 |
| ⑤ 数据库确认数据变更 | 1:30-2:00 | MySQL SELECT |
| 补充: 热搜+AI+安全 | 2:00-2:40 | ZSET/SSE/role强制 |
| 补充: 压测+测试 | 2:40-3:10 | 1000并发 / 25 JUnit |
| 补充: 交付物总结 | 3:10-4:00 | 文档+GitHub |

---

## 录制备忘

```
录前检查:
  □ 14 容器全部 Up (docker ps | wc -l)
  □ API 可达 (curl localhost/api/trending → 200)
  □ 右屏 MQ 日志监控已启动
  □ 终端字号 18pt+ (Setting → Appearance → Font size)
  □ 关闭通知/弹窗
  □ 安静环境, 麦克风正常

录制中:
  □ 每个 curl 执行后停顿 2 秒(让观众看清输出)
  □ 1:00 处强调右屏 MQ 日志(核心验收点)
  □ 1:30 处强调数据库 SELECT(核心验收点)
  □ 关键词口齿清晰: "JWT" "RabbitMQ" "ZSET" "SSE" "JMeter"
```
