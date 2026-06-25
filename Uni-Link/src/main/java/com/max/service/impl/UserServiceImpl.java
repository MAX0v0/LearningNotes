package com.max.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.max.dto.LoginFormDTO;
import com.max.dto.Result;
import com.max.dto.UserDTO;
import com.max.entity.User;
import com.max.mapper.UserMapper;
import com.max.service.IUserService;
import com.max.utils.RedisConstants;
import com.max.utils.RegexUtils;
import com.max.utils.SystemConstants;
import com.max.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 *
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result sendCode(String phone, HttpSession session) {
        //校验手机号,格式正确返回ok,格式错误返回err
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式有误");
        }
        //生成验证码
        String code = RandomUtil.randomNumbers(6);
        //保存到redis中//有效期一分钟
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY +phone,code,RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //发送短信,需要用到第三方技术
        //发送验证码短信  sendCodeToPhone();
        log.debug("发送验证码成功.{}",code);

        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        String phone=loginForm.getPhone();
        //校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式有误");
        }
        //校验验证码
        String cacheCode = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY +phone);//之前存的code
        String code = loginForm.getCode();
        //不对-返回
        if(cacheCode==null || !cacheCode.equals(code)){
            return Result.fail("验证码错误,请重试");
        }
        //对的-若手机号在数据库中不存在则创建一个新的用户到数据库中
        User user = query().eq("phone", phone).one();
        if(user==null){
            user=createUserWithPhone(phone);
        }
        //把登录信息保存到redis中
        String token = UUID.randomUUID().toString(true);
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        //将userDTO转换成Map,并且将所有类型全部改成String类型
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO,new HashMap<>(), CopyOptions.create()
                .setIgnoreNullValue(true)
                .setFieldValueEditor((fieldName,fieldValue)->fieldValue.toString()));
        stringRedisTemplate.opsForHash().putAll(RedisConstants.LOGIN_USER_KEY+token,userMap);
        //设置有效期
        stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY,RedisConstants.LOGIN_USER_TTL+RandomUtil.randomLong(1L,10L),TimeUnit.MINUTES);
        //把key返回前端,每次处理就会带上key的信息
        return Result.ok(token);
    }

    @Override
    public Result sign() {
        //获取当前登录的用户
        Long userId = UserHolder.getUser().getId();
        //获取日期
        LocalDateTime now = LocalDateTime.now();
        //拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key=RedisConstants.USER_SIGN_KEY+userId+keySuffix;
        //获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();

        //写入redis setbit key
        stringRedisTemplate.opsForValue().setBit(key,dayOfMonth-1,true);

        return Result.ok();
    }

    @Override
    public Result signCount() {
        //获取当前登录的用户
        Long userId = UserHolder.getUser().getId();
        //获取日期
        LocalDateTime now = LocalDateTime.now();
        //拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key=RedisConstants.USER_SIGN_KEY+userId+keySuffix;
        //获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        //写入redis setbit key
        stringRedisTemplate.opsForValue().setBit(key,dayOfMonth-1,true);
        //先获取本月截至今天为止所有签到记录,返回的是一个十进制的数字 BITFIELD sign:5:202604 get u10 0
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands
                                .BitFieldType.unsigned(dayOfMonth))
                        .valueAt(0)
        );
        if(result==null||result.isEmpty()){return Result.ok(0);}
        Long num = result.get(0);
        if(num==null||num==0){return Result.ok(0);}
        int count=0;
        while(true){
            if((num & 1)==0){
                break;
            }else{
                count++;
            }
            num >>>= 1;
        }


        return Result.ok(count);
    }

    private User createUserWithPhone(String phone) {
        User newUser=new User();
        newUser.setPhone(phone);
        newUser.setNickName(SystemConstants.USER_NICK_NAME_PREFIX +RandomUtil.randomString(10));
        save(newUser);
        return newUser;
    }
}
