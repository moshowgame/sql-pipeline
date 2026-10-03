package com.sqlpipeline.boot;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication(scanBasePackages = "com.sqlpipeline")
@MapperScan({
        "com.sqlpipeline.datasource.mapper",
        "com.sqlpipeline.health.mapper",
        "com.sqlpipeline.release.mapper",
        "com.sqlpipeline.notify.mapper"
})
@ConfigurationPropertiesScan("com.sqlpipeline")
public class SqlPipelineApplication {

    public static void main(String[] args) {
        SpringApplication.run(SqlPipelineApplication.class, args);
    }
}
