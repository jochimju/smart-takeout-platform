<template>
  <view class="page">
    <view class="hero">
      <text class="title">选择餐厅</text>
      <text class="subtitle">请选择要点餐的食堂</text>
    </view>
    <view class="list">
      <view v-for="(restaurant, index) in restaurants" :key="index" v-if="restaurant.name.length > 0" class="card" :class="{ 'is-resting': restaurant.businessStatus !== 'OPEN' }" @click="enterRestaurant(restaurant)">
        <view class="badge" :class="restaurant.businessStatus === 'OPEN' ? 'open' : 'closed'">
          {{ restaurant.businessStatus === 'OPEN' ? '营业中' : '餐厅休息' }}
        </view>
        <view class="content">
          <text class="name">{{ restaurant.name }}</text>
          <text class="location">{{ restaurant.location }}</text>
          <text class="description">{{ restaurant.description || '校园餐饮服务' }}</text>
          <text class="meal-time">{{ restaurant.mealPeriodText || '用餐时间：00:00-24:00' }}</text>
        </view>
        <text class="arrow">›</text>
      </view>
    </view>
    <view v-if="loading" class="loading">正在更新餐厅信息…</view>
    <view v-if="!loading && restaurants.length === 0" class="empty">暂无可用餐厅</view>
    <view v-if="showProfileSetup" class="profile-setup-mask">
      <view class="profile-setup-card">
        <view class="profile-setup-title">完善头像和昵称</view>
        <view class="profile-setup-tip">登录后即可选择餐厅并开始点餐</view>
        <button class="profile-avatar-button" open-type="chooseAvatar" @chooseavatar="onChooseAvatar">
          <image class="profile-avatar" :src="profileAvatar" mode="aspectFill"></image>
          <view class="profile-avatar-edit">选择头像</view>
        </button>
        <input class="profile-nickname-input" type="nickname" placeholder="请输入昵称" :value="profileNickname" @input="onNicknameInput" @blur="onNicknameInput" />
        <button class="profile-login-button" @click="confirmProfileLogin">登录后选择餐厅</button>
      </view>
    </view>
  </view>
</template>

<script>
import { mapMutations, mapState } from 'vuex'
import { userLogin, getRestaurantList, getRestaurantDetail, getShoppingCartList, delShoppingCart } from '../api/api.js'

export default {
  data () {
    const savedProfile = uni.getStorageSync('smartDiningUserProfile') || {}
    return {
      restaurants: [],
      loading: false,
      showProfileSetup: false,
      profileAvatar: savedProfile.avatarUrl || '../../static/center.png',
      profileNickname: savedProfile.nickName || ''
    }
  },
  onShow () {
    this.showProfileSetup = !this.token
    // 未登录时不发请求，否则必然 401；登录成功后会主动加载
    if (this.token) this.loadRestaurants()
  },
  computed: {
    ...mapState(['token'])
  },
  methods: {
    ...mapMutations(['setToken', 'setBaseUserInfo']),
    loadRestaurants () {
      this.loading = true
      getRestaurantList()
        .then(res => { this.restaurants = res && res.code === 1 ? (res.data || []) : [] })
        .catch(() => { this.restaurants = []; uni.showToast({ title: '餐厅列表加载失败，请稍后重试', icon: 'none' }) })
        .finally(() => { this.loading = false })
    },
    async enterRestaurant (restaurant) {
      if (!this.token) {
        this.showProfileSetup = true
        return
      }
      // The server is the source of truth: the list may have been fetched before a meal window ended.
      const latest = await this.getLatestRestaurant(restaurant)
      if (!latest || latest.businessStatus !== 'OPEN') {
        uni.showToast({ title: '餐厅休息中，请在用餐时间内点餐', icon: 'none' })
        return
      }
      if (!await this.confirmRestaurantSwitch(latest)) return
      uni.setStorageSync('currentRestaurant', latest)
      uni.redirectTo({ url: `/pages/index/index?restaurantId=${latest.id}` })
    },
    async getLatestRestaurant (restaurant) {
      try {
        const res = await getRestaurantDetail(restaurant.id)
        return res && res.code === 1 && res.data ? res.data : null
      } catch (e) { return null }
    },
    async confirmRestaurantSwitch (target) {
      const current = uni.getStorageSync('currentRestaurant') || {}
      if (!current.id || Number(current.id) === Number(target.id)) return true
      try {
        const cart = await getShoppingCartList({})
        if (!cart || cart.code !== 1 || !(cart.data || []).length) return true
      } catch (e) { return true }
      return new Promise(resolve => {
        uni.showModal({
          title: '切换餐厅',
          content: '切换餐厅会清空当前购物车，是否继续？',
          success: async ({ confirm }) => {
            if (!confirm) return resolve(false)
            try { await delShoppingCart(); resolve(true) }
            catch (e) { uni.showToast({ title: '购物车清空失败，请稍后重试', icon: 'none' }); resolve(false) }
          }
        })
      })
    },
    loginSync () {
      return new Promise((resolve, reject) => {
        uni.login({
          success: (loginRes) => {
            if (loginRes.errMsg === 'login:ok' && loginRes.code) resolve(loginRes.code)
            else reject(new Error('微信登录未返回有效凭证'))
          },
          fail: reject
        })
      })
    },
    onChooseAvatar (event) {
      if (event && event.detail && event.detail.avatarUrl) this.profileAvatar = event.detail.avatarUrl
    },
    onNicknameInput (event) {
      this.profileNickname = event && event.detail ? event.detail.value : ''
    },
    async confirmProfileLogin () {
      const profile = {
        nickName: (this.profileNickname || '').trim() || '微信用户',
        avatarUrl: this.profileAvatar || '../../static/center.png'
      }
      try {
        uni.showLoading({ title: '登录中…', mask: true })
        const code = await this.loginSync()
        const result = await userLogin({ code })
        if (!result || result.code !== 1 || !result.data || !result.data.token) {
          throw new Error((result && result.msg) || '登录失败')
        }
        uni.setStorageSync('smartDiningUserProfile', profile)
        this.setBaseUserInfo(profile)
        this.setToken(result.data.token)
        this.showProfileSetup = false
        this.loadRestaurants()
      } catch (error) {
        uni.showToast({ title: (error && error.message) || '微信登录失败，请重试', icon: 'none' })
      } finally {
        uni.hideLoading()
      }
    }
  }
}
</script>

