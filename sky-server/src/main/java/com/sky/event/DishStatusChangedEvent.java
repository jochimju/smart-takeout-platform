package com.sky.event;

import lombok.Value;

@Value
public class DishStatusChangedEvent {
    boolean setmealsChanged;
}
