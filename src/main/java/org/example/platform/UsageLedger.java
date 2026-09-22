package org.example.platform;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.BiConsumer;

/** Provider-reported token counts only. Missing counts stay null, including failed calls. */
@Service
public class UsageLedger {
    public record Usage(String runId,String purpose,String kind,String model,Integer inputTokens,Integer outputTokens,Integer totalTokens,long elapsedMs,String outcome,Map<String,Object> timing) {}
    private record Context(String runId,Consumer<Usage> listener,BiConsumer<String,Object> events) {}
    private static final ThreadLocal<Context> CURRENT=new ThreadLocal<>();
    private final JdbcTemplate db;private final MeterRegistry meters;
    public UsageLedger(JdbcTemplate db,MeterRegistry meters){this.db=db;this.meters=meters;}
    public static AutoCloseable bind(String id,Consumer<Usage> listener) {
        return bind(id,listener,(type,data)->{});
    }
    public static AutoCloseable bind(String id,Consumer<Usage> listener,BiConsumer<String,Object> events) {
        var old=CURRENT.get();CURRENT.set(new Context(id,listener,events));
        return ()->{if(old==null)CURRENT.remove();else CURRENT.set(old);};
    }
    public static BiConsumer<String,Object> events(){var c=CURRENT.get();return c==null?(type,data)->{}:c.events();}
    public void record(String purpose,String kind,String model,Integer input,Integer output,Integer total,long start,String outcome) {
        record(purpose,kind,model,input,output,total,start,outcome,Map.of());
    }
    public void record(String purpose,String kind,String model,Integer input,Integer output,Integer total,long start,String outcome,Map<String,Object> timing) {
        var context=CURRENT.get();var usage=new Usage(context==null?null:context.runId(),purpose,kind,model,input,output,total,(System.nanoTime()-start)/1_000_000,outcome,Map.copyOf(timing));
        try {
            db.update("INSERT INTO model_calls(id,run_id,purpose,kind,model,input_tokens,output_tokens,total_tokens,elapsed_ms,outcome) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    UUID.randomUUID().toString(),usage.runId(),purpose,kind,model,input,output,total,usage.elapsedMs(),outcome);
        }catch(RuntimeException error){meters.counter("platform.telemetry.errors").increment();}
        if(context!=null&&context.listener()!=null)context.listener().accept(usage);
    }
    public List<Map<String,Object>> forRun(String id){return db.queryForList("SELECT * FROM model_calls WHERE run_id=? ORDER BY created_at",id);}
    public Object summary(){return db.queryForList("SELECT kind,model,purpose,outcome,COUNT(*) AS calls,SUM(input_tokens) AS input_tokens,SUM(output_tokens) AS output_tokens,SUM(total_tokens) AS total_tokens,SUM(elapsed_ms) AS elapsed_ms,SUM(CASE WHEN total_tokens IS NULL THEN 1 ELSE 0 END) AS unknown_usage_calls FROM model_calls GROUP BY kind,model,purpose,outcome ORDER BY kind,purpose");}
}
