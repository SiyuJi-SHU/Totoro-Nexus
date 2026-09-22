package org.example.platform;

import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.http.MediaType;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Event replay is durable. A page reconnect resumes; explicit cancellation stops the job. */
@RestController
public class RunStreamController {
    private final AgentRunStore runs;private final PlatformIdentity identity;
    private final ScheduledExecutorService streams=Executors.newScheduledThreadPool(2,r->{var t=new Thread(r,"run-events");t.setDaemon(true);return t;});
    public RunStreamController(AgentRunStore runs,PlatformIdentity identity){this.runs=runs;this.identity=identity;}
    @GetMapping(value="/api/platform/runs/{id}/stream",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(Authentication user,@PathVariable String id,@RequestParam(defaultValue="0") int after,
                             @RequestHeader(name="Last-Event-ID",required=false) String lastEventId) {
        runs.owned(id,identity.userId(user));int cursor=after;
        try{if(lastEventId!=null)cursor=Math.max(cursor,Integer.parseInt(lastEventId));}catch(NumberFormatException ignored){}
        SseEmitter emitter=new SseEmitter(330000L);AtomicInteger position=new AtomicInteger(cursor);AtomicBoolean closed=new AtomicBoolean();AtomicReference<ScheduledFuture<?>> task=new AtomicReference<>();
        Runnable close=()->{closed.set(true);var timer=task.get();if(timer!=null)timer.cancel(false);};
        emitter.onCompletion(close);emitter.onTimeout(close);emitter.onError(error->close.run());
        task.set(streams.scheduleWithFixedDelay(()->{
            if(closed.get()){close.run();return;}
            try {
                // Observe status before reading events. Otherwise a commit between those reads could skip the last batch.
                boolean terminal=!AgentRunStore.active(runs.get(id).status());
                var batch=runs.events(id,position.get());
                for(var event:batch){emitter.send(SseEmitter.event().id(Integer.toString(event.sequence())).name(event.type()).data(event.data(),MediaType.APPLICATION_JSON));position.set(event.sequence());}
                if(batch.size()<200&&terminal){emitter.send(SseEmitter.event().name("done").data("{}"));close.run();emitter.complete();}
            }catch(Exception error){close.run();emitter.complete();}
        },0,300,TimeUnit.MILLISECONDS));
        return emitter;
    }
    @PreDestroy public void close(){streams.shutdownNow();}
}
