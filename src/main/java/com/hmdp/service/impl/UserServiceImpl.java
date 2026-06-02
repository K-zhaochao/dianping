package com.hmdp.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    /**
     * 发送短信验证码并保存验证码
     * @param phone
     * @param session
     * @return
     */
    public Result sendCode(String phone, HttpSession session) {
        // 1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)){
            // 2.如果不符合，返回错误信息
            return Result.fail("手机号格式错误！");
        }

        // 3.符合，生成验证码
        String code = RandomUtil.randomNumbers(6);

        // 4。保存验证码到session
        LoginFormDTO loginForm = LoginFormDTO.builder()
                                .phone(phone)
                                .code(code)
                                .build();
        session.setAttribute("code", loginForm);

        // 5.发送验证码
        log.debug("发送短信验证码成功, 验证码: {}", code);

        // 返回ok
        return Result.ok();
    }

    /**
     * 实现登录功能
     * @param loginForm
     * @param session
     * @return
     */
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        LoginFormDTO login = (LoginFormDTO) session.getAttribute("code");
        if (login == null) {
            return Result.fail("验证码已过期或未发送，请重新获取！");
        }
        // 1.校验手机号
        if (RegexUtils.isPhoneInvalid(loginForm.getPhone()) || !loginForm.getPhone().equals(login.getPhone())) {
            return Result.fail("手机号码不合法，请重新输入！");
        }

        // 2.校验验证码
        if (loginForm.getCode() == null || !loginForm.getCode().equals(login.getCode())) {
            // 3.不一致，报错
            return Result.fail("验证码输入错误！");
        }

        // 4.一致，根据手机号查询用户
        User user = query().eq("phone", loginForm.getPhone()).one();

        // 5.判断用户是否存在
        if (user == null) {
            // 6.不存在，创建新用户并保存
            user = createUserWithPhone(loginForm.getPhone());
        }

        // 7.保存用户信息到session中
        session.setAttribute("user", user);

        return Result.ok();
    }

    private User createUserWithPhone(String phone) {
        // 1.创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomNumbers(10));

        // 2.保存用户
        save(user);

        return user;
    }
}
