package com.sqlpipeline.web.auth;

import com.sqlpipeline.web.auth.mapper.PlatformUserMapper;
import lombok.RequiredArgsConstructor;

import java.util.Optional;

/** 默认用户来源：平台元数据库 platform_user 表。 */
@RequiredArgsConstructor
public class DbUserStore implements UserStore {

    private final PlatformUserMapper mapper;

    @Override
    public Optional<UserAccount> findByUsername(String username) {
        return Optional.ofNullable(mapper.selectByUsername(username));
    }

    @Override
    public void createUser(UserAccount user) {
        mapper.insert(user);
    }
}
