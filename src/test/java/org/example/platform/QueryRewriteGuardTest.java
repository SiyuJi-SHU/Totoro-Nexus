package org.example.platform;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class QueryRewriteGuardTest {
    @Test void omittedErrorOrVersionKeepsOriginal(){assertThat(QueryRewriteGuard.preserve("Redis 7 EHOSTUNREACH，帮忙排查","Redis 网络错误")).isEqualTo("Redis 7 EHOSTUNREACH，帮忙排查");}
    @Test void negativeConditionsCannotBecomePositive(){assertThat(QueryRewriteGuard.preserve("没有启用缓存；响应超时","启用缓存时响应超时")).isEqualTo("没有启用缓存；响应超时");}
    @Test void usefulRewriteRetainsIdentifiers(){assertThat(QueryRewriteGuard.preserve("请查一下 DEXINED 用在什么阶段","DEXINED 使用阶段")).isEqualTo("DEXINED 使用阶段");}
}