<style lang="scss" scoped>
.page { min-height: 100vh; background: #f7f8fa; padding: 48rpx 28rpx; box-sizing: border-box; }
.hero { padding: 32rpx 12rpx 44rpx; }
.title { display: block; font-size: 52rpx; font-weight: 700; color: #222; }
.subtitle { display: block; margin-top: 12rpx; font-size: 28rpx; color: #888; }
.list { display: flex; flex-direction: column; gap: 22rpx; }
.card { display: flex; align-items: center; min-height: 152rpx; padding: 26rpx; border-radius: 24rpx; background: #fff; box-shadow: 0 8rpx 22rpx rgba(0,0,0,.05); }
.card.is-resting { opacity: .68; }
.badge { width: 88rpx; height: 88rpx; border-radius: 18rpx; display: flex; align-items: center; justify-content: center; font-size: 22rpx; color: #fff; flex-shrink: 0; }
.open { background: #19b36b; }
.closed { background: #aeb5bd; }
.content { margin-left: 24rpx; flex: 1; min-width: 0; }
.name { display: block; font-size: 34rpx; font-weight: 600; color: #222; }
.location,.description,.meal-time { display: block; margin-top: 8rpx; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; font-size: 24rpx; color: #7d8790; }
.meal-time { color: #b07828; }
.arrow { font-size: 52rpx; color: #c2c7cc; font-weight: 200; }
.loading,.empty { text-align: center; color: #999; padding: 80rpx 0; font-size: 28rpx; }
.profile-setup-mask { position: fixed; z-index: 1000; inset: 0; display: flex; align-items: center; justify-content: center; padding: 48rpx; box-sizing: border-box; background: rgba(0, 0, 0, .45); }
.profile-setup-card { width: 100%; padding: 48rpx 36rpx; box-sizing: border-box; border-radius: 24rpx; background: #fff; text-align: center; }
.profile-setup-title { color: #222; font-size: 36rpx; font-weight: 600; }
.profile-setup-tip { margin: 12rpx 0 32rpx; color: #888; font-size: 26rpx; }
.profile-avatar-button { position: relative; width: 154rpx; height: 154rpx; margin: 0 auto 28rpx; padding: 0; overflow: visible; border: 0; border-radius: 77rpx; background: transparent; line-height: 1; }
.profile-avatar-button::after, .profile-login-button::after { border: 0; }
.profile-avatar { width: 154rpx; height: 154rpx; border-radius: 77rpx; background: #f1f3f5; }
.profile-avatar-edit { position: absolute; right: -16rpx; bottom: -4rpx; padding: 4rpx 10rpx; border-radius: 16rpx; background: #1677ff; color: #fff; font-size: 20rpx; }
.profile-nickname-input { height: 82rpx; margin-bottom: 24rpx; padding: 0 24rpx; box-sizing: border-box; border: 1rpx solid #dce3ec; border-radius: 14rpx; background: #f8fafc; color: #222; font-size: 28rpx; text-align: left; }
.profile-login-button { height: 86rpx; border-radius: 43rpx; background: #1677ff; color: #fff; font-size: 30rpx; line-height: 86rpx; }
</style>
