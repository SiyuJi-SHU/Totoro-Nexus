package org.example.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class ModelDeadlineTest {
    @ParameterizedTest @ValueSource(ints={30,45,300})
    void aBlockedProviderIsCancelledAtTheAbsoluteDeadline(int callLimit)throws Exception {
        var interrupted=new CountDownLatch(1);
        // Class loading / pool startup is not a blocked provider call.
        assertThat(ModelDeadline.call(()->"ready")).isEqualTo("ready");
        try(var deadline=ModelDeadline.bind(System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(100))){
            assertThatThrownBy(()->ModelDeadline.call(()->{try{new CountDownLatch(1).await();return "never";}catch(InterruptedException e){interrupted.countDown();throw e;}},callLimit)).isInstanceOf(ModelDeadline.LimitException.class);
        }
        assertThat(interrupted.await(1,TimeUnit.SECONDS)).isTrue();assertThat(ModelDeadline.current()).isEqualTo(Long.MAX_VALUE);
    }
    @Test void nestedDeadlineCannotExtendItsParent()throws Exception {
        long parent=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);
        try(var outer=ModelDeadline.bind(parent);var inner=ModelDeadline.bind(Long.MAX_VALUE)){assertThat(ModelDeadline.current()).isEqualTo(parent);}
        assertThat(ModelDeadline.current()).isEqualTo(Long.MAX_VALUE);
    }
}
