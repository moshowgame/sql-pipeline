package com.sqlpipeline.health.mapper;

import com.sqlpipeline.health.entity.HealthCheckRun;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface HealthCheckRunMapper {

    int insert(HealthCheckRun entity);

    int updateResult(HealthCheckRun entity);

    /** 启动恢复：残留的 RUNNING 记录置 ERROR（应用重启中断），返回受影响行数。 */
    int markInterruptedRunning(@Param("errorMsg") String errorMsg);

    List<HealthCheckRun> pageByDefId(@Param("sqlDefId") Long sqlDefId,
                                     @Param("day") String day,
                                     @Param("limit") int limit,
                                     @Param("offset") long offset);
}
