package com.max.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;


public class SimpleRedisLock implements ILock{
    private StringRedisTemplate stringRedisTemplate;
    private String name;//锁名称
    private static final String KEY_PREFIX ="lock:";//锁前缀
    private static final String ID_PREFIX= UUID.randomUUID().toString(true)+"-";
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT=new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate, String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        String threadId = ID_PREFIX+Thread.currentThread().getId();
        Boolean isSuccess = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX +name, threadId, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(isSuccess);
    }
   @Override
    public void unlock() {
       //基于Lua脚本执行
       stringRedisTemplate.execute(UNLOCK_SCRIPT
               , Collections.singletonList(KEY_PREFIX +name)
               ,ID_PREFIX+Thread.currentThread().getId());

    }

/*    @Override
    public void unlock() {
        String threadId = ID_PREFIX+Thread.currentThread().getId();
        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
        if(threadId.equals(id)){
            //释放锁
            stringRedisTemplate.delete(KEY_PREFIX +name);
        }
    }*/
}
