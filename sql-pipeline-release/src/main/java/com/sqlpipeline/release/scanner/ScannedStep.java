package com.sqlpipeline.release.scanner;

import java.nio.file.Path;
import java.util.List;

/** 扫描出的一个发布步骤：数字目录 + 其下 SQL 文件（已按文件名字典序排序）。 */
public record ScannedStep(int no, Path dir, List<Path> sqlFiles) {
}
