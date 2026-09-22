package org.example.eval;

import org.example.eval.rag.RagEvalRun;
import org.example.eval.rag.RagEvalRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
@ConditionalOnProperty(prefix = "eval.rag", name = "enabled", havingValue = "true")
public class EvalApplicationRunner implements ApplicationRunner {

    private final RagEvalRunner ragEvalRunner;
    private final EvalReportService evalReportService;
    private final ConfigurableApplicationContext applicationContext;
    private final String reportPath;
    private final boolean exitAfterRun;

    public EvalApplicationRunner(
            RagEvalRunner ragEvalRunner,
            EvalReportService evalReportService,
            ConfigurableApplicationContext applicationContext,
            @Value("${eval.rag.report-path:reports/eval/rag-eval-report.md}") String reportPath,
            @Value("${eval.exit-after-run:false}") boolean exitAfterRun) {
        this.ragEvalRunner = ragEvalRunner;
        this.evalReportService = evalReportService;
        this.applicationContext = applicationContext;
        this.reportPath = reportPath;
        this.exitAfterRun = exitAfterRun;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        RagEvalRun run = ragEvalRunner.run();
        Path written = evalReportService.writeRagReport(run.getSummary(), run.getResults(), reportPath);
        System.out.println("RAG eval report written to: " + written.toAbsolutePath());

        if (exitAfterRun) {
            int exitCode = SpringApplication.exit(applicationContext, () -> 0);
            System.exit(exitCode);
        }
    }
}
