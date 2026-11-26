package com.example.dynamicmeta.pages;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.example.dynamicmeta.SchemaToEntityProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageStoreTests {

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void pageStoragePropertiesAreDisabledAndLocalByDefault() {
        SchemaToEntityProperties properties = new SchemaToEntityProperties();

        assertThat(properties.isPagesEnabled()).isFalse();
        assertThat(properties.getPagesDir()).isEqualTo("./pages");
    }

    @Test
    void saveGetListAndDeleteRoundTrip() throws IOException {
        Path pages = tempDir.resolve("missing").resolve("pages");
        PageStore store = new PageStore(pages);
        Page savedB = store.save(page("page-b", "B"));
        Page savedA = store.save(page("page-a", "A"));

        assertThat(Files.isDirectory(pages)).isTrue();
        assertThat(store.get("page-b")).contains(savedB);
        assertThat(store.list()).containsExactly(savedA, savedB);
        assertThat(Files.readString(pages.resolve("page-a.json")))
                .startsWith("{\n").contains("\n  \"id\"");

        assertThat(store.delete("page-a")).isTrue();
        assertThat(store.get("page-a")).isEmpty();
        assertThat(store.list()).containsExactly(savedB);
    }

    @Test
    void laterSavesKeepCreatedAtUpdateUpdatedAtAndKeepFiveHistoryFiles()
            throws IOException {
        PageStore store = new PageStore(tempDir);
        Page first = store.save(page("dashboard", "Version 0"));
        Page latest = first;

        for (int i = 1; i <= 7; i++) {
            latest = store.save(page("dashboard", "Version " + i));
        }

        assertThat(latest.createdAt()).isEqualTo(first.createdAt());
        assertThat(latest.updatedAt()).isAfter(first.updatedAt());
        Path history = tempDir.resolve("history").resolve("dashboard");
        try (Stream<Path> files = Files.list(history)) {
            assertThat(files.filter(Files::isRegularFile)).hasSize(5);
        }
        assertThat(store.delete("dashboard")).isTrue();
        assertThat(Files.isDirectory(history)).isTrue();
    }

    @Test
    void rejectsBadIdsNonArrayDocumentsAndOversizeDocuments() {
        PageStore store = new PageStore(tempDir);

        assertThatThrownBy(() -> store.save(page("../escape", "Bad")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("[a-z0-9-]{1,64}");
        assertThatThrownBy(() -> new Page("bad-document", "Bad", null,
                mapper.createObjectNode(), null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("array");

        ArrayNode oversized = mapper.createArrayNode().add("x".repeat(512 * 1024));
        assertThatThrownBy(() -> store.save(new Page(
                "too-large", "Large", null, oversized, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("512 KB");
    }

    private Page page(String id, String name) {
        ArrayNode document = mapper.createArrayNode();
        document.add(mapper.createObjectNode().put("component", name));
        return new Page(id, name, "description", document,
                Instant.parse("2020-01-01T00:00:00Z"), null);
    }
}
