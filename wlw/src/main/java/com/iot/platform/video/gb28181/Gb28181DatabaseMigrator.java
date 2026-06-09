package com.iot.platform.video.gb28181;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * H2 已有库文件时 Hibernate ddl-auto=update 可能不补列，启动前手工 ALTER。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class Gb28181DatabaseMigrator implements org.springframework.beans.factory.InitializingBean {

    private static final String TABLE = "iot_gb28181_platform_config";
    private static final String[][] COLUMNS = {
            {"require_sip_register", "BOOLEAN DEFAULT FALSE NOT NULL"},
            {"media_transport", "VARCHAR(16) DEFAULT 'tcp_passive' NOT NULL"},
    };

    private final DataSource dataSource;

    @Override
    public void afterPropertiesSet() throws Exception {
        for (String[] col : COLUMNS) {
            addColumnIfMissing(col[0], col[1]);
        }
    }

    private void addColumnIfMissing(String column, String ddlType) throws SQLException {
        if (columnExists(TABLE, column)) {
            return;
        }
        String sql = "ALTER TABLE " + TABLE + " ADD COLUMN " + column + " " + ddlType;
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.execute(sql);
            log.info("GB28181 数据库迁移: 已添加列 {}.{}", TABLE, column);
        } catch (SQLException e) {
            if (columnExists(TABLE, column)) {
                return;
            }
            throw new IllegalStateException("无法添加列 " + TABLE + "." + column + ": " + e.getMessage(), e);
        }
    }

    private boolean columnExists(String table, String column) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            String catalog = conn.getCatalog();
            if (hasColumn(meta, catalog, table, column)) {
                return true;
            }
            return hasColumn(meta, catalog, table.toUpperCase(), column.toUpperCase());
        }
    }

    private static boolean hasColumn(DatabaseMetaData meta, String catalog, String table, String column)
            throws SQLException {
        try (ResultSet rs = meta.getColumns(catalog, null, table, column)) {
            return rs.next();
        }
    }
}
