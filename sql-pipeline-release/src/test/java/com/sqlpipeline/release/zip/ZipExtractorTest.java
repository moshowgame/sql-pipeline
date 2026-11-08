package com.sqlpipeline.release.zip;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZipExtractorTest {

    @TempDir
    Path tmp;

    private Path zipOf(String... entries) throws IOException {
        Path zip = tmp.resolve("test.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip), StandardCharsets.UTF_8)) {
            for (int i = 0; i < entries.length; i += 2) {
                zos.putNextEntry(new ZipEntry(entries[i]));
                zos.write(entries[i + 1].getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return zip;
    }

    @Test
    void extractsNestedEntries() throws IOException {
        Path zip = zipOf("1_init/a.sql", "SELECT 1;", "1_init/sub/b.sql", "SELECT 2;");
        Path target = tmp.resolve("out");

        ZipExtractor.extract(zip, target);

        assertThat(target.resolve("1_init/a.sql")).content(StandardCharsets.UTF_8).isEqualTo("SELECT 1;");
        assertThat(target.resolve("1_init/sub/b.sql")).content(StandardCharsets.UTF_8).isEqualTo("SELECT 2;");
    }

    @Test
    void rejectsZipSlipEntry() throws IOException {
        // zip entry 含 ../ 逃逸路径 → 整体拒绝
        Path zip = tmp.resolve("evil.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            zos.putNextEntry(new ZipEntry("../evil.sql"));
            zos.write("DROP TABLE x;".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        Path target = tmp.resolve("out");

        assertThatThrownBy(() -> ZipExtractor.extract(zip, target)).isInstanceOf(IOException.class);
        // 未在目标目录外落任何文件
        assertThat(target.resolve("../evil.sql")).doesNotExist();
    }

    @Test
    void rejectsAbsoluteEntryName() throws IOException {
        Path zip = tmp.resolve("evil2.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            zos.putNextEntry(new ZipEntry("/etc/passwd"));
            zos.write("x".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        Path target = tmp.resolve("out");

        assertThatThrownBy(() -> ZipExtractor.extract(zip, target)).isInstanceOf(IOException.class);
    }

    @Test
    void sha256HexIsStable() {
        assertThat(ZipExtractor.sha256Hex("abc")).isEqualTo(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void deleteRecursivelyRemovesTree() throws IOException {
        Path dir = tmp.resolve("tree");
        Files.createDirectories(dir.resolve("a/b"));
        Files.writeString(dir.resolve("a/b/f.txt"), "x");

        ZipExtractor.deleteRecursively(dir);

        assertThat(dir).doesNotExist();
    }

    @Test
    void deleteRecursivelyNullSafe() {
        ZipExtractor.deleteRecursively(null);
        assertThat(true).isTrue();
    }
}
