package com.sqlpipeline.health.mapper;

import com.sqlpipeline.health.entity.HealthCheckRun;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface HealthCheckRunMapper {

    int insert(HealthCheckRun entity);

    int updateResult(HealthCheckRun entity);

    List<HealthCheckRun> pageByDefId(@Param("sqlDefId") Long sqlDefId,
                                     @Param("limit") int limit,
                                     @Param("offset") long offset);
}
