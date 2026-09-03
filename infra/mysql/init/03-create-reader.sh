#!/bin/sh
set -eu

# SQLBot 第一版只需读取两个语义化分析视图，避免直接暴露原始订单与用户维度数据。
mysql -uroot -p"${MYSQL_ROOT_PASSWORD}" "${MYSQL_DATABASE}" <<SQL
CREATE USER IF NOT EXISTS '${MYSQL_READER_USER}'@'%' IDENTIFIED BY '${MYSQL_READER_PASSWORD}';
GRANT SELECT ON \`${MYSQL_DATABASE}\`.\`v_daily_group_operation\` TO '${MYSQL_READER_USER}'@'%';
GRANT SELECT ON \`${MYSQL_DATABASE}\`.\`v_daily_fault_analysis\` TO '${MYSQL_READER_USER}'@'%';
FLUSH PRIVILEGES;
SQL
