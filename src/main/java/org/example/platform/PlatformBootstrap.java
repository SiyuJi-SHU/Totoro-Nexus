package org.example.platform;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Order(0)
public class PlatformBootstrap implements ApplicationRunner {
    private final PlatformCatalog catalog;
    private final JdbcTemplate db;
    public PlatformBootstrap(PlatformCatalog catalog,JdbcTemplate db) { this.catalog=catalog;this.db=db; }
    @Override public void run(ApplicationArguments args) {
        catalog.initializeDefaults();
        db.update("UPDATE documents SET status='INDEX_FAILED',error_message='服务重启中断索引准备，旧版本仍可用' WHERE status='INDEXING'");
        db.update("UPDATE document_versions SET status='FAILED',error_message='索引准备中断' WHERE status='PREPARING'");
        db.update("UPDATE agent_runs SET status='interrupted',error_message='服务重启，执行已中断，可重新运行',finished_at=CURRENT_TIMESTAMP WHERE status IN ('queued','running','reviewing')");
        db.update("UPDATE evaluation_jobs SET status='interrupted',finished_at=CURRENT_TIMESTAMP WHERE status IN ('queued','running')");
    }
}
