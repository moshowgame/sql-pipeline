package com.sqlpipeline.health.assertion;

import lombok.AllArgsConstructor;
import lombok.Data;

/** 断言求值结果。 */
@Data
@AllArgsConstructor
public class AssertResult {

    private boolean pass;
    private String message;
}
