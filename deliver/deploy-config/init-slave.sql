-- ============================================
-- MySQL 从库初始化脚本
-- 在从库容器首次启动后执行
-- ============================================
--
-- 前置条件:
--   1. init.sql 已在主库和从库执行（创建 kb_platform 数据库和表）
--   2. 主库已创建 repl 复制用户:
--      CREATE USER IF NOT EXISTS 'repl'@'%' IDENTIFIED WITH mysql_native_password BY 'repl123';
--      GRANT REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'repl'@'%';
--
-- 使用说明:
--   本脚本的 MASTER_LOG_FILE 和 MASTER_LOG_POS 需要在每次主库重启后更新。
--   可以通过 docker exec kb-mysql mysql -uroot -proot123 -e "SHOW MASTER STATUS"
--   获取最新的 File 和 Position 值。
-- ============================================

-- 等待主库就绪（避免容器启动顺序问题）
-- 实际生产环境建议使用 MASTER_AUTO_POSITION=1 (GTID) 替代手动指定位置

STOP SLAVE;

CHANGE MASTER TO
    MASTER_HOST='mysql',
    MASTER_PORT=3306,
    MASTER_USER='repl',
    MASTER_PASSWORD='repl123',
    -- ⚠️ 以下两行在每次主库重启后需要更新
    -- 运行: docker exec kb-mysql mysql -uroot -proot123 -e "SHOW MASTER STATUS"
    MASTER_LOG_FILE='mysql-bin.000007',
    MASTER_LOG_POS=861,
    GET_MASTER_PUBLIC_KEY=1;

START SLAVE;

-- 验证
SHOW SLAVE STATUS\G;
