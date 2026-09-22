package org.example.platform;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class EvidenceAssertionsTest {
    @Test void unknownBenchmarkAndChangedWeightsAreRejected(){assertThat(EvidenceAssertions.supported("使用BSDS500验证，权重2.5","DexiNed weight 2.5")).isFalse();assertThat(EvidenceAssertions.supported("权重3.5","weight 2.5")).isFalse();}
    @Test void knownModelNamesAndWeightsAreAccepted(){assertThat(EvidenceAssertions.supported("Edge2Me 的权重2.5","Edge2Me weight 2.5")).isTrue();}
    @Test void isoTimestampIsKeptAsAlphanumericSegmentsInsteadOfRejectingItsDateBoundary(){assertThat(EvidenceAssertions.supported("当前日期时间：2026-09-17T12:34:56+08:00","当前日期时间：2026-09-17T12:34:56+08:00")).isTrue();}
    @Test void yearOnlySummaryCanBeSupportedByFullMonthDates(){assertThat(EvidenceAssertions.supported("硕士就读时间为2024-2027年","2024.09 - 2027.06 上海大学 电子信息 硕士")).isTrue();}
    @Test void yearSummaryIsNotSupportedByAnEmbeddedIdentifier(){assertThat(EvidenceAssertions.supported("发生于2024年","BUILD2024 failed")).isFalse();}
    @Test void presentationListNumbersAreNotTreatedAsUnsupportedBusinessValues(){
        String source="Default severity is s3. Escalate to s2 for user impact, saturation alerts, or unresolved capacity issues.";
        assertThat(EvidenceAssertions.supported("默认是s3，以下任一情况升级s2：1）用户受影响；2）存在饱和告警；3）容量问题无法解决。",source)).isTrue();
        assertThat(EvidenceAssertions.supported("默认是s3，以下任一情况升级s2：(1) 用户受影响；(2) 存在饱和告警；（3）容量问题无法解决。",source)).isTrue();
        assertThat(EvidenceAssertions.supported("Steps: 1. inspect settings; 2) inspect policy", "inspect settings; inspect policy")).isTrue();
    }
    @Test void factualNumbersStillRequireSourceSupport(){
        assertThat(EvidenceAssertions.supported("重试3次后等待17秒", "Retry after 17 seconds.")).isFalse();
        assertThat(EvidenceAssertions.supported("版本2.5使用3个副本", "Version 2.5 uses two replicas.")).isFalse();
    }
    @Test void equivalentDurationUnitsAreAcceptedWithoutRelaxingTheValue(){
        assertThat(EvidenceAssertions.supported("down状态持续5分钟后触发告警", "Page when down for 5m or longer.")).isTrue();
        assertThat(EvidenceAssertions.supported("租约有效6小时", "The lease remains valid for 6 hours.")).isTrue();
        assertThat(EvidenceAssertions.supported("down状态持续6分钟后触发告警", "Page when down for 5m or longer.")).isFalse();
    }
}
