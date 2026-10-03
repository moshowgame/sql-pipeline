package com.sqlpipeline.release.mapper;

import com.sqlpipeline.release.entity.ReleaseSqlLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ReleaseSqlLogMapper {

    int insert(ReleaseSqlLog entity);

    List<ReleaseSqlLog> selectByPlan(@Param("planId") Long planId,
                                     @Param("stepNo") Integer stepNo,
                                     @Param("runSeq") Integer runSeq);
}
