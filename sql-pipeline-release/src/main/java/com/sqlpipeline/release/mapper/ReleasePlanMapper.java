package com.sqlpipeline.release.mapper;

import com.sqlpipeline.release.entity.ReleasePlan;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ReleasePlanMapper {

    int insert(ReleasePlan entity);

    int updateForStart(@Param("id") Long id,
                       @Param("crNumber") String crNumber,
                       @Param("remark") String remark,
                       @Param("operator") String operator,
                       @Param("status") String status);

    int updateStatus(@Param("id") Long id, @Param("status") String status);

    int markFinished(@Param("id") Long id, @Param("status") String status);

    int resetForRerun(@Param("id") Long id);

    ReleasePlan selectById(@Param("id") Long id);

    List<ReleasePlan> selectAll();
}
