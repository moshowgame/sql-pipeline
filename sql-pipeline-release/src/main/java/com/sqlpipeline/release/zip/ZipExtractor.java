package com.sqlpipeline.release.zip;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * ZIP 解压工具（§12.4 路径穿越防护）：
 * - zip-slip 防护：entry 名规范化后必须落在目标目录内，否则整体拒绝解压
 * - 解压到临时目录，成功后由调用方原子移动到缓存目录，避免半解压状态被复用
 */
public final class ZipExtractor {

    private ZipExtractor() {
    }

    /** 解压 zip 到 targetDir（目录不存在则创建）；entry 路径逃逸目标目录时抛 IOException。 */
    public static void extract(Path zipFile, Path targetDir) throws IOException {
        Path target = targetDir.toAbsolutePath().normalize();
        Files.createDirectories(target);
        try (InputStream in = Files.newInputStream(zipFile);
             ZipInputStream zis = new ZipInputStream(in, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                // 防穿越：拒绝绝对路径与 ../ 逃逸（§12.4），不做静默改写
                String name = entry.getName().replace('\\', '/');
                if (name.startsWith("/") || name.toUpperCase().matches("^[A-Z]:.*")) {
                    throw new IOException("absolute path in zip entry rejected: " + entry.getName());
                }
                Path out = target.resolve(name).normalize();
                if (name.contains("..") || !out.startsWith(target)) {
                    throw new IOException("zip entry escapes target dir (zip-slip): " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                    continue;
                }
                Files.createDirectories(out.getParent());
                try (OutputStream os = Files.newOutputStream(out)) {
                    zis.transferTo(os);
                }
                zis.closeEntry();
            }
        }
    }

    /** 递归删除目录（失败静默，仅用于缓存清理）。 */
    public static void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                    // 缓存清理失败不影响主流程
                }
            });
        } catch (IOException ignored) {
            // ignore
        }
    }

    /** SHA-256 十六进制摘要（用于缓存 key：zip 路径 + mtime）。 */
    public static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest(input.getBytes(StandardCharsets.UTF_8))) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
