package com.sqlpipeline.notify.mapper;

import com.sqlpipeline.notify.entity.NotifyChannel;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface NotifyChannelMapper {

    int insert(NotifyChannel entity);

    int update(NotifyChannel entity);

    int deleteById(@Param("id") Long id);

    NotifyChannel selectById(@Param("id") Long id);

    List<NotifyChannel> selectAll();

    List<NotifyChannel> selectEnabled();
}
