package com.hmdp.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
@Slf4j
public class RedisWorker {
    private StringRedisTemplate stringRedisTemplate;
    private Long BEGIN_TIMESTAMP = 1735689600L; // 2025-01-01 00:00:00 UTC
    private int COUNT_BITS = 32; // 序列号占用的位数
    public RedisWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public long nextId(String keyPrefix){
        //获得时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        //计算时间戳
        Long timeStamp = nowSecond - BEGIN_TIMESTAMP;

        //生成序列号
        //获得当前日期，精确到天
        String date = now.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        //redis自增长
        long count = stringRedisTemplate.opsForValue().increment("incr:"+keyPrefix+":"+date);

        //拼接并返回
        return timeStamp<<COUNT_BITS|count;
    }
}
