package com.sky.controller.user;

import com.sky.dto.RedPacketPaymentDTO;
import com.sky.dto.RedPacketPurchaseDTO;
import com.sky.result.Result;
import com.sky.service.RedPacketService;
import com.sky.vo.OrderPaymentVO;
import com.sky.vo.RedPacketPurchaseVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/user/red-packet")
@Api(tags = "red packet api")
@RequiredArgsConstructor
public class RedPacketController {
    private final RedPacketService redPackets;

    @GetMapping("/package")
    @ApiOperation("list red packet packages")
    public Result packages() { return Result.success(redPackets.packages()); }

    @PostMapping("/purchase-orders")
    @ApiOperation("create red packet purchase order")
    public Result<RedPacketPurchaseVO> purchase(@RequestBody RedPacketPurchaseDTO dto) {
        return Result.success(redPackets.createPurchase(dto));
    }

    @PutMapping("/purchase-orders/payment")
    @ApiOperation("pay red packet purchase order")
    public Result<OrderPaymentVO> payment(@RequestBody RedPacketPaymentDTO dto) throws Exception {
        return Result.success(redPackets.payment(dto.getPurchaseOrderNo()));
    }

    @PutMapping("/purchase-orders/payment/mock/confirm")
    @ApiOperation("confirm mock red packet payment")
    public Result mockPayment(@RequestBody RedPacketPaymentDTO dto) {
        redPackets.confirmMockPayment(dto.getPurchaseOrderNo());
        return Result.success();
    }

    @GetMapping("/my")
    @ApiOperation("list my available red packets")
    public Result my() { return Result.success(redPackets.myAvailable()); }
}
