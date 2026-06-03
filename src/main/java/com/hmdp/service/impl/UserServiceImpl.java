package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

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

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 发送短信验证码并保存验证码
     * @param phone
     * @param session
     * @return
     */
    public Result sendCode(String phone, HttpSession session) throws JsonProcessingException {
        // 1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)){
            // 2.如果不符合，返回错误信息
            return Result.fail("手机号格式错误！");
        }

        // 3.符合，生成验证码
        String code = RandomUtil.randomNumbers(6);

        // 4。保存验证码到redis
        LoginFormDTO loginForm = LoginFormDTO.builder()
                                .phone(phone)
                                .code(code)
                                .build();
        // session.setAttribute("code", loginForm);
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, objectMapper.writeValueAsString(loginForm), LOGIN_CODE_TTL, TimeUnit.MINUTES);

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
    public Result login(LoginFormDTO loginForm, HttpSession session) throws JsonProcessingException {
        // 1.校验手机号
        if (RegexUtils.isPhoneInvalid(loginForm.getPhone())) {
            return Result.fail("手机号码不合法，请重新输入！");
        }

        // 从Redis中获取用户信息
        String codeString = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + loginForm.getPhone());
        LoginFormDTO login = objectMapper.readValue(codeString, LoginFormDTO.class);
        //LoginFormDTO login = (LoginFormDTO) session.getAttribute("code");
        if (login == null) {
            return Result.fail("验证码已过期或未发送，请重新获取！");
        }
        if (!loginForm.getPhone().equals(login.getPhone())) {
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

        // 7.保存用户信息到redis中
        // 7.1随机生成token作为令牌
        String token = UUID.randomUUID().toString();
        String key = LOGIN_USER_KEY + token;
        // 7.2将User对象转成HashMap存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> map = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        // session.setAttribute("user", userDTO);
        // 7.3存储进redis
        stringRedisTemplate.opsForHash().putAll(key, map);
        // 7.4设置有效期
        stringRedisTemplate.expire(key, LOGIN_USER_TTL, TimeUnit.SECONDS);

        return Result.ok(token);
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
