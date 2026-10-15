<!--购买页-->
<template>
  <view class="customer-box">
    <view class="wrap">
      <view class="contion">
        <view class="orderPay">
          <view>
            <view v-if="timeout">订单已超时</view>
            <view v-else-if="orderReady"
              >支付剩余时间<text>{{ rocallTime }}</text></view
            >
          </view>
          <view v-if="!orderReady">正在核对订单</view>
          <view class="money"
            >￥<text>{{ orderReady ? Number(orderDataInfo.orderAmount).toFixed(2) : "加载中" }}</text></view
          >
          <view v-if="orderReady">{{ restaurantName }}-{{ orderDataInfo.orderNumber }}</view>
        </view>
      </view>
      <view class="box payBox">
        <view class="contion">
          <view class="example-body">
            <radio-group class="uni-list" @change="styleChange">
              <view class="uni-list-item">
                <view
                  class="uni-list-item__container"
                  v-for="(item, index) in payMethodList"
                  :key="item"
                >
                  <view class="uni-list-item__content">
                    <icon class="wechatIcon"></icon
                    ><text class="uni-list-item__content-title">{{
                      item
                    }}</text>
                  </view>
                  <view class="uni-list-item__extra">
                    <radio
                      :value="item"
                      color="#FFC200"
                      :checked="index == activeRadio"
                      class="radioIcon"
                    />
                  </view>
                </view>
              </view>
            </radio-group>
          </view>
        </view>
      </view>
      <view class="bottomBox btnBox">
        <button class="add_btn" type="primary" plain="true" :disabled="!orderReady || paying" @click="handleSave">
          确认支付
        </button>
      </view>
    </view>
  </view>
</template>

<script>
import { isPaymentOrderReady } from "@/utils/payment-order";
import { executePayment, waitForOrderPayment } from "@/utils/payment-flow";
import { mapState } from "vuex";
import {
  getOrderDetail,
  paymentOrder,
  cancelOrder,
  confirmMockOrderPayment,
} from "@/pages/api/api.js";
export default {
  data() {
    return {
      orderReady: false,
      paying: false,
      timeout: false,
      rocallTime: "",
      orderId: null,
      orderDataInfo: {},
      activeRadio: 0,
      payMethodList: ["微信支付"],
      restaurantName: "餐厅信息待确认",
      times: null,
    };
  },
  created() {
    this.orderDataInfo = this.orderData();
    const currentRestaurant = uni.getStorageSync("currentRestaurant") || {};
    this.restaurantName =
      this.orderDataInfo.restaurantName ||
      currentRestaurant.name ||
      "餐厅信息待确认";
  },
  onLoad(options) {
    this.orderId = options && options.orderId;
    this.loadPaymentOrder();
  },
  onUnload() { clearTimeout(this.times); },
  methods: {
    ...mapState(["orderData"]),
    async loadPaymentOrder() {
      this.orderReady = false;
      if (!/^[1-9]\d*$/.test(String(this.orderId || ""))) {
        uni.showToast({ title: "订单尚未创建，请返回结算页查询", icon: "none" });
        return;
      }
      try {
        const response = await getOrderDetail(this.orderId);
        const detail = response && response.data;
        const order = detail && { ...detail, orderNumber: detail.number, orderAmount: detail.amount };
        if (!response || response.code !== 1 || !isPaymentOrderReady(order) || String(order.id) !== String(this.orderId))
          throw { msg: "订单资料不完整，请稍后重试" };
        if (Number(detail.status) !== 1) throw { msg: "订单已不处于待支付状态" };
        this.orderDataInfo = { ...order, restaurantName: this.restaurantName };
        this.orderReady = true;
        this.runTimeBack();
      } catch (error) {
        uni.showToast({ title: error.msg || error.message || "订单查询失败，请返回后重试", icon: "none" });
      }
    },
    // 支付详情
    async handleSave() {
      if (!this.orderReady || this.paying) return;
      if (this.timeout) {
        uni.redirectTo({
          url: "/pages/details/index?orderId=" + this.orderId,
        });
      } else {
        clearTimeout(this.times);
        const params = {
          orderNumber: this.orderDataInfo.orderNumber,
          payMethod: this.activeRadio === 0 ? 1 : 2,
        };
        this.paying = true;
        let submitted = false;
        try {
          const response = await paymentOrder(params);
          await executePayment(response, {
            amount: this.orderDataInfo.orderAmount,
            showModal: options => uni.showModal(options),
            requestPayment: options => uni.requestPayment(options),
            confirmMock: () => confirmMockOrderPayment(params),
          });
          submitted = true;
          await waitForOrderPayment(() => getOrderDetail(this.orderId));
          uni.redirectTo({ url: "/pages/success/index?orderId=" + this.orderId });
        } catch (err) {
          uni.showToast({
            title: err.msg || err.message || "支付失败，请重试",
            duration: 1500,
            icon: "none",
          });
          if (submitted) {
            this.orderReady = false;
            uni.redirectTo({ url: "/pages/details/index?orderId=" + this.orderId });
          }
        } finally {
          this.paying = false;
          if (this.orderReady) this.runTimeBack();
        }
      }
    },
    // // 订单倒计时
    runTimeBack() {
      clearTimeout(this.times);
      const time = this.orderDataInfo.expireTime || this.orderDataInfo.orderTime;
      const end = typeof time === "string" ? Date.parse(time.replace(/-/g, "/").replace("T", " ")) : NaN;
      if (!Number.isFinite(end)) {
        this.orderReady = false;
        uni.showToast({ title: "订单支付期限缺失，请返回后重试", icon: "none" });
        return;
      }
      const now = Date.parse(new Date());
      const m15 = this.orderDataInfo.expireTime ? 0 : 15 * 60 * 1000;
      const msec = m15 - (now - end);
      if (msec < 0) {
        this.timeout = true;
        clearTimeout(this.times);
      } else {
        let min = parseInt((msec / 1000 / 60) % 60);
        let sec = parseInt((msec / 1000) % 60);
        if (min < 10) {
          min = "0" + min;
        } else {
          min = min;
        }
        if (sec < 10) {
          sec = "0" + sec;
        } else {
          sec = sec;
        }
        this.rocallTime = min + ":" + sec;
        let that = this;
        if (min >= 0 && sec >= 0) {
          if (min === 0 && sec === 0) {
            this.timeout = true;
            clearTimeout(this.times);
            return;
          }
          this.times = setTimeout(function () {
            that.runTimeBack();
          }, 1000);
        }
      }
    },
  },
};
</script>
<style src="./../common/Navbar/navbar.scss" lang="scss" scoped></style>
<style src="./../order/style.scss" lang="scss"></style>
<style>
</style>
