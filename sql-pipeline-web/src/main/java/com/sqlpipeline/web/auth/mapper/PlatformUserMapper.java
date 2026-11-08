package com.sqlpipeline.web.auth.mapper;

import com.sqlpipeline.web.auth.UserAccount;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** 平台用户表访问（注解 SQL，与 auth 包同源，便于整体抽换）。 */
@Mapper
public interface PlatformUserMapper {

    @Select("SELECT username, password_hash AS passwordHash, display_name AS displayName, "
            + "role, expires_at AS expiresAt, cr_in_no AS crInNo, staff_id AS staffId, "
            + "enabled, created_at AS createdAt, updated_at AS updatedAt "
            + "FROM platform_user WHERE username = #{username}")
    UserAccount selectByUsername(@Param("username") String username);

    @Insert("INSERT INTO platform_user (username, password_hash, display_name, role, expires_at, cr_in_no, staff_id, enabled, created_at, updated_at) "
            + "VALUES (#{username}, #{passwordHash}, #{displayName,jdbcType=VARCHAR}, #{role,jdbcType=VARCHAR}, "
            + "#{expiresAt,jdbcType=TIMESTAMP}, #{crInNo,jdbcType=VARCHAR}, #{staffId,jdbcType=VARCHAR}, #{enabled}, now(), now())")
    int insert(UserAccount user);

    /** 未过期临时账号列表（self-TPAM 页面展示）。 */
    @Select("SELECT username, display_name AS displayName, role, expires_at AS expiresAt, "
            + "cr_in_no AS crInNo, staff_id AS staffId, enabled, created_at AS createdAt "
            + "FROM platform_user WHERE expires_at IS NOT NULL ORDER BY created_at DESC")
    List<UserAccount> selectTempUsers();

    /** 到点回收：直接删除过期临时账号，释放 staffid 以便重新申请。 */
    @Delete("DELETE FROM platform_user WHERE expires_at IS NOT NULL AND expires_at < now()")
    int deleteExpired();

    /** 提前吊销：直接删除（审计依赖应用日志中的 CR/IN 记录）。 */
    @Delete("DELETE FROM platform_user WHERE username = #{username} AND expires_at IS NOT NULL")
    int deleteTempUser(@Param("username") String username);

    @Update("UPDATE platform_user SET password_hash = #{passwordHash}, updated_at = now() WHERE username = #{username}")
    int updatePassword(@Param("username") String username, @Param("passwordHash") String passwordHash);
}
