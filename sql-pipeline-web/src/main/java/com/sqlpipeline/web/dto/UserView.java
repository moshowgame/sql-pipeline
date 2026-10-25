package com.sqlpipeline.web.dto;

import com.sqlpipeline.web.auth.UserAccount;

/** 当前登录用户视图（绝不含密码字段）。 */
public record UserView(String username, String displayName) {

    public static UserView of(UserAccount user) {
        return new UserView(user.getUsername(), user.getDisplayName());
    }
}
