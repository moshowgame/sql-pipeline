package com.sqlpipeline.web.auth.mapper;

import com.sqlpipeline.web.auth.UserAccount;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 平台用户表访问（注解 SQL，与 auth 包同源，便于整体抽换）。 */
@Mapper
public interface PlatformUserMapper {

    @Select("SELECT username, password_hash AS passwordHash, display_name AS displayName, "
            + "enabled, created_at AS createdAt, updated_at AS updatedAt "
            + "FROM platform_user WHERE username = #{username}")
    UserAccount selectByUsername(@Param("username") String username);

    @Insert("INSERT INTO platform_user (username, password_hash, display_name, enabled, created_at, updated_at) "
            + "VALUES (#{username}, #{passwordHash}, #{displayName,jdbcType=VARCHAR}, #{enabled}, now(), now())")
    int insert(UserAccount user);

    @Update("UPDATE platform_user SET password_hash = #{passwordHash}, updated_at = now() WHERE username = #{username}")
    int updatePassword(@Param("username") String username, @Param("passwordHash") String passwordHash);
}
