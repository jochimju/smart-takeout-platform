<template>
  <view class="page">
    <view v-if="!selectMode" class="hero">
      <view class="title">{{ packageInfo ? packageInfo.salePrice + '元购买红包包' : '购买红包包' }}</view>
      <view class="sub" v-if="packageInfo">支付成功后获得 {{ packageInfo.packetCount }} 张 ¥{{ packageInfo.packetAmount }} 红包，有效期 {{ packageInfo.validMonths }} 个月</view>
      <button class="buy" :disabled="paying" @click="buy">{{ paying ? '正在支付...' : '立即购买 ¥' + (packageInfo ? packageInfo.salePrice : '') }}</button>
    </view>
    <view class="heading">{{ selectMode ? `选择红包（共 ${packets.length} 张）` : '可用红包' }}</view>
    <view v-if="selectMode" class="no-use" @click="finishSelection(null)"><text>不使用红包</text><text v-if="!selectedId" class="selected">✓</text></view>
    <view v-for="packet in packets" :key="packet.id" class="packet" @click="finishSelection(packet)">
      <view><text class="amount">¥{{ packet.amount }}</text><text class="name">智餐外卖红包</text></view>
      <view class="expiry">有效至 {{ packet.expireTime }}<text v-if="selectMode && selectedId === packet.id" class="selected">已选择 ✓</text></view>
    </view>
    <view v-if="!packets.length" class="empty">暂无可用红包，购买后会自动发放 5 张</view>
  </view>
</template>

<script>
import { getRedPacketPackages, getMyRedPackets, createRedPacketPurchase, createRedPacketPayment, confirmMockRedPacketPayment } from '../api/api.js'
import { executePayment } from '@/utils/payment-flow'

export default {
  data () { return { packets: [], packageId: null, packageInfo: null, paying: false, selectMode: false, selectedId: null } },
  onLoad (options) { this.selectMode = options && options.select === '1'; this.selectedId = options && options.selectedId ? Number(options.selectedId) : null },
  onShow () { this.load() },
  methods: {
    async load () {
      try {
        const [packages, packets] = await Promise.all([getRedPacketPackages(), getMyRedPackets()])
        // 售价、张数和有效期都以服务端配置为准，避免前端写死后与实际售价不一致
        this.packageInfo = (packages.data && packages.data[0]) || null
        this.packageId = this.packageInfo && this.packageInfo.id
        this.packets = packets.data || []
        uni.setStorageSync('redPacketAvailable', this.packets)
      } catch (error) { uni.showToast({ title: error.msg || '红包加载失败', icon: 'none' }) }
    },
    async buy () {
      if (!this.packageId || this.paying) return
      this.paying = true
      try {
        const order = await createRedPacketPurchase({ packageId: this.packageId })
        if (!order || Number(order.code) !== 1 || !order.data) throw new Error((order && order.msg) || '红包购买订单创建失败')
        const purchaseOrderNo = order.data.purchaseOrderNo
        const payment = await createRedPacketPayment({ purchaseOrderNo })
        await executePayment(payment, {
          amount: order.data.payAmount,
          showModal: options => uni.showModal(options),
          requestPayment: options => uni.requestPayment(options),
          confirmMock: () => confirmMockRedPacketPayment({ purchaseOrderNo }),
        })
        uni.showToast({ title: '支付已提交，到账后可查看红包', icon: 'none' })
        await this.load()
      } catch (error) { uni.showToast({ title: error.msg || error.message || '购买未完成', icon: 'none' }) } finally { this.paying = false }
    },
    finishSelection (packet) {
      if (!this.selectMode) return
      uni.setStorageSync('redPacketSelection', { changed: true, id: packet ? packet.id : null, amount: packet ? Number(packet.amount || 0) : 0 })
      uni.navigateBack()
    },
  },
}
</script>

<style scoped>
.page { min-height: 100vh; background: #f6f7fb; padding: 24rpx; box-sizing: border-box; }
.hero { padding: 36rpx; border-radius: 20rpx; color: #fff; background: linear-gradient(135deg, #f34b4b, #c5162f); }.title { font-size: 38rpx; font-weight: 700; }.sub { margin: 18rpx 0 26rpx; font-size: 25rpx; opacity: .9; }.buy { border-radius: 40rpx; background: #fff; color: #c5162f; font-size: 29rpx; font-weight: 700; }.heading { margin: 34rpx 6rpx 16rpx; font-size: 32rpx; font-weight: 700; }.packet, .no-use { margin-bottom: 18rpx; padding: 26rpx; border-radius: 16rpx; background: #fff; }.packet { border-left: 10rpx solid #e73842; }.amount { margin-right: 20rpx; color: #e73842; font-size: 44rpx; font-weight: 700; }.name { font-size: 28rpx; }.expiry { margin-top: 14rpx; color: #909399; font-size: 24rpx; }.empty { padding: 80rpx 0; color: #909399; text-align: center; }.no-use { display: flex; justify-content: space-between; font-size: 29rpx; }.selected { float: right; margin-left: 18rpx; color: #e73842; font-weight: 700; }
</style>
