# Swagger API 文档

## 访问地址

| 服务 | Swagger UI 地址 | API Docs 地址 |
|------|----------------|---------------|
| user-service | http://localhost/api/user/swagger-ui.html | http://localhost/api/user/v3/api-docs |
| article-service | http://localhost/api/articles/swagger-ui.html | http://localhost/api/articles/v3/api-docs |
| interact-service | http://localhost/api/comments/swagger-ui.html | http://localhost/api/comments/v3/api-docs |

## 技术栈

- SpringDoc OpenAPI 2.6.0
- 配置: `backend/*/src/main/resources/application.yml` (springdoc.*)
- 网关白名单: `backend/gateway-service/.../AuthPathMatcher.java`
- 公共配置: `backend/common/.../SwaggerConfig.java`

## 注意

各服务通过 Gateway 路由访问。AuthPathMatcher 已将 Swagger 路径加入公开白名单, 无需 JWT Token。
