package com.sqlpipeline.health.mapper;

import com.sqlpipeline.health.entity.SqlDefinitionHistory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SqlDefinitionHistoryMapper {

    int insert(SqlDefinitionHistory entity);

    /** 分页 + 可选按日期过滤（changed_at::date）。totalCount 经窗口函数回填。 */
    List<SqlDefinitionHistory> pageByDefId(@Param("sqlDefId") Long sqlDefId,
                                           @Param("day") String day,
                                           @Param("limit") int limit,
                                           @Param("offset") long offset);
}
