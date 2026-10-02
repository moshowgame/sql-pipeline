package com.sqlpipeline.health.mapper;

import com.sqlpipeline.health.entity.SqlDefinitionHistory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SqlDefinitionHistoryMapper {

    int insert(SqlDefinitionHistory entity);

    List<SqlDefinitionHistory> selectByDefId(@Param("sqlDefId") Long sqlDefId);
}
