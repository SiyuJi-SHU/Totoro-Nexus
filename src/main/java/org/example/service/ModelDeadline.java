package org.example.service;

import java.util.concurrent.*;

/** Absolute request deadline, also inherited explicitly by workflow workers. */
public final class ModelDeadline {
    private static final ThreadLocal<Long> DEADLINE=new ThreadLocal<>();
    private static final ThreadLocal<Long> REQUEST_DEADLINE=new ThreadLocal<>();
    private static final ExecutorService CALLS=new ThreadPoolExecutor(12,12,0,TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(24),r->{var t=new Thread(r,"bounded-model-call");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private ModelDeadline() {}
    public static long current(){return DEADLINE.get()==null?Long.MAX_VALUE:DEADLINE.get();}
    public static long requestDeadline(){return REQUEST_DEADLINE.get()==null?current():REQUEST_DEADLINE.get();}
    public static AutoCloseable bind(long deadline){Long old=DEADLINE.get();if(old==null)REQUEST_DEADLINE.set(deadline);DEADLINE.set(Math.min(current(),deadline));return ()->{if(old==null){DEADLINE.remove();REQUEST_DEADLINE.remove();}else DEADLINE.set(old);};}
    public static long remainingNanos(){return Math.max(0,current()-System.nanoTime());}
    public static <T> T call(Callable<T> action){return call(action,30);}
    public static <T> T call(Callable<T> action,int maxSeconds){
        ChatModelFactory.checkCancelled();
        long wait=Math.min(TimeUnit.SECONDS.toNanos(maxSeconds),remainingNanos());
        if(wait<=0)throw new LimitException();
        Future<T> future=CALLS.submit(action);
        try{return future.get(wait,TimeUnit.NANOSECONDS);}
        catch(TimeoutException e){future.cancel(true);throw new LimitException();}
        catch(InterruptedException e){future.cancel(true);Thread.currentThread().interrupt();throw new CancellationException();}
        catch(ExecutionException e){if(e.getCause() instanceof RuntimeException r)throw r;throw new IllegalStateException(e.getCause());}
    }
    public static final class LimitException extends RuntimeException {public LimitException(){super("模型调用达到时间上限");}}
}
