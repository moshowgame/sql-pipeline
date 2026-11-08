package com.sqlpipeline.release.mapper;

import com.sqlpipeline.release.entity.ReleaseSqlLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface ReleaseSqlLogMapper {

    int insert(ReleaseSqlLog entity);

    List<ReleaseSqlLog> selectByPlan(@Param("planId") Long planId,
                                     @Param("stepNo") Integer stepNo,
                                     @Param("runSeq") Integer runSeq,
                                     @Param("status") String status);

    /** 每个步骤的语句总数/失败数（最新一轮），供失败率展示。 */
    List<Map<String, Object>> selectStepStats(@Param("planId") Long planId);
}
