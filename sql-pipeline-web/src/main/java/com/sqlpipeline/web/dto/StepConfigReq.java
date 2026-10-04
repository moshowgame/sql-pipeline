package com.sqlpipeline.web.dto;

import com.sqlpipeline.release.orchestrator.StepConfig;

public record StepConfigReq(String dirName, Integer stepNo, String connKey, String afterMode, String executor) {

    public StepConfig toConfig() {
        return new StepConfig(dirName, stepNo, connKey, afterMode, executor);
    }
}
