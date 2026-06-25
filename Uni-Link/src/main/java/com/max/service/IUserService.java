package com.max.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.max.dto.LoginFormDTO;
import com.max.dto.Result;
import com.max.entity.User;

import javax.servlet.http.HttpSession;

/**
 * <p>
 *  服务类
 * </p>
 *
 *
 */
public interface IUserService extends IService<User> {

    Result sendCode(String phone, HttpSession session);

    Result login(LoginFormDTO loginForm, HttpSession session);

    Result sign();

    Result signCount();
}
