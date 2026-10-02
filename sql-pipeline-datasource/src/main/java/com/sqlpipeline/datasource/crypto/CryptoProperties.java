package com.sqlpipeline.datasource.crypto;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sql-pipeline.crypto")
@Data
public class CryptoProperties {

    /** AES-256 密钥，Base64 编码的 32 字节；生产环境必须通过环境变量/KMS 注入。 */
    private String key;
}
