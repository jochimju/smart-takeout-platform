<!--配送信息-->
<template>
  <view class="box">
    <view class="orderBaseInfo">
      <view>
        <view>期望时间</view>
        <view>{{
          orderDetailsData.deliveryStatus === 1
            ? "立即送出"
            : orderDetailsData.estimatedDeliveryTime
        }}</view>
      </view>
      <view>
        <view>配送地址</view>
        <view>
          <view class="nameInfo">
            <text>{{ orderDetailsData.consignee }}</text>
            {{ orderDetailsData.phone }}
          </view>
          <view>{{ orderDetailsData.address }}</view>
        </view>
      </view>
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
  },
};
</script>
<style src="../../order/style.scss" lang="scss"></style>