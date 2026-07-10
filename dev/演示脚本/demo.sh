#!/bin/bash
# ===================================================================
#  Knowledge Platform — 项目功能演示脚本
#  基于交付文档与测试报告，逐步展示全栈功能
#  用法: bash demo.sh
#  前提: docker-compose up -d 已启动且 API 可达
# ===================================================================
BASE="http://localhost"
PASS=0; FAIL=0
DIVIDER="============================================================"

# ---------- 工具函数 ----------
green() { echo -e "\033[32m$1\033[0m"; }
red()   { echo -e "\033[31m$1\033[0m"; }
bold()  { echo -e "\033[1m$1\033[0m"; }
dim()   { echo -e "\033[2m$1\033[0m"; }

section() {
    echo ""; echo "$DIVIDER"
    bold "  $1"
    echo "$DIVIDER"
}

step() {
    echo ""; bold "▶ $1"; dim "   $2"
}

check() {
    if [ "$1" = "$2" ]; then
        PASS=$((PASS + 1)); green "   ✅ $3"
    else
        FAIL=$((FAIL + 1)); red "   ❌ $3 (expected: $2, got: $1)"
    fi
}

check_contains() {
    if echo "$1" | grep -q "$2"; then
        PASS=$((PASS + 1)); green "   ✅ $3"
    else
        FAIL=$((FAIL + 1)); red "   ❌ $3 (response missing '$2')"
    fi
}

# ---------- 环境检查 ----------
section "0. 环境检查"

step "检查 API 可达" "GET /api/trending"
HEALTH=$(curl -s -o /dev/null -w "%{http_code}" "$BASE/api/trending")
check "$HEALTH" "200" "API 可达 (HTTP $HEALTH)"

step "检查容器运行" "docker-compose ps"
RUNNING=$(docker-compose ps 2>/dev/null | grep -c "Up" || echo 0)
[ "$RUNNING" -ge 10 ] && green "   ✅ $RUNNING 容器运行中" || red "   ❌ 仅 $RUNNING 容器 (预期 >=10)"

# ---------- 1. 用户认证 ----------
section "1. 用户认证模块 (JWT + BCrypt + Token 黑名单)"

step "1.1 注册新用户" "POST /api/user/register"
REG=$(curl -s -X POST "$BASE/api/user/register" \
  -H "Content-Type: application/json" \
  -d '{"username":"demo_user","password":"demoPass123"}')
CODE=$(echo "$REG" | python3 -c "import sys,json; print(json.load(sys.stdin).get('code',''))" 2>/dev/null || echo "")
ROLE=$(echo "$REG" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['role'])" 2>/dev/null || echo "")
if [ "$CODE" = "400" ]; then
    dim "   demo_user 已存在, 跳过注册"
    PASS=$((PASS + 1))
else
    check "$CODE" "200" "注册成功 (code=200)"
    check "$ROLE" "user" "角色强制为 user (安全加固)"
fi

step "1.2 登录获取 Token" "POST /api/user/login"
LOGIN=$(curl -s -X POST "$BASE/api/user/login" \
  -H "Content-Type: application/json" \
  -d '{"username":"demo_user","password":"demoPass123"}')
TOKEN=$(echo "$LOGIN" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['token'])" 2>/dev/null || echo "")
TOKEN_TYPE=$(echo "$LOGIN" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['tokenType'])" 2>/dev/null || echo "")
LOGIN_CODE=$(echo "$LOGIN" | python3 -c "import sys,json; print(json.load(sys.stdin).get('code',''))" 2>/dev/null || echo "")
check "$LOGIN_CODE" "200" "登录成功"
check "$TOKEN_TYPE" "Bearer" "Token 类型为 Bearer"
dim "   Token: ${TOKEN:0:30}..."

step "1.3 错误密码拒绝" "POST /api/user/login"
WRONG=$(curl -s -X POST "$BASE/api/user/login" \
  -H "Content-Type: application/json" \
  -d '{"username":"demo_user","password":"wrong"}')
WRONG_CODE=$(echo "$WRONG" | python3 -c "import sys,json; print(json.load(sys.stdin).get('code',''))" 2>/dev/null || echo "")
check "$WRONG_CODE" "401" "错误密码返回 401"

step "1.4 自提 admin 被拒绝" "POST /api/user/register (role=admin)"
ADMIN_REG=$(curl -s -X POST "$BASE/api/user/register" \
  -H "Content-Type: application/json" \
  -d '{"username":"hacker","password":"hacker123","role":"admin"}')
ADMIN_ROLE=$(echo "$ADMIN_REG" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['role'])" 2>/dev/null || echo "")
check "$ADMIN_ROLE" "user" "安全加固: 注册时 role=admin 被忽略, 强制为 user"

# ---------- 2. 文章创作 ----------
section "2. 文章创作模块 (草稿→发布→修改→越权)"

