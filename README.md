# Knowledge Platform — 基于 AI 的智能知识库与内容发布平台

[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.6-brightgreen)](https://spring.io/projects/spring-boot)
[![React](https://img.shields.io/badge/React-18-61DAFB?logo=react)](https://react.dev/)
[![Java](https://img.shields.io/badge/Java-17-orange)](https://openjdk.org/projects/jdk/17/)
[![Docker](https://img.shields.io/badge/Docker-Compose-2496ED?logo=docker)](https://docs.docker.com/compose/)
[![License](https://img.shields.io/badge/License-MIT-blue)](LICENSE)

一个具有 **AI 能力** 的知乎式知识社区后台系统。在用户、文章、互动等基础功能之上，深入攻克了网关限流、实时热榜、异步消息广播及 AI 流式写作辅助等核心技术挑战。

---

## 架构概览

```
浏览器 / App
    │
    ▼
┌─────────────────────────────────────────────┐
│  Nginx (反向代理 · 限流 · 静态文件)            │
└──────────────┬──────────────────────────────┘
               │
    ┌──────────┴──────────┐
    ▼                     ▼
┌──────────┐       ┌──────────┐
│ Gateway 1│       │ Gateway 2│   ← Spring Cloud Gateway
│ :8080    │       │ :8080    │      JWT 鉴权 · Sentinel 限流
└────┬─────┘       └────┬─────┘
     │                  │
     └───────┬──────────┘
             │
   ┌─────────┼─────────┬──────────────┐
   ▼         ▼         ▼              ▼
┌──────┐ ┌──────┐ ┌──────────┐ ┌──────────┐
│ User │ │Article│ │Interact  │ │ AI Asst  │
│:9001 │ │:9002 │ │:9003     │ │ :9012    │
└──┬───┘ └──┬───┘ └────┬─────┘ └──────────┘
   │        │          │
   └────────┼──────────┘
            │
   ┌────────┼──────────┐
   ▼        ▼          ▼
┌──────┐ ┌──────┐ ┌──────────┐
│MySQL │ │Redis │ │RabbitMQ  │
│ 主+从 │ │  7   │ │   3      │
└──────┘ └──────┘ └──┬───┬───┘
                     │   │
                ┌────▼─┐ ┌▼────────┐
                │ Tag  │ │Compliance│
                │Extract│ │ Check   │
                └──────┘ └─────────┘
```

---

## 核心功能

| 模块 | 功能 | 状态 |
|------|------|:----:|
| 🔐 用户认证 | JWT 签发与校验、BCrypt 加密、Token 黑名单注销、角色权限 | ✅ |
| 📝 文章管理 | 草稿/发布、分页浏览、逻辑删除、作者本人可查看草稿 | ✅ |
| 💬 互动系统 | 评论异步持久化、点赞防重（Redis SETNX）、Redis Stream 削峰 | ✅ |
| 🔥 实时热搜 | Redis ZSET 热度排行、L1/L2/L3 三级缓存、每日衰减、防刷 | ✅ |
| 🤖 AI 续写 | SSE 流式输出、OpenAI 兼容 API、Resilience4j 熔断、Demo 模式 | ✅ |
| 🏷️ 标签提取 | 文章发布 → RabbitMQ → 规则匹配关键词 → 回写数据库 | ✅ |
| 🛡️ 合规检测 | 敏感词/可疑词自动审核 → PASS/REVIEW/BLOCK | ✅ |
| 🚦 网关限流 | Nginx 令牌桶 + Sentinel 动态规则（Nacos 热更新） | ✅ |

---

## 技术栈

| 层级 | 技术 |
|------|------|
| 后端框架 | Spring Boot 3.2.6 · Spring Cloud 2023.0.3 · Spring Cloud Alibaba 2023.0.1.2 |
| API 网关 | Spring Cloud Gateway（响应式） |
| 服务注册 | Nacos 2.2 |
| 流量控制 | Sentinel（网关层 QPS 限流 + Nacos 动态规则） |
| ORM | MyBatis-Plus 3.5.7（分页 · 逻辑删除 · 自动填充） |
| 数据库 | MySQL 8.0（主从复制 · 读写分离） |
| 缓存 | Redis 7（ZSET 热搜 · Stream 削峰 · Pub/Sub 缓存刷新）+ Caffeine L1 缓存 |
| 消息队列 | RabbitMQ 3（Topic Exchange → 标签提取 + 合规检测） |
| 安全认证 | jjwt 0.12.6（HMAC-SHA384）· BCrypt · Druid SQL 防火墙 |
| AI 对接 | Spring WebFlux + SSE · Resilience4j 熔断 |
| 前端 | React 18 · Vite · Tailwind CSS |
| 负载均衡 | Nginx（反向代理 · HTTP 缓存 · Gzip · 限流） |
| 容器化 | Docker + Docker Compose |

---

## 快速开始

### 前置要求

- Java 17+
- Docker & Docker Compose
- Maven 3.8+（本地构建时）

### 1. 克隆项目

```bash
git clone https://github.com/your-username/knowledge-platform.git
cd knowledge-platform
```

### 2. 编译后端

```bash
mvn package -DskipTests
```

### 3. 构建前端（可选）

```bash
cd frontend
npm install && npm run build
cd ..
```

### 4. 一键启动

```bash
docker-compose up -d
```

等待约 30 秒所有服务就绪：

```bash
# 健康检查
curl http://localhost/api/trending
# → {"code":200,"message":"success","data":[...]}

# 前端页面
curl http://localhost/
# → HTTP 200 (React SPA)
```

### 5. 验证核心流程

```bash
# 注册
curl -X POST http://localhost/api/user/register \
  -H "Content-Type: application/json" \
  -d '{"username":"demo","password":"demo123456"}'

# 登录（获取 Token）
TOKEN=$(curl -s -X POST http://localhost/api/user/login \
  -H "Content-Type: application/json" \
  -d '{"username":"demo","password":"demo123456"}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['token'])")

# 发布文章
curl -X POST http://localhost/api/articles \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"title":"Hello World","content":"这是我的第一篇文章"}'
```

### 6. 停止

```bash
docker-compose down
```

---

## 项目结构

```
knowledge-platform/
├── README.md                   # 本文件
├── TESTING.md                  # 功能测试指引
├── pom.xml                     # Maven 父 POM
├── docker-compose.yml          # Docker 编排（12 容器）
│
├── backend/                    # 后端微服务 (Spring Boot)
│   ├── common/                 # 公共模块（实体、DTO、VO、工具类）
│   ├── gateway-service/        # API 网关（鉴权 + 限流 + 路由）
│   ├── user-service/           # 用户服务（注册、登录、注销）
│   ├── article-service/        # 文章服务（CRUD、热搜、发布事件）
│   ├── interact-service/       # 互动服务（点赞、评论）
│   ├── ai-assistant-service/   # AI 服务（SSE 流式续写）
│   ├── tag-extract-service/    # 标签提取（RabbitMQ 消费者）
│   └── compliance-service/     # 合规检测（RabbitMQ 消费者）
│
├── frontend/                   # React SPA 前端
│   ├── index.html
│   ├── css/
│   ├── js/                     # api.js · app.js · components.js
│   ├── package.json
│   └── Dockerfile
│
├── docs/                       # 项目文档
│   ├── 开发文档.md              # 完整设计策划书
│   ├── 开发日志/                # Stage 5-13 开发日志
│   └── deliverFiles/           # 交付文档（安全、数据库、AI等）
│
└── deploy/                     # 部署配置
    ├── nginx/nginx.conf        # Nginx 配置（限流、缓存、SSE）
    ├── sql/                    # 数据库 DDL + 主从复制脚本
    └── postman/                # Postman 测试集合
```

---

## API 概览

| 方法 | 路径 | 说明 | 认证 |
|------|------|------|:----:|
| POST | `/api/user/register` | 注册 | — |
| POST | `/api/user/login` | 登录 | — |
| POST | `/api/user/logout` | 注销 | JWT |
| GET | `/api/articles?page=1&size=20` | 文章列表（降序） | — |
| GET | `/api/articles/{id}` | 文章详情 | — |
| POST | `/api/articles` | 创建文章（草稿） | JWT |
| PUT | `/api/articles/{id}` | 修改文章 | JWT |
| PUT | `/api/articles/{id}/publish` | 发布文章 | JWT |
| DELETE | `/api/articles/{id}` | 逻辑删除 | JWT |
| GET | `/api/articles/mine` | 我的文章 | JWT |
| POST | `/api/articles/{id}/like` | 点赞 | JWT |
| POST | `/api/comments` | 评论 | JWT |
| GET | `/api/comments?articleId={id}` | 文章评论 | — |
| GET | `/api/trending` | 热搜 Top 10 | — |
| POST | `/api/ai/continue` | AI 流式续写 (SSE) | JWT |

---

## 文档导航

| 文档 | 内容 |
|------|------|
| [TESTING.md](TESTING.md) | 功能测试指引 — 完整 curl 命令演示 |
| [开发文档](docs/开发文档.md) | 设计策划书 — 总体架构与详细设计 |
| [开发日志](docs/开发日志/) | Stage 5-13 各阶段开发记录 |
| [交付文档](docs/deliverFiles/) | 安全策略、数据库设计、测试报告等 10 份 |
| [Postman 集合](deploy/postman/) | 50+ API 测试用例 |

---

## License

MIT
