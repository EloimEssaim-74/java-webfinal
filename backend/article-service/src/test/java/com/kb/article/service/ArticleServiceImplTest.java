package com.kb.article.service;

import com.kb.article.service.impl.ArticleServiceImpl;
import com.kb.common.dto.ArticleCreateRequest;
import com.kb.common.dto.ArticleUpdateRequest;
import com.kb.common.exception.BusinessException;
import com.kb.common.vo.ArticleListItemVO;
import com.kb.common.vo.ArticleVO;
import com.kb.common.vo.PageResult;
import org.junit.jupiter.api.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;MODE=MySQL",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.datasource.read-write-splitting.enabled=false",
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,com.alibaba.cloud.nacos.NacosDiscoveryAutoConfiguration,org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration",
    "spring.rabbitmq.listener.simple.auto-startup=false",
    "spring.sql.init.mode=always",
    "spring.sql.init.schema-locations=classpath:schema.sql"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ArticleServiceImplTest {

    @MockBean
    private RabbitTemplate rabbitTemplate;

    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    @MockBean
    private RedisConnectionFactory redisConnectionFactory;

    @MockBean
    private RedisMessageListenerContainer redisMessageListenerContainer;

    @MockBean
    private HotArticleService hotArticleService;

    @Autowired
    private ArticleServiceImpl articleService;

    private static Long articleId;
    private static final Long AUTHOR_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;

    @Test
    @Order(1)
    @DisplayName("create: 创建文章, status=DRAFT (安全加固)")
    void create_shouldBeDraft() {
        ArticleCreateRequest req = new ArticleCreateRequest();
        req.setTitle("测试文章标题");
        req.setContent("测试文章内容正文");
        req.setStatus("PUBLISHED"); // 尝试直接发布

        ArticleVO vo = articleService.create(req, AUTHOR_ID);

        assertNotNull(vo);
        assertNotNull(vo.getId());
        assertEquals("测试文章标题", vo.getTitle());
        assertEquals("DRAFT", vo.getStatus(), "Stage 13 安全加固: 创建时强制 DRAFT");
        assertEquals(AUTHOR_ID, vo.getAuthorId());
        articleId = vo.getId();
    }

    @Test
    @Order(2)
    @DisplayName("publish: 发布文章, status=PUBLISHED")
    void publish_shouldChangeToPublished() {
        ArticleVO vo = articleService.publish(articleId, AUTHOR_ID, "user");

        assertEquals("PUBLISHED", vo.getStatus());
    }

    @Test
    @Order(3)
    @DisplayName("update: 修改文章标题")
    void update_shouldChangeTitle() {
        ArticleUpdateRequest req = new ArticleUpdateRequest();
        req.setTitle("修改后的标题");

        ArticleVO vo = articleService.update(articleId, req, AUTHOR_ID, "user");

        assertEquals("修改后的标题", vo.getTitle());
    }

    @Test
    @Order(4)
    @DisplayName("update: 越权修改应抛出 BusinessException")
    void update_unauthorized_shouldThrow() {
        ArticleUpdateRequest req = new ArticleUpdateRequest();
        req.setTitle("Hacked!");

        assertThrows(BusinessException.class,
                () -> articleService.update(articleId, req, OTHER_USER_ID, "user"));
    }

    @Test
    @Order(5)
    @DisplayName("list: 分页列表, total>0")
    void list_shouldReturnPaginated() {
        PageResult<ArticleListItemVO> result = articleService.list(1, 10);

        assertNotNull(result.getList());
        assertTrue(result.getTotal() > 0, "total should be > 0 (MybatisPlusConfig deployed)");
        assertEquals(1, result.getPage());
    }

    @Test
    @Order(6)
    @DisplayName("detail: 获取文章详情")
    void detail_shouldReturnFullArticle() {
        ArticleVO vo = articleService.detail(articleId, AUTHOR_ID);

        assertEquals(articleId, vo.getId());
        assertNotNull(vo.getTitle());
        assertNotNull(vo.getContent());
        assertNotNull(vo.getCreatedAt());
    }

    @Test
    @Order(7)
    @DisplayName("detail: 不存在文章抛出 BusinessException")
    void detail_nonexistent_shouldThrow() {
        assertThrows(BusinessException.class,
                () -> articleService.detail(99999L, AUTHOR_ID));
    }

    @Test
    @Order(8)
    @DisplayName("delete: 逻辑删除后不出现在列表")
    void delete_shouldSoftDelete() {
        articleService.delete(articleId, AUTHOR_ID, "user");

        // 删除后 detail 抛异常
        assertThrows(BusinessException.class,
                () -> articleService.detail(articleId, AUTHOR_ID));
    }
}
