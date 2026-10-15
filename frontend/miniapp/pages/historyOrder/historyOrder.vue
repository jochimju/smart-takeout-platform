<!--历史订单-->
<template>
  <view class="history_order">
    <uni-nav-bar
      @clickLeft="goBack"
      left-icon="back"
      leftIcon="arrowleft"
      title="历史订单"
      statusBar="true"
      fixed="true"
      color="#ffffff"
      backgroundColor="#333333"
    ></uni-nav-bar>
    <!-- 根据scrollinto和:id="'tab'+index"切换下方轮播 -->
    <scroll-view
      scroll-x="true"
      class="scroll-row"
      :scroll-into-view="scrollinto"
      :scroll-with-animation="true"
    >
      <view
        v-for="(item, index) in tabBars"
        :key="index"
        :id="'tab' + index"
        class="scroll-row-item"
        @click="changeTab(index)"
      >
        <view :class="tabIndex == index ? 'scroll-row-item-act' : ''"
          ><text class="line"></text>{{ item }}</view
        >
      </view>
    </scroll-view>
    <!--  滑块内容 对应的是顶部选项卡的切换 :current="tabIndex"  设置的是y方向上可以滚动-->
    <swiper
      :current="tabIndex"
      @change="onChangeSwiperTab"
      :style="{ height: scrollH + 'px' }"
    >
      <swiper-item v-for="(item, index) in tabBars" :key="index">
        <!-- 垂直滚动区域  scroll和swiper的高度都要给且是一样的高度-->
        <scroll-view
          scroll-y="true"
          :style="{ height: scrollH + 'px' }"
          @scrolltolower="lower"
        >
          <!-- 可垂直滚动区域 显示真正内容-->
          <view
            class="main recent_orders"
            v-if="recentOrdersList && recentOrdersList.length > 0"
          >
            <!-- 历史订单列表 -->
            <view
              class="box order_lists"
              v-for="(item, index) in recentOrdersList"
              :key="index"
              :class="{
                'item-last': Number(index) + 1 === recentOrdersList.length,
              }"
            >
              <!-- 时间和支付状态 -->
              <view class="date_type">
                <!-- 时间 -->
                <text class="time">{{ item.orderTime }}</text>
                <!-- 支付状态 -->
                <text
                  class="type status"
                  :class="{ status: item.status == 2 }"
                  >{{
                    statusWord(item.status, getOvertime(item.orderTime))
                  }}</text
                >
              </view>
              <!-- 点菜的内容 -->
              <view class="orderBox" @click="goDetail(item.id)">
                <view class="food_num">
                  <scroll-view
                    scroll-x="true"
                    class="pic"
                    style="width: 100%; overflow: hidden; white-space: nowrap"
                  >
                    <view
                      class="food_num_item"
                      v-for="(num, y) in item.orderDetailList"
                      :key="y"
                    >
                      <view class="img">
                        <image :src="resolveMenuImage(num.image)"></image>
                      </view>
                      <view class="food">{{ num.name }}</view>
                    </view>
                  </scroll-view>
                </view>
                <!-- 商品数量及金额 -->
                <view class="numAndAum">
                  <view
                    ><text
                      >￥{{
                        numes(item.orderDetailList).total.toFixed(2)
                      }}</text
                    ></view
                  >
                  <view
                    ><text
                      >共{{ numes(item.orderDetailList).count }}件</text
                    ></view
                  >
                </view>
              </view>
              <view class="againBtn">
                <button
                  class="new_btn"
                  type="default"
                  @click="oneMoreOrder(item.id)"
                >
                  再来一单
                </button>
                <button
                  class="new_btn delete_btn"
                  type="default"
                  @click="deleteOrder(item)"
                  v-if="canDeleteOrder(item.status)"
                >
                  删除订单
                </button>
                <button
                  class="new_btn btn"
                  type="default"
                  @click="goDetail(item.id)"
                  v-if="item.status === 1 && getOvertime(item.orderTime) > 0"
                >
                  去支付
                </button>
                <button
                  class="new_btn btn"
                  type="default"
                  @click="handleReminder('center', item.id)"
                  v-if="item.status === 2"
                >
                  催单
                </button>
              </view>
            </view>
          </view>
        </scroll-view>
      </swiper-item>
    </swiper>
    <uni-popup ref="commonPopup" class="comPopupBox">
      <view class="popup-content">
        <view class="text">{{ textTip }}</view>
        <view class="btn" v-if="showConfirm">
          <view @click="closePopup">确认</view>
        </view>
      </view>
    </uni-popup>
  </view>
</template>

