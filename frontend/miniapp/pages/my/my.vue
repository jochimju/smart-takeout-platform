<template>
  <view>
    <uni-nav-bar
      @clickLeft="goBack"
      left-icon="back"
      leftIcon="arrowleft"
      title="个人中心"
      statusBar="true"
      fixed="true"
      color="#ffffff"
      backgroundColor="#1468d4"
    ></uni-nav-bar>

    <view class="my-center">
      <!-- 头像展示部分 -->
      <head
        :psersonUrl="psersonUrl"
        :nickName="nickName"
        :gender="gender"
        :phoneNumber="phoneNumber"
        :getPhoneNum="getPhoneNum"
      ></head>

      <view class="container">
        <!-- 地址和历史订单 -->
        <order-info @goAddress="goAddress" @goOrder="goOrder"></order-info>
        <view class="red-packet-entry" @click="goRedPacket">
          <view><text class="red-packet-title">我的红包</text><text class="red-packet-tip">购买红包包，点餐时自动抵扣</text></view>
          <text class="red-packet-arrow">›</text>
        </view>
        <!-- 最近订单 -->
        <!-- 最近订单title -->
        <view
          class="recent"
          v-if="recentOrdersList && recentOrdersList.length > 0"
        >
          <text class="order_line">最近订单</text>
        </view>
        <order-list
          :scrollH="scrollH"
          @lower="lower"
          @goDetail="goDetail"
          @oneOrderFun="oneOrderFun"
          @deleteOrder="deleteOrder"
          @getOvertime="getOvertime"
          @statusWord="statusWord"
          @numes="numes"
          :loading="loading"
          :loadingText="loadingText"
          :recentOrdersList="recentOrdersList"
        ></order-list>
      </view>
    </view>
  </view>
</template>

<script>
import {
  getOrderPage,
  deleteHistoryOrder,
} from "../api/api.js";
import { mapMutations } from "vuex";
import { statusWord, getOvertime } from "@/utils/index.js";
import { reorderToRestaurant } from "@/utils/reorder.js";

import HeadInfo from "./components/headInfo.vue"; //头部
import OrderInfo from "./components/orderInfo.vue"; //地址
import OrderList from "./components/orderList.vue"; //最近订单
export default {
  data() {
    return {
      psersonUrl: "../../static/btn_waiter_sel.png",
      nickName: "",
      gender: "0",
      phoneNumber: "18500557668",
      recentOrdersList: [],
      sumOrder: {
        amount: 0,
        number: 0,
      },
      status: "",
      scrollH: 0,
      pageInfo: {
        page: 1,
        pageSize: 10,
        total: 0,
      },
      loadingText: "",
      loading: false,
    };
  },
  components: {
    HeadInfo,
    OrderInfo,
    OrderList,
  },
  filters: {
    getPhoneNum(str) {
      return str.replace(/\-/g, "");
    },
  },
  onLoad() {
    this.psersonUrl =
      this.$store.state.baseUserInfo &&
      this.$store.state.baseUserInfo.avatarUrl;
    this.nickName =
      this.$store.state.baseUserInfo && this.$store.state.baseUserInfo.nickName;
    this.gender =
      this.$store.state.baseUserInfo && this.$store.state.baseUserInfo.gender;
    this.phoneNumber =
      this.$store.state.shopPhone && this.$store.state.shopPhone;
    this.getList();
  },
  created() {},
  onReady() {
    uni.getSystemInfo({
      success: (res) => {
        this.scrollH = res.windowHeight - uni.upx2px(100);
      },
    });
  },
  methods: {
    ...mapMutations(["setAddressBackUrl"]),
    statusWord(obj) {
      return statusWord(obj.status, obj.time);
    },
    getOvertime(time) {
      return getOvertime(time);
    },
    // 获取列表数据
    getList() {
      const params = {
        pageSize: 10,
        page: this.pageInfo.page,
      };
      getOrderPage(params).then((res) => {
        if (res.code === 1) {
          this.recentOrdersList = this.recentOrdersList.concat(
            res.data.records
          );
          this.pageInfo.total = res.data.total;
          this.loadingText = "";
          this.loading = false;
        }
      });
    },
    // 去地址页面
    goAddress() {
      this.setAddressBackUrl("/pages/my/my");
      // TODO
      uni.redirectTo({
        url: "/pages/address/address?form=" + "my",
      });
    },
    // 去历史订单页面
    goOrder() {
      // TODO
      uni.navigateTo({
        url: "/pages/historyOrder/historyOrder",
      });
    },
    goRedPacket() {
      uni.navigateTo({ url: "/pages/redPacket/index" });
    },
    async oneOrderFun(id) {
      try {
        await reorderToRestaurant(id);
      } catch (err) {
        uni.showToast({ title: err.msg || err.message || "再来一单失败", icon: "none" });
      }
    },
    deleteOrder(order) {
      uni.showModal({
        title: "删除订单",
        content: "删除后将不再在历史订单中展示，确定删除吗？",
        confirmColor: "#e95f3c",
        success: ({ confirm }) => {
          if (!confirm) return;
          uni.showLoading({ title: "删除中", mask: true });
          deleteHistoryOrder(order.id)
            .then((res) => {
              if (res.code !== 1) {
                throw new Error(res.msg || "删除失败，请重试");
              }
              this.pageInfo.page = 1;
              this.recentOrdersList = [];
              this.getList();
              uni.showToast({ title: "订单已删除", icon: "success" });
            })
            .catch((err) => {
              uni.hideLoading();
              uni.showToast({
                title: err.message || "删除失败，请重试",
                icon: "none",
              });
            });
        },
      });
    },
    quitClick() {},
    // 去详情页面
    goDetail(id) {
      this.setAddressBackUrl("/pages/my/my");
      uni.redirectTo({
        url: "/pages/details/index?orderId=" + id,
      });
    },
    dataAdd() {
      const pages = Math.ceil(this.pageInfo.total / 10); //计算总页数
      if (this.pageInfo.page === pages) {
        this.loadingText = "没有更多了";
        this.loading = true;
      } else {
        this.pageInfo.page++;
        this.getList();
      }
    },

    lower() {
      this.loadingText = "数据加载中...";
      this.loading = true;
      this.dataAdd();
    },
    goBack() {
      uni.redirectTo({
        url: "/pages/index/index",
      });
    },
  },
};
</script>
<style lang="scss" scoped>
.red-packet-entry {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 20rpx 0;
  padding: 26rpx 30rpx;
  border-radius: 14rpx;
  color: #fff;
  background: linear-gradient(135deg, #f34b4b, #c5162f);
}
.red-packet-title { display: block; font-size: 30rpx; font-weight: 700; }
.red-packet-tip { display: block; margin-top: 8rpx; font-size: 23rpx; opacity: .88; }
.red-packet-arrow { font-size: 48rpx; line-height: 1; }
.my-center {
  background: #f6f6f6;
  height: 100%;

  .container {
    margin-top: 20rpx;
    height: calc(100% - 194rpx);
  }
}
::v-deep .uni-navbar--border {
  border-width: 0 !important;
}
</style>
