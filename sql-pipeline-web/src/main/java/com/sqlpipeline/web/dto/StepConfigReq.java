package com.sqlpipeline.web.dto;

import com.sqlpipeline.release.orchestrator.StepConfig;
import jakarta.validation.constraints.NotBlank;

public record StepConfigReq(Integer stepNo, String connKey, String afterMode, String executor) {

    public StepConfig toConfig() {
        return new StepConfig(stepNo, connKey, afterMode, executor);
    }
}
