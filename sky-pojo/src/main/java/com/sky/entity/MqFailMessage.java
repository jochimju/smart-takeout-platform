package com.sky.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("mq_fail_message")
public class MqFailMessage implements Serializable {
    private Long id;
    private String exchangeName;
    private String routingKey;
    private String messageBody;
    private String failReason;
    private Integer status;
    private Integer retryCount;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
