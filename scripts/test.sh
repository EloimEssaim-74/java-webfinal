#!/bin/bash
# ============================================
# Knowledge Platform — 自动化测试脚本
# 用法: bash test.sh
# ============================================
set -e

cd "$(dirname "$0")"

echo "============================================"
echo "  Knowledge Platform — 自动化测试"
echo "============================================"
echo ""

# 运行测试
mvn test -q 2>&1 | grep -v "DEBUG\|^[0-9].*TRACE\|^\[" | tail -20

echo ""
echo "--- 测试统计 ---"
find . -path "*/target/surefire-reports/*.txt" -exec grep "Tests run:" {} \;

echo ""
echo "--- 构建状态 ---"
if mvn test -q 2>&1 | grep -q "BUILD SUCCESS"; then
    echo "✅ ALL TESTS PASSED"
else
    echo "❌ SOME TESTS FAILED"
    echo ""
    echo "--- 失败详情 ---"
    for f in $(find . -path "*/target/surefire-reports/*.txt"); do
        if grep -q "Failures: [1-9]\|Errors: [1-9]" "$f" 2>/dev/null; then
            echo "  $(basename $f .txt | sed 's/TEST-//'):"
            grep -A2 "FAILED\|ERROR" "$f" | head -20
        fi
    done
fi
