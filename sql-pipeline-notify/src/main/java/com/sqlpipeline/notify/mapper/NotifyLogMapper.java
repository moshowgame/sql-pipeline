package com.sqlpipeline.notify.mapper;

import com.sqlpipeline.notify.entity.NotifyLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface NotifyLogMapper {

    int insert(NotifyLog entity);

    List<NotifyLog> selectRecent(@Param("limit") int limit);
}
