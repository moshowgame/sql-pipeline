package com.sqlpipeline.datasource.mapper;

import com.sqlpipeline.datasource.entity.DbConnection;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface DbConnectionMapper {

    int insert(DbConnection entity);

    int update(DbConnection entity);

    int updateEnabled(@Param("id") Long id, @Param("enabled") Integer enabled, @Param("updatedBy") String updatedBy);

    DbConnection selectById(@Param("id") Long id);

    DbConnection selectByKey(@Param("connKey") String connKey);

    List<DbConnection> selectAll();

    List<DbConnection> selectEnabled();
}
