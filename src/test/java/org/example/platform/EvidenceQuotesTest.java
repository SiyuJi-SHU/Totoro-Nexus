package org.example.platform;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class EvidenceQuotesTest {
    @Test void presentationDifferencesResolveToActualSourceWithoutChangingWordsNumbersOrNegation() {
        String original="Use **DexiNed** for boundary\nresponse fusion; `T_m=1.2`. Do not use a probability as a logit.";
        var resolved=EvidenceQuotes.resolve(original,"Use DexiNed for boundary response fusion; T_m=1.2.");
        assertThat(resolved).isPresent();assertThat(original).contains(resolved.orElseThrow());
        assertThat(EvidenceQuotes.resolve(original,"T_m=2.5")).isEmpty();
        assertThat(EvidenceQuotes.resolve(original,"Do use a probability as a logit.")).isEmpty();
        assertThat(EvidenceQuotes.resolve(original,"DexiNed was trained here")).isEmpty();
    }
}
