import { repetitionOrder } from '@/pages/api/api.js'

/**
 * 恢复原订单，并回到原订单所属餐厅的点餐页。
 * 餐厅由服务端根据订单快照返回，不能用当前已选择的餐厅替代。
 */
export async function reorderToRestaurant (orderId) {
  uni.showLoading({ title: '正在恢复订单', mask: true })
  try {
    const res = await repetitionOrder(orderId)
    if (res.code !== 1 || !res.data || !res.data.id) {
      throw new Error(res.msg || '无法获取原订单的餐厅信息')
    }
    uni.setStorageSync('currentRestaurant', res.data)
    uni.redirectTo({
      url: `/pages/index/index?restaurantId=${res.data.id}`
    })
  } finally {
    uni.hideLoading()
  }
}
