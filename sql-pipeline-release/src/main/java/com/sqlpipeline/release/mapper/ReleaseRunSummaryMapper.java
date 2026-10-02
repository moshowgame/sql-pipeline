package com.sqlpipeline.release.mapper;

import com.sqlpipeline.release.entity.ReleaseRunSummary;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ReleaseRunSummaryMapper {

    int insert(ReleaseRunSummary entity);

    List<ReleaseRunSummary> selectByPlan(@Param("planId") Long planId);
}
