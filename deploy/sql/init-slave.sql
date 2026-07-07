-- ============================================
-- MySQL 从库初始化
-- 在从库容器首次启动后手动执行（或通过 healthcheck 后自动执行）
-- ============================================

-- 配置复制源
CHANGE MASTER TO
    MASTER_HOST='mysql',
    MASTER_PORT=3306,
    MASTER_USER='repl',
    MASTER_PASSWORD='repl123',
    MASTER_LOG_FILE='mysql-bin.000001',
    MASTER_LOG_POS=0,
    GET_MASTER_PUBLIC_KEY=1;

-- 启动复制
START SLAVE;

-- 验证复制状态
SHOW SLAVE STATUS\G;
