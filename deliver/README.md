# Knowledge Platform — 最终交付物

> 题目三：智能知识库与内容发布平台
> 代码仓库：github.com/EloimEssaim-74/java-webfinal

## 目录说明

| 目录/文件 | 内容 | 用途 |
|----------|------|------|
| `期末报告.md` | 11章整合报告 | 核心评分材料 |
| `postman/` | 4套Postman集合 (50+用例) | API测试验证 |
| `jmeter/` | 4个JMeter测试计划 | 性能压测复现 |
| `deploy-config/` | docker-compose.yml + nginx.conf + SQL | 一键部署 |
| `api-docs/` | Swagger UI 访问说明 | API文档 |
| `video/` | 演示视频 (占位) | 3-5分钟功能演示 |

## 快速开始

```bash
# 1. 启动全部服务
docker-compose up -d

# 2. 验证
curl http://localhost/api/trending

# 3. API 文档
open http://localhost/api/user/swagger-ui.html

# 4. 运行测试
mvn test

# 5. Postman
导入 deliver/postman/stage12-integration-test.postman_collection.json
```

## 评分对照

| 评分项 | 对应位置 |
|--------|---------|
| 系统开发实现 (50%) | 源码: backend/ 8微服务 + frontend/ React SPA |
| API文档 | api-docs/ + Swagger UI |
| Postman集合 | postman/ |
| 演示视频 (10%) | video/ (待录制) |
| 报告文档 (40%) | 期末报告.md |