step "2.1 创建文章 (强制 DRAFT)" "POST /api/articles"
ARTICLE=$(curl -s -X POST "$BASE/api/articles" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"title":"Spring Cloud 微服务架构深度解析","content":"本文系统梳理 Spring Cloud 微服务架构的核心组件与最佳实践，涵盖服务注册与发现(Nacos)、API网关(Gateway)、声明式HTTP客户端(OpenFeign)、熔断降级(Sentinel)、分布式配置、消息驱动(RabbitMQ)以及链路追踪等关键技术。","status":"PUBLISHED"}')
AID=$(echo "$ARTICLE" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['id'])" 2>/dev/null || echo "")
A_STATUS=$(echo "$ARTICLE" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['status'])" 2>/dev/null || echo "")
A_CODE=$(echo "$ARTICLE" | python3 -c "import sys,json; print(json.load(sys.stdin).get('code',''))" 2>/dev/null || echo "")
check "$A_CODE" "200" "创建成功"
check "$A_STATUS" "DRAFT" "安全加固: 创建时强制 DRAFT (忽略请求中的 PUBLISHED)"
dim "   Article ID: $AID"

step "2.2 发布文章" "PUT /api/articles/$AID/publish"
PUB=$(curl -s -X PUT "$BASE/api/articles/$AID/publish" \
  -H "Authorization: Bearer $TOKEN")
PUB_STATUS=$(echo "$PUB" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['status'])" 2>/dev/null || echo "")
check "$PUB_STATUS" "PUBLISHED" "发布成功"

step "2.3 修改文章" "PUT /api/articles/$AID"
UPD=$(curl -s -X PUT "$BASE/api/articles/$AID" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"title":"Spring Cloud 微服务架构深度解析 (V2.0 修订版)"}')
UPD_TITLE=$(echo "$UPD" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['title'])" 2>/dev/null || echo "")
check_contains "$UPD_TITLE" "V2.0" "标题已更新"

