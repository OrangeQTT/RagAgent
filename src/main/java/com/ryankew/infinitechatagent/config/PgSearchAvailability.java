package com.ryankew.infinitechatagent.config;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 启动时探测 PostgreSQL 是否安装了 ParadeDB 的 pg_search 扩展。
 * 标准 PostgreSQL 没有该扩展，此时 BM25 关键词检索应整体跳过，
 * 而不是每次查询都报 "function paradedb.match does not exist" 之类的错误刷屏。
 */
@Component
public class PgSearchAvailability {

    private final boolean available;

    public PgSearchAvailability(JdbcTemplate jdbcTemplate) {
        this.available = probe(jdbcTemplate);
    }

    public boolean isAvailable() {
        return available;
    }

    private static boolean probe(JdbcTemplate jdbcTemplate) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM pg_available_extensions WHERE name = 'pg_search'",
                    Integer.class);
            return count != null && count > 0;
        } catch (Exception e) {
            // 探测失败（库连不上等）按不可用处理，BM25 跳过，由向量检索兜底
            return false;
        }
    }
}
