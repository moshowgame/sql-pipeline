package com.sqlpipeline.release.scanner;

import java.util.List;

/** 预览视图：扫描结果与步骤配置合并后的展示。 */
public record ScannedStepView(int stepNo, String dirName, List<String> files,
                              String connKey, String afterMode, String executor) {
}
