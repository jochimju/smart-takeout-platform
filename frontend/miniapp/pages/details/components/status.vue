<!-- 订单状态 -->
<template>
  <view>
    <view class="box">
      <!-- 1待付款 2待接单 3 已接单 4 派送中 5 已完成 6 已取消 7 退款 -->
      <view class="orderInfoTip">
        <view class="tit">{{ statusText }}</view>
        <view v-if="isPendingPayment">
          <view class="tit">等待支付</view>
          <view class="time">
            <view class="timeIcon"></view>
            等待支付：
            <text>{{ rocallTime }}</text>
            <text>{{ paymentTime }}</text>
          </view>
        </view>
        <view class="againBtn">
          <button
            class="new_btn"
            type="default"
            @click="handleCancel('center', orderDetailsData)"
            v-if="
              isPendingPayment ||
              orderDetailsData.status === 2 ||
              orderDetailsData.status === 3 ||
              orderDetailsData.status === 4
            "
          >
            取消订单
          </button>
          <button
            class="new_btn btn"
            type="default"
            @click="handlePay(orderDetailsData.id)"
            v-if="isPendingPayment"
          >
            立即支付
          </button>
          <button
            class="new_btn btn"
            type="default"
            @click="handleReminder('center', orderDetailsData.id)"
            v-if="orderDetailsData.status === 2"
          >
            催单
          </button>
          <button
            class="new_btn"
            type="default"
            @click="handleRefund('center')"
            v-if="orderDetailsData.status == 5"
          >
            申请退款
          </button>
          <button
            class="new_btn"
            type="default"
            @click="oneMoreOrder(orderDetailsData.id)"
            v-if="orderDetailsData.status !== 7"
          >
            再来一单
          </button>
        </view>
      </view>
    </view>
    <view class="box timeTip" v-if="isPendingPayment">
      <view class="icon newIcon"></view>
      请在15分钟内完成支付，超时将自动取消。
    </view>
    <view
      class="box timeTip"
      v-if="orderDetailsData.status === 6 && orderDetailsData.payStatus === 2"
    >
      <view class="icon moneyIcon"></view>
      您的订单已
      <text>退款成功</text>
      。
    </view>
  </view>
</template>
<script>
export default {
  // 获取父级传的数据
  props: {
    // 订单详情
    orderDetailsData: {
      type: Object,
      default: () => ({}),
    },
    // 倒计时间
    timeout: {
      type: Boolean,
      default: false,
    },
    // 支付时间
    rocallTime: {
      type: String,
      default: '',
    }
  },
  computed: {
    isPendingPayment() {
      return !this.timeout && Number(this.orderDetailsData.status) === 1 && Number(this.orderDetailsData.payStatus) === 0;
    },
    statusText() {
      const status = Number(this.orderDetailsData.status);
      const paid = Number(this.orderDetailsData.payStatus) === 1;
      if (this.timeout && status === 1) return "订单已取消";
      if (status === 1) return paid ? "支付完成，等待商家接单" : "等待支付";
      return {
        2: "等待商户接单",
        3: "商家已接单",
        4: "订单派送中",
        5: "订单已完成",
        6: "订单已取消",
        7: "退款中",
      }[status] || "订单状态待确认";
    },
  },
  methods: {
    //取消订单 
    handleCancel (type, obj) {
      // 小程序组件事件统一使用短横线命名，避免驼峰事件在编译后丢失大小写。
      this.$emit("cancel-order", { type: type, obj: obj })
    },
    // 立即支付
    handlePay (id) {
      this.$emit("handlePay", id)
    },
    // 催单
    handleReminder (type, id) {
      this.$emit("handleReminder", { type: type, id: id })
    },
    // 申请退款
    handleRefund (type) {
      this.$emit("handleRefund", type)
    },
    // 再来一单
    oneMoreOrder (id) {
      this.$emit("repeat-order", id)
    }
  }
}
</script>
<style src="../../order/style.scss" lang="scss"></style>
