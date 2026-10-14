package com.sky.service.impl;

import com.alibaba.fastjson.JSON;
import com.sky.dto.SeckillOrderSubmitDTO;
import com.sky.dto.SeckillOrderMessageDTO;
import com.sky.mapper.SeckillActivityMapper;
import com.sky.task.SeckillAsyncTask;
import com.sky.vo.OrderSubmitVO;
import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.mockito.Mockito.*;

class SeckillBridgeBatchTest {
    @Test void interruptedAfterCommitBeforeRedisDeleteReplaysSameBatch() {
        var activities=mock(SeckillActivityMapper.class);var cache=mock(SeckillCache.class);var store=mock(SeckillAsyncStore.class);
        fixture(activities,cache);
        doThrow(new IllegalStateException("bridge stopped after commit")).doNothing().when(cache).handedOffBatch(eq(1L),anyList());
        var task=new SeckillAsyncTask(activities,cache,store);task.bridge();task.bridge();
        verify(store,times(2)).acceptBatch(argThat(rows->rows.size()==2));verify(cache,times(2)).handedOffBatch(1L,List.of("SK1","SK2"));
    }
    @Test void removesTasksOnlyAfterWholeBatchCommitReturns() {
        var activities=mock(SeckillActivityMapper.class);var cache=mock(SeckillCache.class);var store=mock(SeckillAsyncStore.class);
        fixture(activities,cache);new SeckillAsyncTask(activities,cache,store).bridge();
        var ordered=inOrder(store,cache);ordered.verify(store).acceptBatch(argThat(rows -> rows.size()==2));
        ordered.verify(cache).handedOffBatch(1L,List.of("SK1","SK2"));
    }
    @Test void transientDatabaseFailureRetainsEntireBatchWithoutSingletonRetryStorm() {
        var activities=mock(SeckillActivityMapper.class);var cache=mock(SeckillCache.class);var store=mock(SeckillAsyncStore.class);
        fixture(activities,cache);doThrow(new IllegalStateException("database unavailable")).when(store).acceptBatch(anyList());
        new SeckillAsyncTask(activities,cache,store).bridge();verify(store,times(1)).acceptBatch(anyList());verify(cache,never()).handedOffBatch(anyLong(),anyList());
    }
    @Test void permanentIdentityConflictIsIsolatedAndOnlyValidTasksAreRemoved() {
        var activities=mock(SeckillActivityMapper.class);var cache=mock(SeckillCache.class);var store=mock(SeckillAsyncStore.class);
        fixture(activities,cache);
        doAnswer(call -> { List<SeckillOrderMessageDTO> rows=call.getArgument(0);
            if(rows.stream().anyMatch(row -> "SK2".equals(row.getOrderNumber())))throw new SeckillAsyncStore.AcceptanceConflict("conflicting identity");return null;
        }).when(store).acceptBatch(anyList());
        new SeckillAsyncTask(activities,cache,store).bridge();verify(cache).handedOffBatch(1L,List.of("SK1"));verify(cache,never()).handedOffBatch(eq(1L),argThat(rows -> rows.contains("SK2")));
    }
    private void fixture(SeckillActivityMapper activities,SeckillCache cache) {
        when(activities.allIds()).thenReturn(List.of(1L));var pending=new LinkedHashMap<String,String>();
        for(long id=1;id<=2;id++) {
            var request=new SeckillOrderSubmitDTO();request.setRequestId("request"+id);var command=new SeckillOrderMessageDTO();
            command.setActivityId(1L);command.setUserId(id);command.setOrderNumber("SK"+id);command.setRequest(request);
            pending.put("SK"+id,JSON.toJSONString(Map.of("command",JSON.toJSONString(command),"price",1,"acceptedAt",System.currentTimeMillis())));
            var accepted=new OrderSubmitVO();accepted.setOrderNumber("SK"+id);when(cache.find(1L,id,"request"+id)).thenReturn(accepted);
        }
        when(cache.findBatch(eq(1L),anyList())).thenAnswer(call->{
            List<SeckillOrderMessageDTO> commands=call.getArgument(1);var result=new LinkedHashMap<String,OrderSubmitVO>();
            for(var command:commands)result.put(command.getOrderNumber(),cache.find(1L,command.getUserId(),command.getRequest().getRequestId()));
            return result;
        });
        when(cache.pending(1L)).thenReturn(pending);
    }
}
