package com.sqlpipeline.release.enums;

/** 步骤执行完成后的过渡方式。 */
public enum AfterMode {
    /** 自动推进下一步。 */
    CONTINUE,
    /** 等待人工点击 continue。 */
    WAIT
}
