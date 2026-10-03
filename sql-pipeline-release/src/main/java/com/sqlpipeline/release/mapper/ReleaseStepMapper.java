package com.sqlpipeline.release.mapper;

import com.sqlpipeline.release.entity.ReleaseStep;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ReleaseStepMapper {

    int batchInsert(@Param("steps") List<ReleaseStep> steps);

    ReleaseStep selectById(@Param("id") Long id);

    ReleaseStep selectByNo(@Param("planId") Long planId, @Param("stepNo") int stepNo);

    List<ReleaseStep> selectByPlan(@Param("planId") Long planId);

    List<ReleaseStep> selectRunning();

    /** 下一个待处理步骤：PENDING 或 WAITING_CONTINUE 中序号最小者。 */
    ReleaseStep selectNextActionable(@Param("planId") Long planId);

    ReleaseStep selectWaiting(@Param("planId") Long planId);

    int countByStatus(@Param("planId") Long planId, @Param("status") String status);

    int updateStatus(@Param("id") Long id, @Param("status") String status);

    int markRunning(@Param("id") Long id);

    int markSuccess(@Param("id") Long id, @Param("durationMs") Long durationMs);

    int markFail(@Param("id") Long id, @Param("durationMs") Long durationMs, @Param("errorMsg") String errorMsg);

    int markSkipped(@Param("id") Long id);

    int incrementRetry(@Param("id") Long id, @Param("remark") String remark);

    int markConfirmed(@Param("id") Long id, @Param("operator") String operator);

    int deleteByPlan(@Param("planId") Long planId);
}