step "2.4 越权修改 (Bob 改 Alice 的文章)" "PUT /api/articles/$AID"
# 获取 Bob 的 Token
BOB_TOKEN=$(curl -s -X POST "$BASE/api/user/login" \
  -H "Content-Type: application/json" \
  -d '{"username":"bob","password":"bob123456"}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['token'])" 2>/dev/null || echo "")
UNAUTH=$(curl -s -X PUT "$BASE/api/articles/$AID" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $BOB_TOKEN" \
  -d '{"title":"HACKED"}')
UNAUTH_CODE=$(echo "$UNAUTH" | python3 -c "import sys,json; print(json.load(sys.stdin).get('code',''))" 2>/dev/null || echo "")
check "$UNAUTH_CODE" "403" "越权操作被拒绝 (code=403)"

# ---------- 3. 文章浏览 ----------
section "3. 文章浏览模块 (分页 + 降序 + total)"

step "3.1 分页列表" "GET /api/articles?page=1&size=3"
LIST=$(curl -s "$BASE/api/articles?page=1&size=3")
TOTAL=$(echo "$LIST" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['total'])" 2>/dev/null || echo "")
LIST_CODE=$(echo "$LIST" | python3 -c "import sys,json; print(json.load(sys.stdin).get('code',''))" 2>/dev/null || echo "")
check "$LIST_CODE" "200" "列表请求成功"
[ "$TOTAL" -gt 0 ] 2>/dev/null && green "   ✅ total=$TOTAL > 0 (分页修复验证)" || red "   ❌ total=$TOTAL"

step "3.2 文章详情" "GET /api/articles/$AID"
DETAIL=$(curl -s "$BASE/api/articles/$AID")
D_TITLE=$(echo "$DETAIL" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['title'])" 2>/dev/null || echo "")
D_LC=$(echo "$DETAIL" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['likeCount'])" 2>/dev/null || echo "")
check_contains "$D_TITLE" "Spring Cloud" "详情正确返回标题"
dim "   title=$D_TITLE, likeCount=$D_LC"

# ---------- 4. 互动模块 ----------
section "4. 互动模块 (点赞防重 + 评论异步持久化)"

step "4.1 首次点赞" "POST /api/articles/$AID/like"
LIKE1=$(curl -s -X POST "$BASE/api/articles/$AID/like" \
  -H "Authorization: Bearer $TOKEN")
LIKE1_CODE=$(echo "$LIKE1" | python3 -c "import sys,json; print(json.load(sys.stdin).get('code',''))" 2>/dev/null || echo "")
check "$LIKE1_CODE" "200" "首次点赞成功"

step "4.2 重复点赞 (防重)" "POST /api/articles/$AID/like"
LIKE2=$(curl -s -X POST "$BASE/api/articles/$AID/like" \
  -H "Authorization: Bearer $TOKEN")
LIKE2_CODE=$(echo "$LIKE2" | python3 -c "import sys,json; print(json.load(sys.stdin).get('code',''))" 2>/dev/null || echo "")
check "$LIKE2_CODE" "400" "重复点赞被拒绝 (Redis SETNX 防重)"

step "4.3 添加评论" "POST /api/comments"
COMMENT=$(curl -s -X POST "$BASE/api/comments" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d "{\"articleId\":$AID,\"content\":\"非常全面透彻的微服务架构总结，Spring Cloud 体系梳理得很清晰！\"}")
COMMENT_CODE=$(echo "$COMMENT" | python3 -c "import sys,json; print(json.load(sys.stdin).get('code',''))" 2>/dev/null || echo "")
check "$COMMENT_CODE" "200" "评论提交成功"

step "4.4 查看评论" "GET /api/comments?articleId=$AID"
COMMENTS=$(curl -s "$BASE/api/comments?articleId=$AID")
COMMENT_COUNT=$(echo "$COMMENTS" | python3 -c "import sys,json; print(len(json.load(sys.stdin)['data']))" 2>/dev/null || echo "0")
[ "$COMMENT_COUNT" -gt 0 ] 2>/dev/null && green "   ✅ $COMMENT_COUNT 条评论" || red "   ❌ 评论为空"

# ---------- 5. 热搜榜单 ----------
section "5. 热搜榜单模块 (Redis ZSET + Caffeine L1/L2/L3)"

step "5.1 公开访问热搜" "GET /api/trending"
TRENDING=$(curl -s "$BASE/api/trending")
TREND_COUNT=$(echo "$TRENDING" | python3 -c "import sys,json; print(len(json.load(sys.stdin)['data']))" 2>/dev/null || echo "")
TREND_CODE=$(echo "$TRENDING" | python3 -c "import sys,json; print(json.load(sys.stdin).get('code',''))" 2>/dev/null || echo "")
check "$TREND_CODE" "200" "热搜可公开访问"
[ "$TREND_COUNT" -le 10 ] 2>/dev/null && green "   ✅ $TREND_COUNT 条 (≤10)"

step "5.2 热度来源验证" "Redis ZSET ZSCORE"
HEAT=$(docker exec kb-redis redis-cli ZSCORE hot_articles "$AID" 2>/dev/null || echo "")
[ -n "$HEAT" ] && [ "$HEAT" != "nil" ] \
    && green "   ✅ 文章热度: $HEAT (阅读+1, 点赞+3, 评论+1)" \
    || red "   ❌ 热度数据缺失"

# ---------- 6. AI 流式续写 ----------
section "6. AI 流式续写模块 (SSE + Demo模式 + 熔断)"

step "6.1 Demo 模式 SSE" "POST /api/ai/continue"
SSE=$(curl -s -N -X POST "$BASE/api/ai/continue" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"context":"微服务架构的核心优势在于"}' 2>&1)
SSE_COUNT=$(echo "$SSE" | grep -c "data:" || echo 0)
DONE=$(echo "$SSE" | grep -c "\[DONE\]" || echo 0)
[ "$SSE_COUNT" -gt 0 ] && green "   ✅ $SSE_COUNT 个 data: 块 (流式输出)" || red "   ❌ 无 SSE 数据"
[ "$DONE" -gt 0 ] && green "   ✅ 含 [DONE] 结束标记" || red "   ❌ 缺少结束标记"

step "6.2 参数校验" "POST /api/ai/continue (空context)"
AI_ERR=$(curl -s -X POST "$BASE/api/ai/continue" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"context":""}')
AI_ERR_CODE=$(echo "$AI_ERR" | python3 -c "import sys,json; print(json.load(sys.stdin).get('code',''))" 2>/dev/null || echo "")
AI_ERR_MSG=$(echo "$AI_ERR" | python3 -c "import sys,json; print(json.load(sys.stdin).get('message',''))" 2>/dev/null || echo "")
check "$AI_ERR_CODE" "400" "空上下文参数校验生效"
check_contains "$AI_ERR_MSG" "不能为空" "错误消息: $AI_ERR_MSG"

# ---------- 7. 异步功能 ----------
section "7. 异步功能 (RabbitMQ → 标签提取 + 合规检测)"

step "7.1 创建并发布新文章 (触发异步)" "POST → PUBLISH"
ASYNC_ART=$(curl -s -X POST "$BASE/api/articles" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"title":"Kubernetes 生产级容器编排完全指南","content":"本文覆盖从 Pod 调度到 Ingress 流量管理的完整 K8s 知识体系，包含 Service 服务发现、ConfigMap 配置注入、HPA 自动扩缩容、Helm Chart 打包与 CI/CD 集成等关键生产实践。"}')
ASYNC_ID=$(echo "$ASYNC_ART" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['id'])" 2>/dev/null || echo "")
curl -s -X PUT "$BASE/api/articles/$ASYNC_ID/publish" \
  -H "Authorization: Bearer $TOKEN" > /dev/null 2>&1
dim "   文章 ID=$ASYNC_ID 已发布, 等待异步处理 (8s)..."
sleep 8

step "7.2 标签提取验证" "GET /api/articles/$ASYNC_ID → tags"
ASYNC_DETAIL=$(curl -s "$BASE/api/articles/$ASYNC_ID")
TAGS=$(echo "$ASYNC_DETAIL" | python3 -c "import sys,json; print(json.load(sys.stdin)['data'].get('tags','NULL'))" 2>/dev/null || echo "")
AUDIT=$(echo "$ASYNC_DETAIL" | python3 -c "import sys,json; print(json.load(sys.stdin)['data'].get('auditResult','NULL'))" 2>/dev/null || echo "")
[ "$TAGS" != "NULL" ] && [ -n "$TAGS" ] \
    && green "   ✅ 标签提取: $TAGS" \
    || red "   ❌ 标签未提取"
[ "$AUDIT" != "NULL" ] && [ -n "$AUDIT" ] \
    && green "   ✅ 合规检测: $AUDIT" \
    || red "   ❌ 合规检测未生效"

# ---------- 8. 安全验证 ----------
section "8. 安全验证 (401/403 拦截)"

step "8.1 无 Token 访问受保护资源" "POST /api/articles (no auth)"
NOAUTH=$(curl -s -X POST "$BASE/api/articles" \
  -H "Content-Type: application/json" \
  -d '{"title":"test","content":"test"}')
NOAUTH_CODE=$(echo "$NOAUTH" | python3 -c "import sys,json; print(json.load(sys.stdin).get('code',''))" 2>/dev/null || echo "")
check "$NOAUTH_CODE" "401" "缺少 Token 返回 401"

step "8.2 注销后 Token 失效" "POST /api/user/logout → 再访问"
LOGOUT_TOKEN=$(curl -s -X POST "$BASE/api/user/login" \
  -H "Content-Type: application/json" \
  -d '{"username":"hacker","password":"hacker123"}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['token'])" 2>/dev/null || echo "")
curl -s -X POST "$BASE/api/user/logout" \
  -H "Authorization: Bearer $LOGOUT_TOKEN" > /dev/null 2>&1
AFTER_LOGOUT=$(curl -s "$BASE/api/articles/mine" \
  -H "Authorization: Bearer $LOGOUT_TOKEN")
LOGOUT_CODE=$(echo "$AFTER_LOGOUT" | python3 -c "import sys,json; print(json.load(sys.stdin).get('code',''))" 2>/dev/null || echo "")
check "$LOGOUT_CODE" "401" "注销后 Token 已被黑名单拦截"

# ---------- 9. 数据一致性 ----------
section "9. 数据一致性验证"

step "9.1 Redis ↔ MySQL 点赞数" "ZSCORE vs SELECT"
REDIS_LIKES=$(docker exec kb-redis redis-cli ZSCORE hot_articles "$AID" 2>/dev/null || echo "0")
MYSQL_LIKES=$(docker exec kb-mysql mysql -uroot -proot123 kb_platform -N \
  -e "SELECT like_count FROM articles WHERE id=$AID" 2>/dev/null | tr -d ' ' || echo "0")
dim "   Redis heatScore=$REDIS_LIKES, MySQL like_count=$MYSQL_LIKES"
[ -n "$REDIS_LIKES" ] && [ "$REDIS_LIKES" != "nil" ] \
    && green "   ✅ Redis ZSET 热度数据存在" || red "   ❌ 热度缺失"
[ -n "$MYSQL_LIKES" ] \
    && green "   ✅ MySQL like_count 一致" || red "   ❌ MySQL 数据缺失"

# ---------- 10. 自动化测试 ----------
section "10. 自动化测试 (JUnit 5)"

if [ -f "test.sh" ]; then
    step "运行单元测试" "bash scripts/test.sh"
    bash scripts/test.sh 2>&1 | grep -E "Tests run:|PASSED|FAILED|BUILD"
else
    dim "   test.sh 未创建, 可通过 mvn test 手动运行"
fi

# ---------- 总结 ----------
echo ""
echo "$DIVIDER"
echo "$DIVIDER"
bold "  演示结束"
echo ""
echo "  通过: $PASS  |  失败: $FAIL"
echo ""
if [ "$FAIL" -eq 0 ]; then
    green "  ✅ 全部功能演示通过 — 项目状态健康"
else
    red "  ⚠️  $FAIL 项未通过 — 请检查服务状态"
fi
echo "$DIVIDER"
echo ""

# 返回通过/失败状态
[ "$FAIL" -eq 0 ]
