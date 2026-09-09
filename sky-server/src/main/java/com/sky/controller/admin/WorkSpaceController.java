package com.sky.controller.admin;

import com.sky.result.Result;
import com.sky.service.WorkspaceService;
import com.sky.vo.BusinessDataVO;
import com.sky.vo.DishOverViewVO;
import com.sky.vo.OrderOverViewVO;
import com.sky.vo.SetmealOverViewVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 瀹搞儰缍旈崣?
 */
@RestController
@RequestMapping("/admin/workspace")
@Slf4j
@Api(tags = "workspace api")
public class WorkSpaceController {

    @Autowired
    private WorkspaceService workspaceService;

    /**
     * 瀹搞儰缍旈崣棰佺矕閺冦儲鏆熼幑顔界叀鐠?
     * @return
     */
    @GetMapping("/businessData")
    @ApiOperation("today business data")
    public Result<BusinessDataVO> businessData(){
        //閼惧嘲绶辫ぐ鎾炽亯閻ㄥ嫬绱戞慨瀣闂?
        LocalDateTime begin = LocalDateTime.now().with(LocalTime.MIN);
        //閼惧嘲绶辫ぐ鎾炽亯閻ㄥ嫮绮ㄩ弶鐔告闂?
        LocalDateTime end = LocalDateTime.now().with(LocalTime.MAX);

        BusinessDataVO businessDataVO = workspaceService.getBusinessData(begin, end);
        return Result.success(businessDataVO);
    }

    /**
     * 閺屻儴顕楃拋銏犲礋缁狅紕鎮婇弫鐗堝祦
     * @return
     */
    @GetMapping("/overviewOrders")
    @ApiOperation("閺屻儴顕楃拋銏犲礋缁狅紕鎮婇弫鐗堝祦")
    public Result<OrderOverViewVO> orderOverView(){
        return Result.success(workspaceService.getOrderOverView());
    }

    /**
     * 閺屻儴顕楅懣婊冩惂閹槒顫?
     * @return
     */
    @GetMapping("/overviewDishes")
    @ApiOperation("dish overview")
    public Result<DishOverViewVO> dishOverView(){
        return Result.success(workspaceService.getDishOverView());
    }

    /**
     * 閺屻儴顕楁總妤咁樀閹槒顫?
     * @return
     */
    @GetMapping("/overviewSetmeals")
    @ApiOperation("setmeal overview")
    public Result<SetmealOverViewVO> setmealOverView(){
        return Result.success(workspaceService.getSetmealOverView());
    }
}
