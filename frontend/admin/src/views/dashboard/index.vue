<template>
  <div class="dashboard-container home">
    <section class="dashboard-welcome">
      <div>
        <span class="dashboard-welcome__tag">校园餐饮运营中心</span>
        <h1>你好，欢迎来到智餐工作台</h1>
        <p>查看今日餐饮运营情况，及时处理订单与餐品状态。</p>
      </div>
      <div class="dashboard-welcome__scene">
        <i class="el-icon-s-shop" />
        <div><strong>下沙校区</strong><span>今日运营概览</span></div>
      </div>
    </section>
    <!-- 营业数据 -->
    <Overview :overviewData="overviewData" />
    <!-- end -->
    <!-- 订单管理 -->
    <Orderview :orderviewData="orderviewData" />
    <!-- end -->
    <div class="homeMain">
      <!-- 菜品总览 -->
      <CuisineStatistics :dishesData="dishesData" />
      <!-- end -->
      <!-- 套餐总览 -->
      <SetMealStatistics :setMealData="setMealData" />
      <!-- end -->
    </div>
    <!-- 订单信息 -->
    <OrderList
      :order-statics="orderStatics"
      @getOrderListBy3Status="getOrderListBy3Status"
    />
    <!-- end -->
  </div>
</template>

<script lang="ts">
import { Component, Vue } from 'vue-property-decorator'
import {
  getBusinessData,
  getDataOverView, //营业数据
  getOrderData, //订单管理今日订单
  getOverviewDishes, //菜品总览
  getSetMealStatistics, //套餐总览
} from '@/api/index'
import { getOrderListBy } from '@/api/order'
// 组件
// 营业数据
import Overview from './components/overview.vue'
// 订单管理
import Orderview from './components/orderview.vue'
// 菜品总览
import CuisineStatistics from './components/cuisineStatistics.vue'
// 套餐总览
import SetMealStatistics from './components/setMealStatistics.vue'
// 订单列表
import OrderList from './components/orderList.vue'
@Component({
  name: 'Dashboard',
  components: {
    Overview,
    Orderview,
    CuisineStatistics,
    SetMealStatistics,
    OrderList,
  },
})
export default class extends Vue {
  private todayData = {} as any
  private overviewData = {}
  private orderviewData = {} as any
  private flag = 2
  private tateData = []
  private dishesData = {} as any
  private setMealData = {}
  private orderListData = []
  private counts = 0
  private page: number = 1
  private pageSize: number = 10
  private status = 2
  private orderStatics = {} as any
  created() {
    this.init()
  }
  init() {
    this.$nextTick(() => {
      this.getBusinessData()
      this.getOrderStatisticsData()
      this.getOverStatisticsData()
      this.getSetMealStatisticsData()
    })
  }
  // 获取营业数据
  async getBusinessData() {
    const data = await getBusinessData()
    this.overviewData = data.data.data
  }
  // 获取今日订单
  async getOrderStatisticsData() {
    const data = await getOrderData()
    this.orderviewData = data.data.data
  }
  // 获取菜品总览数据
  async getOverStatisticsData() {
    const data = await getOverviewDishes()
    this.dishesData = data.data.data
  }
  // 获取套餐总览数据
  async getSetMealStatisticsData() {
    const data = await getSetMealStatistics()
    this.setMealData = data.data.data
  }
  //获取待处理，待派送，派送中数量
  getOrderListBy3Status() {
    getOrderListBy({})
      .then((res) => {
        if (res.data.code === 1) {
          this.orderStatics = res.data.data
        } else {
          this.$message.error(res.data.msg)
        }
      })
      .catch((err) => {
        this.$message.error('请求出错了：' + err.message)
      })
  }
}
</script>

<style lang="scss">
.dashboard-welcome {
  min-height: 146px;
  margin-bottom: 20px;
  padding: 30px 36px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  overflow: hidden;
  position: relative;
  border-radius: 16px;
  background: linear-gradient(120deg, #0b4f9f 0%, #1468d4 62%, #2c8de8 100%);
  box-shadow: 0 12px 30px rgba(20, 104, 212, .18);
  color: #fff;
}
.dashboard-welcome::after { content: ''; position: absolute; width: 260px; height: 260px; right: -70px; top: -115px; border: 45px solid rgba(255,255,255,.08); border-radius: 50%; }
.dashboard-welcome__tag { font-size: 12px; letter-spacing: 2px; color: #bcdcff; }
.dashboard-welcome h1 { margin: 10px 0 8px; font-size: 26px; line-height: 1.3; }
.dashboard-welcome p { margin: 0; color: #d8eaff; font-size: 14px; }
.dashboard-welcome__scene { z-index: 1; display: flex; align-items: center; gap: 14px; min-width: 200px; padding: 16px 20px; border: 1px solid rgba(255,255,255,.18); border-radius: 13px; background: rgba(255,255,255,.1); }
.dashboard-welcome__scene i { font-size: 30px; color: #fff; }
.dashboard-welcome__scene strong, .dashboard-welcome__scene span { display: block; }
.dashboard-welcome__scene strong { margin-bottom: 5px; font-size: 15px; }
.dashboard-welcome__scene span { color: #cde4ff; font-size: 12px; }
</style>
