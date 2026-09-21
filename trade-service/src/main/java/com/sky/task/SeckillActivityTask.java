package com.sky.task;

import com.sky.mapper.SeckillActivityMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 每分钟将到期的秒杀活动标为停用，供管理端和后续查询使用。 */
@Component
@Slf4j
public class SeckillActivityTask {
    @Autowired
    private SeckillActivityMapper seckillActivityMapper;

    @Scheduled(cron = "0 * * * * ?")
    public void disableExpiredActivities() {
        int affected = seckillActivityMapper.disableExpiredActivities();
        if (affected > 0) {
            log.info("已自动下架 {} 个到期秒杀活动", affected);
        }
    }
}
