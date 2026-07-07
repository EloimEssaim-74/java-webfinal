-- ============================================
-- MySQL 主库复制配置
-- 在主库容器启动时执行（docker-entrypoint-initdb.d）
-- ============================================

-- 创建复制用户（从库连接用）
CREATE USER IF NOT EXISTS 'repl'@'%' IDENTIFIED BY 'repl123';
GRANT REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'repl'@'%';
FLUSH PRIVILEGES;

-- 注意: 二进制日志和 server-id 通过 Docker 启动参数或 my.cnf 配置
-- docker-compose 中通过 command 参数传递:
--   --server-id=1
--   --log-bin=mysql-bin
--   --binlog-format=ROW
--   --binlog-do-db=kb_platform
