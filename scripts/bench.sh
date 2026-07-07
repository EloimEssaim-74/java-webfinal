#!/bin/bash
# ===================================================================
#  Knowledge Platform — 压力测试
#  bash bench.sh          → bash 受控并发 (15/25/50/100)
#  jmeter -n -t xxx.jmx   → JMeter 受控并发 (50/100 线程)
#
#  JMeter 测试计划: deploy/jmeter/
#    combined-test-plan.jmx  → 热搜50+文章25+详情25 (300 req, 0% err)
#    trending-100.jmx        → 热搜100×2 (200 req, 0% err, avg 2ms)
#    articles-50.jmx         → 文章50×3 (150 req, 0% err, avg 8ms)
#    detail-25.jmx           → 详情25×3 (75 req, 0% err, avg 7ms)
# ===================================================================
#  Knowledge Platform — 压力测试 (纯 bash 并发)
#  用法: bash bench.sh
#  每场景: 15 并发 × 10 秒
# ===================================================================
set -e

BASE="http://localhost"
CONCURRENT=15
DURATION=10

B="\033[1m"; G="\033[32m"; Y="\033[33m"; R="\033[0m"

bench() {
    local name="$1"; local url="$2"; local method="${3:-GET}"
    local body="$4"; local token="$5"

    echo ""
    echo "============================================================"
    echo -e "  ${B}$name${R}"
    echo "  $method $url"
    echo "  ${CONCURRENT} 并发 × ${DURATION}s"
    echo "============================================================"

    # 构建 curl 命令
    local curl_cmd
    if [ "$method" = "POST" ]; then
        curl_cmd="curl -s -o /dev/null -w '%{http_code} %{time_total}' -X POST '$url' -H 'Content-Type: application/json' -H 'Authorization: Bearer $token' -d '$body'"
    else
        curl_cmd="curl -s -o /dev/null -w '%{http_code} %{time_total}' '$url'"
    fi

    local tmp=$(mktemp -d)
    local start=$(date +%s)
    local end_time=$((start + DURATION))

    # 启动并发 worker — 每个 worker 独立循环
    for i in $(seq 1 $CONCURRENT); do
        (
            while [ "$(date +%s)" -lt "$end_time" ]; do
                eval "$curl_cmd 2>/dev/null"
                echo ""
            done
        ) > "$tmp/w$i" 2>/dev/null &
    done
    wait
    local end=$(date +%s)

    # 收集所有有效行 (过滤空行)
    local all=$(mktemp)
    for i in $(seq 1 $CONCURRENT); do
        grep -E "^[0-9]{3} [0-9.]+$" "$tmp/w$i" 2>/dev/null >> "$all" || true
    done
    local total=$(wc -l < "$all" 2>/dev/null || echo 0)
    [ "$total" -eq 0 ] && echo "  无有效数据" && rm -rf "$tmp" "$all" && return

    local elapsed=$((end - start))
    [ "$elapsed" -eq 0 ] && elapsed=1
    local qps=$(echo "scale=1; $total / $elapsed" | bc 2>/dev/null || echo "0")

    # HTTP 状态码统计
    local ok=$(grep -c "^2" "$all" 2>/dev/null || echo 0)
    local err=$((total - ok))
    local err_rate=$(echo "scale=1; $err * 100 / $total" | bc 2>/dev/null || echo "0")

    # 延迟统计 (取 time_total 列)
    awk '{print $2}' "$all" | sort -n > "$tmp/lat"
    local sample=$(wc -l < "$tmp/lat" 2>/dev/null || echo 0)
    local avg=$(awk '{sum+=$1; n++} END {if(n>0) printf "%.1f", sum/n*1000}' "$tmp/lat" 2>/dev/null || echo "0")
    local p50=$(awk "NR==int($sample*0.50+0.5)" "$tmp/lat" 2>/dev/null || echo "0")
    local p95=$(awk "NR==int($sample*0.95+0.5)" "$tmp/lat" 2>/dev/null || echo "0")
    local p99=$(awk "NR==int($sample*0.99+0.5)" "$tmp/lat" 2>/dev/null || echo "0")
    p50=$(echo "scale=1; $p50 * 1000" | bc 2>/dev/null || echo "0")
    p95=$(echo "scale=1; $p95 * 1000" | bc 2>/dev/null || echo "0")
    p99=$(echo "scale=1; $p99 * 1000" | bc 2>/dev/null || echo "0")

    echo ""
    echo -e "  ${G}总请求:${R}   $total"
    echo -e "  ${G}QPS:${R}       ${Y}$qps${R}"
    echo -e "  ${G}成功率:${R}   $(echo "scale=1; 100-$err_rate" | bc)%"
    echo -e "  ${G}平均:${R}     ${avg}ms"
    echo -e "  ${G}P50:${R}      ${p50}ms"
    echo -e "  ${G}P95:${R}      ${p95}ms"
    echo -e "  ${G}P99:${R}      ${p99}ms"

    rm -rf "$tmp" "$all"
}

# ===================================================================
echo "============================================================"
echo -e "  ${B}Knowledge Platform — 压力测试${R}"
echo "============================================================"
echo ""
curl -s -o /dev/null -w "  API: HTTP %{http_code}\n" "$BASE/api/trending"

# 场景 1 — 纯缓存读
bench "场景1: 热搜榜 | GET /api/trending (Redis ZSET + Caffeine L1)" \
      "$BASE/api/trending"

# 场景 2 — MySQL 读 + 分页
bench "场景2: 文章列表 | GET /api/articles?page=1&size=10 (MySQL + 联合索引)" \
      "$BASE/api/articles?page=1&size=10"

# 场景 3 — 读 + Redis 写 (热度)
bench "场景3: 文章详情 | GET /api/articles/20 (读 + Redis ZINCRBY + 防重)" \
      "$BASE/api/articles/20"

# 场景 4 — 写操作
TOKEN_A=$(curl -s -X POST "$BASE/api/user/login" -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"alice123456"}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['token'])")
bench "场景4: 文章创建 | POST /api/articles (写 MySQL + 事务)" \
      "$BASE/api/articles" "POST" \
      '{"title":"bench","content":"bench"}' "$TOKEN_A"

echo ""
echo "============================================================"
echo -e "  ${G}压测完成${R} — 每个场景 ${CONCURRENT} 并发 × ${DURATION}s"
echo "  环境: WSL2 + Docker Desktop, bash 并发模拟"
echo "============================================================"
