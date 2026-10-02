package com.sqlpipeline.release.scanner;

import com.sqlpipeline.release.config.ReleaseProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReleaseScannerTest {

    @TempDir
    Path tempDir;

    private ReleaseScanner scanner;

    @BeforeEach
    void setUp() {
        scanner = new ReleaseScanner(new ReleaseProperties());
    }

    @Test
    void numericOrderAndSkipNonNumericAndEmptyDirs() throws IOException {
        Path plan = Files.createDirectories(tempDir.resolve("release_20261003"));
        write(plan.resolve("1"), "a.sql");
        write(plan.resolve("1"), "b.sql");
        write(plan.resolve("2"), "c.sql");
        write(plan.resolve("10"), "d.sql");
        write(plan.resolve("notes"), "readme.md");   // 非数字目录
        Files.createDirectories(plan.resolve("3"));  // 空目录
        Files.createDirectories(plan.resolve("5/sub")); // 仅有子目录，无 .sql

        List<ScannedStep> steps = scanner.scan(plan);

        assertThat(steps).extracting(ScannedStep::no).containsExactly(1, 2, 10);
        assertThat(steps.get(0).sqlFiles())
                .extracting(p -> p.getFileName().toString())
                .containsExactly("a.sql", "b.sql");
    }

    @Test
    void missingStepNumbersAreIgnored() throws IOException {
        Path plan = Files.createDirectories(tempDir.resolve("plan"));
        write(plan.resolve("2"), "a.sql");
        write(plan.resolve("5"), "b.sql");
        List<ScannedStep> steps = scanner.scan(plan);
        assertThat(steps).extracting(ScannedStep::no).containsExactly(2, 5);
    }

    @Test
    void caseInsensitiveSqlSuffix() throws IOException {
        Path plan = Files.createDirectories(tempDir.resolve("plan"));
        write(plan.resolve("1"), "A.SQL");
        List<ScannedStep> steps = scanner.scan(plan);
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).sqlFiles()).hasSize(1);
    }

    @Test
    void nonRecursive() throws IOException {
        Path plan = Files.createDirectories(tempDir.resolve("plan"));
        write(plan.resolve("1"), "a.sql");
        write(plan.resolve("1/nested"), "ignored.sql"); // 不递归
        List<ScannedStep> steps = scanner.scan(plan);
        assertThat(steps.get(0).sqlFiles()).hasSize(1);
    }

    @Test
    void missingPlanDirReturnsEmpty() {
        assertThat(scanner.scan(tempDir.resolve("not_exists"))).isEmpty();
    }

    private void write(Path dir, String fileName) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(fileName), "SELECT 1;");
    }
}
