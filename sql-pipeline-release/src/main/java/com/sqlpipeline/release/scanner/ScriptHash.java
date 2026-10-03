package com.sqlpipeline.release.scanner;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** 脚本目录内容哈希：用于 start 后检测脚本变更（ADR：发布脚本不可变约定的技术兜底）。 */
public final class ScriptHash {

    private ScriptHash() {
    }

    /** 对目录内 SQL 文件（按文件名排序）计算 SHA-256；目录不存在返回 null。 */
    public static String of(Path dir, String sqlSuffix) {
        if (dir == null || !Files.isDirectory(dir)) {
            return null;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(sqlSuffix.toLowerCase()))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (Path file : files) {
                md.update(file.getFileName().toString().getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
                md.update(Files.readAllBytes(file));
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
