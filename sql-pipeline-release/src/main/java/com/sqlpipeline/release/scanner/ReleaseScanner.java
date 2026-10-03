package com.sqlpipeline.release.scanner;

import com.sqlpipeline.common.error.ErrorCode;
import com.sqlpipeline.common.exception.BizException;
import com.sqlpipeline.release.config.ReleaseProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 发布目录扫描（RL-1 / RL-8 / G7）：
 * <ul>
 *   <li>一级子目录名必须为纯数字，非数字目录忽略</li>
 *   <li>按数字升序排序决定执行顺序，缺号自动忽略</li>
 *   <li>无 SQL 文件的空目录视为不存在</li>
 *   <li>目录内 SQL 文件按文件名字典序，不递归子目录</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class ReleaseScanner {

    private static final Pattern NUM_DIR = Pattern.compile("^\\d+$");

    private final ReleaseProperties properties;

    public List<ScannedStep> scan(Path planDir) {
        if (!Files.isDirectory(planDir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(planDir)) {
            List<ScannedStep> steps = stream
                    .filter(Files::isDirectory)
                    .filter(p -> NUM_DIR.matcher(p.getFileName().toString()).matches())
                    .sorted(Comparator.comparingInt(p -> Integer.parseInt(p.getFileName().toString())))
                    .map(this::toStep)
                    .filter(st -> !st.sqlFiles().isEmpty())
                    .toList();
            if (steps.size() > properties.getMaxSteps()) {
                throw BizException.i18n(ErrorCode.RL_SCAN_FAILED,
                        "error.rl.maxStepsExceeded", steps.size(), properties.getMaxSteps());
            }
            return steps;
        } catch (IOException e) {
            throw BizException.i18n(ErrorCode.RL_SCAN_FAILED, "error.rl.scanFailed", String.valueOf(planDir), e);
        }
    }

    private ScannedStep toStep(Path dir) {
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> sqlFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString()
                            .toLowerCase().endsWith(properties.getSqlSuffix().toLowerCase()))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
            return new ScannedStep(Integer.parseInt(dir.getFileName().toString()), dir, sqlFiles);
        } catch (IOException e) {
            throw BizException.i18n(ErrorCode.RL_SCAN_FAILED, "error.rl.scanFailed", String.valueOf(dir), e);
        }
    }
}
