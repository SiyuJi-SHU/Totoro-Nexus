package org.example.service;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DocumentReadingServiceTest {
    private final DocumentReadingService reader = new DocumentReadingService();
    @Test void readsUnusualHeadingAndItsPrerequisitesWithoutAKeywordHeuristic() {
        String text = "# Storage\nOverview.\n## Identifying the damage\nRun as git user.\n### Inspect\n```sh\n# this is code\ngit fsck\n```\n## Recovery\nLater.\n";
        var source = new DocumentReadingService.Source("doc", "v1", "storage.md", text);
        var sections = reader.sections(source);
        assertThat(sections).extracting(DocumentReadingService.Section::title).containsExactly("Storage", "Identifying the damage", "Inspect", "Recovery");
        var window = reader.read(source, "v1", "s1", null, 20000);
        assertThat(window.content()).contains("Run as git user.", "git fsck").doesNotContain("## Recovery");
        assertThat(window.truncated()).isFalse();
        assertThat(text.substring(window.start(), window.end())).isEqualTo(window.content());
    }
    @Test void paginatesLargeSourcesWithoutLosingContentAndRejectsDifferentVersion() {
        String text = "a".repeat(19999) + "😀" + "b".repeat(6000);
        var source = new DocumentReadingService.Source("doc", "v1", "large.md", text);
        var first = reader.read(source, "v1", null, 0, 20000);
        var second = reader.read(source, "v1", null, first.nextOffset(), 20000);
        assertThat(first.truncated()).isTrue();
        assertThat(first.content() + second.content()).isEqualTo(text);
        assertThat(second.nextOffset()).isNull();
        assertThatThrownBy(() -> reader.read(source, "v2", null, 0, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reader.read(source, "v1", null, 20000, 100)).isInstanceOf(IllegalArgumentException.class);
    }
}
