package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements Ilock{
    private StringRedisTemplate stringRedisTemplate;
    private String name;
    private static final String KEYPREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID(true)+"-";
    public static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static{
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }
    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate, String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }

    @Override
    public Boolean tryLock(Long timeSecs) {
        String threadId = ID_PREFIX+Thread.currentThread().getId();

        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEYPREFIX + name, threadId, timeSecs, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unlock() {
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEYPREFIX+name),
                ID_PREFIX + Thread.currentThread().getId()
        );

//        //获取线程标识
//        String threadId = ID_PREFIX+Thread.currentThread().getId();
//        //判断标识是否一致
//        String id = stringRedisTemplate.opsForValue().get(KEYPREFIX + name);
//
//        if(id.equals(threadId)) {
//            stringRedisTemplate.delete(KEYPREFIX + name);
//        }
    }
}
