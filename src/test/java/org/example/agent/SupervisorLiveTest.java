package org.example.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Explicit opt-in online check; these are curated symptoms, not production incidents. */
@SpringBootTest(classes=org.example.Main.class, webEnvironment=SpringBootTest.WebEnvironment.NONE,
        properties={"milvus.host=localhost"})
@EnabledIfEnvironmentVariable(named="RUN_PHASE20_LIVE",matches="1")
class SupervisorLiveTest {
    @Autowired SupervisorAgent supervisor;
    @Test void tenCuratedSymptomsUseRealModelClassification() throws Exception {
        String[][] cases={
            {"页面响应时间显著上升，Apdex 降到0.6，慢请求增加", "apdex_slo_violation_001"},
            {"应用出现大量HTTP 500，错误率超过SLO阈值", "error_slo_violation_001"},
            {"服务完全没有请求流量，任务吞吐为零", "traffic_absent_001"},
            {"黑盒探测HTTPS端点持续失败，外部探针成功率很低", "blackbox_probe_failures_001"},
            {"Cloud SQL数据库不可用，应用无法建立连接", "cloud_sql_database_down_001"},
            {"Gitaly重启后git仓库refs指针损坏，页面5xx", "gitaly_repository_corruption_001"},
            {"pubsubbeat写入Elasticsearch失败，mapper_parsing_exception", "elk_mapper_parsing_exception_001"},
            {"Kubernetes Deployment多数待启动容器CrashLoopBackOff", "kube_containers_waiting_in_error_001"},
            {"Patroni PostgreSQL频繁出现deadlock detected，事务互相等待", "patroni_deadlocks_detected_001"},
            {"PostgreSQL磁盘空间耗尽，No space left on device，WAL文件占用很大", "postgresql_disk_space_001"}
        };
        List<Map<String,Object>> rows=new ArrayList<>(); int hits=0;
        for(var c:cases){
            long start=System.nanoTime();var matches=supervisor.identifyScenarios(c[0]);
            boolean hit=!matches.isEmpty() && matches.get(0).getConfidence()>0 && c[1].equals(matches.get(0).getScenarioId());
            if(hit)hits++;
            rows.add(Map.of("symptoms",c[0],"expected",c[1],"matches",matches,"correctTop1",hit,"latencyMs",(System.nanoTime()-start)/1e6));
        }
        Path out=Path.of("../eval/phase20/results/supervisor-live.json");Files.createDirectories(out.getParent());
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(out.toFile(),Map.of("kind","10 curated symptoms / real API", "correct",hits,"total",cases.length,"accuracy",hits/(double)cases.length,"rows",rows));
        assertThat(hits/(double)cases.length).isGreaterThanOrEqualTo(.85);
    }
}
