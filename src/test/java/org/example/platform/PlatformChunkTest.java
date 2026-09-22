package org.example.platform;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformChunkTest {
    @Test void retrievalTextAddsDocumentIdentityWithoutChangingEvidenceContent() {
        var chunk = new PlatformChunk("id","doc","version","dataset",
                "gitlab__docs__alerts__ApdexSLOViolation.md",
                "ApdexSLOViolation > Overview > General Troubleshooting Steps",0,10,25,
                "Original evidence",null,null,null,"source");

        assertThat(chunk.retrievalText()).contains("Document: ApdexSLOViolation")
                .contains("Section: ApdexSLOViolation > Overview > General Troubleshooting Steps")
                .endsWith("Original evidence");
        assertThat(chunk.content()).isEqualTo("Original evidence");
        assertThat(chunk.start()).isEqualTo(10);
        assertThat(chunk.end()).isEqualTo(25);
    }
}
