package com.hmdp.utils;

public interface Ilock {
    Boolean tryLock(Long timeSecs);
    void unlock();
}