<script>
import {
  getOrderPage,
  reminderOrder,
  deleteHistoryOrder,
} from "../api/api.js";
import { mapMutations } from "vuex";
import Empty from "@/components/empty/empty";
import { statusWord, getOvertime } from "@/utils/index.js";
import { resolveMenuImage } from "@/utils/menuImage.js";
import { reorderToRestaurant } from "@/utils/reorder.js";
export default {
  components: {
    Empty,
  },
  data() {
    return {
      recentOrdersList: [],
      pageInfo: {
        page: 1,
        pageSize: 10,
        total: 0,
      },
      status: "",
      loadingType: 0,
      showTitle: false,
      scrollinto: "tab0",
      scrollH: 0,
      tabIndex: 0,
      tabBars: ["全部订单", "待付款", "退款"],
      textTip: "",
      showConfirm: false,
      isEmpty: false,
    };
  },
  onLoad() {
    this.getList();
  },
  onUnload() {
    this.showTitle = false;
  },
  onReady() {
    uni.getSystemInfo({
      success: (res) => {
        this.scrollH = res.windowHeight - uni.upx2px(100);
      },
    });
  },
  onPullDownRefresh() {
    this.pageInfo.page = 1;
    this.loadingType = 0;
    this.recentOrdersList = [];
    this.finished = false;
    this.getList();
    uni.stopPullDownRefresh();
    this.showTitle = true;
  },
  onReachBottom() {
    if (this.recentOrdersList.length < Number(this.pageInfo.total)) {
      this.pageInfo.page++;
      this.loadingStatus = "loading";
      this.getList(this.status);
      this.showTitle = true;
    }
  },
  methods: {
    //
    ...mapMutations(["setAddressBackUrl"]),
    resolveMenuImage,
    numes(list) {
      let count = 0;
      let total = 0;
      list.length > 0 &&
        list.forEach((obj) => {
          count += Number(obj.number);
          total += Number(obj.number) * Number(obj.amount);
        });
      return { count: count, total: total };
    },
    statusWord(status, time) {
      return statusWord(status, time);
    },
    getOvertime(time) {
      return getOvertime(time);
    },
    // 仅已完成或已取消的订单允许从历史记录中删除
    canDeleteOrder(status) {
      return [5, 6].includes(Number(status));
    },
    // 获取历史订单列表
    getList() {
      const params = {
        pageSize: 10,
        page: this.pageInfo.page,
        status: this.status !== "" ? this.status : "",
      };
      uni.showLoading({ title: "加载中", mask: true });
      getOrderPage(params).then((res) => {
        if (res.code === 1) {
          setTimeout(function () {
            uni.hideLoading();
          }, 100);
          this.recentOrdersList = this.recentOrdersList.concat(
            res.data.records
          );
          this.pageInfo.total = res.data.total;
          this.isEmpty = true;
        }
      });
    },
    // 再来一单
    async oneMoreOrder(id) {
      try {
        await reorderToRestaurant(id);
      } catch (err) {
        uni.showToast({ title: err.msg || err.message || "再来一单失败", icon: "none" });
      }
    },
    // 删除历史订单：后端会保留订单本身，只对当前用户隐藏
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
    // tab选项卡切换轮播
    changeTab(index) {
      // 点击的还是当前数据的时候直接return
      if (this.tabIndex == index) {
        return;
      }
      this.tabIndex = index;
      if (index === 1) {
        this.status = 1;
      } else if (index === 2) {
        this.status = 6;
      } else {
        this.status = "";
      }
      this.pageInfo.page = 1;
      this.recentOrdersList = [];
      this.getList(this.status);
      // 滑动
      this.scrollinto = "tab" + index;
    },
    onChangeSwiperTab(e) {
      this.changeTab(e.detail.current);
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
    // 去详情页面
    goDetail(id) {
      this.setAddressBackUrl("/pages/historyOrder/historyOrder");
      uni.redirectTo({
        url: "/pages/details/index?orderId=" + id,
      });
    },
    // 催单
    handleReminder(type, id) {
      reminderOrder(id).then((res) => {
        if (res.code === 1) {
          this.showConfirm = true;
          this.textTip = "您的催单信息已发出！";
          this.$refs.commonPopup.open(type);
          this.getList(this.status);
        }
      });
    },
    // 关闭弹层
    closePopup(type) {
      this.$refs.commonPopup.close(type);
    },
    // 返回我的
    goBack() {
      uni.redirectTo({
        url: "/pages/my/my",
      });
    },
  },
};
</script>

<style lang="scss" scoped>
.history_order {
  height: 100%;
  .recent_orders {
    padding-top: 8rpx;
  }
}
.scroll-row {
  height: 88rpx;
  line-height: 88rpx;
  background-color: #fff;
  padding: 0 30rpx;
  display: flex;
  box-shadow: 0 2px 4px 0 rgba(0, 0, 0, 0.06);
}
.scroll-row-item {
  margin-right: 88rpx;
  color: #666;
  display: inline-block;
  font-size: 28rpx;
}
.scroll-row-item-act {
  color: #333;
  position: relative;
  font-weight: 600;
  .line {
    width: 32rpx;
    height: 8rpx;
    display: block;
    background: #1468d4;
    border-radius: 8rpx;
    transform: translate(-50%, -50%);
    position: absolute;
    bottom: -4rpx;
    left: 50%;
  }
}
.delete_btn {
  color: #e95f3c !important;
  border-color: #f2b7a8 !important;
}
</style>
