package com.sqlpipeline.health.mapper;

import com.sqlpipeline.health.entity.SqlDefinition;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SqlDefinitionMapper {

    int insert(SqlDefinition entity);

    int update(SqlDefinition entity);

    int updateEnabled(@Param("id") Long id, @Param("enabled") Integer enabled, @Param("updatedBy") String updatedBy);

    SqlDefinition selectById(@Param("id") Long id);

    List<SqlDefinition> selectAll();

    List<SqlDefinition> selectEnabled();
}
