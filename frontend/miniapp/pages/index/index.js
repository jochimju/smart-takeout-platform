import navBar from "../common/Navbar/navbar.vue" //标题
import Phone from "@/components/uni-phone/index.vue" //拨打电话
import popMask from "./components/popMask.vue" //规格
import popCart from "./components/popCart.vue" //购物车弹出层
import dishDetail from "./components/dishDetail.vue" //菜品详情
import {
  // 智餐外卖相关的接口
  userLogin,
  // 获取购物车集合
  getShoppingCartList,
  // 新的购物车添加逻辑接口
  newAddShoppingCartAdd,
  // 新的购物车减少接口
  newShoppingCartSub,
  // 清空购物车
  delShoppingCart,
  // 此接口为首页查询套餐详情展示的接口
  querySetmealDishById,
  // 获取店铺信息
  getShopStatus,
  // 获取店铺联系方式
  getShopPhone,
  querySeckillActivities,
  getRestaurantCategories,
  getRestaurantDishes,
  getRestaurantSetmeals,
  getRestaurantDetail,
  switchRestaurantCart,
} from "../api/api.js"
import { mapState, mapMutations } from "vuex"
import { baseUrl } from "../../utils/env"
import { call } from "../../utils/index.js"
export default {
  data () {
    return {
      title: "Hello",
      // 去结算部分
      openOrderCartList: false,
      // 存放左侧滚动区域菜品分类数组
      typeListData: [],
      dishListData: [],
      // 存放右侧对应菜品每个菜名称的数组
      dishListItems: [],
      dishDetailes: {},
      openDetailPop: false,
      openMoreNormPop: false,
      moreNormDataes: null,
      tableInfo: null,
      moreNormDishdata: null,
      moreNormdata: null,
      // 套餐中查询到的菜品名称
      dishMealData: null,
      openTablePeoPleNumber: 1,
      orderData: 0,
      // 选中左侧菜品的索引
      typeIndex: 0,
      // 控制菜品详情显示
      openTablePop: false,
      // 规格有关的数组
      flavorDataes: [],
      // 加入购物车数量
      orderDishNumber: 0,
      // 菜品金额
      orderDishPrice: 0,
      params: {
        shopId: "f3deb",
        storeId: "1282344676983062530",
        tableId: "1282346960773238786",
      },
      // 添加一个右侧number更新以后重新刷新接口的id --- 这个id来自左侧菜品分类的id
      rightIdAndType: {},
      phoneData: "",
      tablewareNumber: 0,
      shopStatus: null,
      scrollTop: 0,
      menuHeight: 0, // 左边菜单的高度
      menuItemHeight: 0, // 左边菜单item的高度
      itemId: "", // 栏目右边scroll-view用于滚动的id
      arr: [],
      restaurantId: null,
      restaurantName: '',
      seckillTimer: null,
      showProfileSetup: false,
      restaurantAccessChecked: false,
      profileAvatar: (uni.getStorageSync('smartDiningUserProfile') || {}).avatarUrl || '../../static/center.png',
      profileNickname: (uni.getStorageSync('smartDiningUserProfile') || {}).nickName || '',
    }
  },
  //   组件
  components: {
    navBar,
    Phone,
    popMask,
    popCart,
    dishDetail,
  },
  //   计算属性
  computed: {
    // 购物车信息列表
    orderListDataes: function () {
      return this.orderListData()
    },
    loaddingSt: function () {
      return this.lodding()
    },
    // 计算购物车清单
    orderAndUserInfo: function () {
      let orderData = []
      Array.isArray(this.orderListDataes) &&
        this.orderListDataes.forEach((n, i) => {
          let userData = {}
          userData.nickName = n.name ?? ""
          userData.avatarUrl = n.image ?? ""
          userData.dishList = [n]
          const num = orderData.findIndex(
            (o) => o.nickName == userData.nickName
          )
          if (num != -1) {
            orderData[num].dishList.push(n)
          } else {
            orderData.push(userData)
          }
        })
      return orderData
    },
    ht: function () {
      return (
        uni.getMenuButtonBoundingClientRect().top +
        uni.getMenuButtonBoundingClientRect().height +
        7
      )
    },
  },

  onReady () {
    this.getMenuItemTop()
  },
  onLoad (options) {
    const savedRestaurant = uni.getStorageSync('currentRestaurant') || {}
    const routeRestaurantId = options && options.restaurantId
    this.restaurantId = Number(routeRestaurantId || savedRestaurant.id) || null
    this.restaurantName = savedRestaurant.name || ''
    uni.onNetworkStatusChange(function (res) {
      if (res.isConnected == false) {
        uni.navigateTo({
          url: "/pages/nonet/index",
        })
      }
    })
    if (options) {
      if (!options.status && !options.formOrder) this.getData()
    }
  },
  onShow () {
    if (this.token()) {
      this.ensureRestaurantOpen()
    }
  },
  methods: {
    //   vuex储存信息
    ...mapMutations([
      "setShopInfo", //设置店铺信息
      "setShopPhone", //设置电话
      "setShopStatus", //设置店铺状态
      "initdishListMut", //设置购物车订单
      "setStoreInfo",
      "setBaseUserInfo", //设置用户基本信息
      "setLodding",
      "setToken", //设置token
      "setCurrentRestaurant",
    ]),
    // 从vuex信息
    ...mapState([
      "shopInfo", //店铺信息
      "shopPhone", //电话
      "orderListData",
      "baseUserInfo", //用户信息
      "lodding",
      "token", //token
      "currentRestaurant",
    ]),
    loginSync () {
      return new Promise((resolve, reject) => {
        uni.login({
          success: (loginRes) => {
            if (loginRes.errMsg === "login:ok") {
              resolve(loginRes.code)
            }
          },
        })
      })
    },
    // 获取用户信息
    getData () {
      let res = wx.getMenuButtonBoundingClientRect()
      let _this = this
      // 获取店铺状态
      this.getShopInfo()
      this.selectHeight = res.height
      if (this.token() === "") {
        this.showProfileSetup = true
      }
    },
    onChooseAvatar (event) {
      if (event && event.detail && event.detail.avatarUrl) this.profileAvatar = event.detail.avatarUrl
    },
    onNicknameInput (event) {
      this.profileNickname = event && event.detail ? event.detail.value : ''
    },
    async confirmProfileLogin () {
      const profile = { nickName: (this.profileNickname || '').trim() || '微信用户', avatarUrl: this.profileAvatar || '../../static/center.png' }
      uni.setStorageSync('smartDiningUserProfile', profile)
      this.setBaseUserInfo(profile)
      try {
        uni.showLoading({ title: '登录中…', mask: true })
        const code = await this.loginSync()
        const result = await userLogin({ code })
        if (!result || result.code !== 1 || !result.data || !result.data.token) throw { msg: result && result.msg || '登录失败' }
        this.setToken(result.data.token)
        this.showProfileSetup = false
        this.init()
      } catch (error) {
        uni.showToast({ title: error && error.msg || '微信登录失败，请重试', icon: 'none' })
      } finally { uni.hideLoading() }
    },

    async init () {
      // 获取菜品和套餐分类接口
      if (this.typeIndex !== 0) {
        this.typeIndex = 0
      }

      // 获取店铺联系方式
      await this.getShopInfo()
      this.getShopPhone()
      if (!this.restaurantId) return
      const categoryRequest = getRestaurantCategories(this.restaurantId)
      categoryRequest.then(async (res) => {
        if (res && res.code === 1) {
          const categories = [...res.data]
          // 限时秒杀是每个餐厅统一的入口，固定放在首位；是否有活动由点击后按当前餐厅查询决定。
          if (!categories.some(item => item.name === '限时秒杀套餐')) {
            categories.unshift({ id: '__seckill__', name: '限时秒杀套餐', type: 2, isSeckill: true })
          }
          categories.sort((a, b) => Number(b.name === '限时秒杀套餐') - Number(a.name === '限时秒杀套餐'))
          this.typeListData = categories
          if (categories.length > 0) {
            this.getDishListDataes(categories[this.typeIndex || 0])
          }
        }
      })
      // 调用一次购物车集合---初始化
      this.getTableOrderDishListes()
    },
    // 点击左边的栏目切换
    async swichMenu (params, index) {
      if (this.arr.length == 0) {
        await this.getMenuItemTop()
      }
      if (index == this.typeIndex) return
      this.$nextTick(function () {
        this.typeIndex = index
        this.leftMenuStatus(index)
      })
      this.getDishListDataes(params, index)
    },
    // 获取一个目标元素的高度
    getElRect (elClass, dataVal) {
      new Promise((resolve, reject) => {
        const query = uni.createSelectorQuery().in(this)
        query
          .select("." + elClass)
          .fields(
            {
              size: true,
            },
            (res) => {
              // 如果节点尚未生成，res值为null，循环调用执行
              if (!res) {
                setTimeout(() => {
                  this.getElRect(elClass)
                }, 10)
                return
              }
              this[dataVal] = res.height
              resolve()
            }
          )
          .exec()
      })
    },
    // 设置左边菜单的滚动状态
    async leftMenuStatus (index) {
      this.typeIndex = index
      // 如果为0，意味着尚未初始化
      if (this.menuHeight == 0 || this.menuItemHeight == 0) {
        await this.getElRect("menu-scroll-view", "menuHeight")
        await this.getElRect("type_item", "menuItemHeight")
      }
      // 将菜单活动item垂直居中
      this.scrollTop =
        index * this.menuItemHeight +
        this.menuItemHeight / 2 -
        this.menuHeight / 2
    },
    // 获取右边菜单每个item到顶部的距离
    getMenuItemTop () {
      new Promise((resolve) => {
        let selectorQuery = uni.createSelectorQuery()
        selectorQuery
          .selectAll(".class-item")
          .boundingClientRect((rects) => {
            // 如果节点尚未生成，rects值为[](因为用selectAll，所以返回的是数组)，循环调用执行
            if (!rects.length) {
              setTimeout(() => {
                this.getMenuItemTop()
              }, 10)
              return
            }
          })
          .exec()
      })
    },
    // 获取菜品列表
    async getDishListDataes (params, index) {
      this.rightIdAndType = {}
      this.rightIdAndType = {
        id: params.id,
        type: params.type,
      }
      const param = {
        categoryId: params.id,
      }
      if (params.name === '限时秒杀套餐' || params.isSeckill) {
        if (this.seckillTimer) clearInterval(this.seckillTimer)
        try {
          // 不按当前餐厅过滤：所有餐厅的秒杀入口共用同一份活动列表
          const success = await querySeckillActivities()
          if (success && success.code === 1) {
            this.dishListData = (success.data || []).map(item => ({
              ...item, id: item.setmealId, name: item.setmealName, description: item.setmealName,
              image: this.resolveImage(item.image),
              price: Number(item.seckillPrice), type: 2, newCardNumber: 0,
              seckillActivityId: item.id, seckillCountdown: '', seckillEnded: false,
              seckillNotStarted: false, seckillButtonText: '立即抢购',
            }))
            this.refreshSeckillCountdown()
            this.seckillTimer = setInterval(() => this.refreshSeckillCountdown(), 1000)
          }
        } catch (e) { this.dishListData = [] }
        this.typeIndex = index
        this.setOrderNum()
        return
      }
      if (this.seckillTimer) {
        clearInterval(this.seckillTimer)
        this.seckillTimer = null
      }
      // type：1是菜品、2是套餐
      if (params.type === 1) {
        const dishRequest = getRestaurantDishes(this.restaurantId, params.id)
        await dishRequest
          .then((res) => {
            if (res && res.code === 1) {
              // 添加一个字段去实时更新加入购物车number数量 ----- newCardNumber
              this.dishListData =
                res.data &&
                res.data.map((obj) => ({
                  ...obj,
                  image: this.resolveImage(obj.image),
                  type: 1,
                  newCardNumber: 0,
                }))
            }
          })
          .catch((err) => { })
      } else {
        // 套餐
        const setmealRequest = getRestaurantSetmeals(this.restaurantId, params.id)
        await setmealRequest
          .then((success) => {
            if (success && success.code === 1) {
              // dishListItems被转换数组---原始this.dishListData
              this.dishListData =
                success.data &&
                success.data.map((obj) => ({
                  ...obj,
                  image: this.resolveImage(obj.image),
                  type: 2,
                  newCardNumber: 0,
                }))
            }
          })
          .catch((err) => { })
      }
      this.typeIndex = index
      this.setOrderNum()
    },
    async ensureRestaurantOpen () {
      if (!this.restaurantId) {
        uni.showToast({ title: '请先选择餐厅', icon: 'none' })
        uni.redirectTo({ url: '/pages/restaurant/index' })
        return false
      }
      try {
        const res = await getRestaurantDetail(this.restaurantId)
        const restaurant = res && res.code === 1 ? res.data : null
        if (!restaurant || restaurant.businessStatus !== 'OPEN') {
          uni.showToast({ title: '餐厅休息中，请在用餐时间内点餐', icon: 'none' })
          uni.redirectTo({ url: '/pages/restaurant/index' })
          return false
        }
        uni.setStorageSync('currentRestaurant', restaurant)
        this.restaurantName = restaurant.name || ''
        this.restaurantAccessChecked = true
        this.init()
        return true
      } catch (e) {
        uni.showToast({ title: '餐厅状态获取失败，请稍后重试', icon: 'none' })
        uni.redirectTo({ url: '/pages/restaurant/index' })
        return false
      }
    },
    refreshSeckillCountdown () {
      ;(this.dishListData || []).forEach(item => {
        if (!item.seckillActivityId) return
        const now = Date.now()
        const begin = new Date(item.beginTime).getTime()
        const end = new Date(item.endTime).getTime()
        const beforeStart = now < begin
        const seconds = Math.max(0, Math.floor(((beforeStart ? begin : end) - now) / 1000))
        const pad = value => String(value).padStart(2, '0')
        item.seckillCountdown = seconds > 0
          ? `${beforeStart ? '距开抢 ' : '距结束 '}${pad(Math.floor(seconds / 3600))}:${pad(Math.floor(seconds % 3600 / 60))}:${pad(seconds % 60)}`
          : '活动已结束'
        item.seckillEnded = !beforeStart && seconds <= 0
        item.seckillNotStarted = beforeStart
        item.seckillButtonText = item.seckillEnded ? '已结束' : (item.seckillNotStarted ? '即将开始' : (item.stock > 0 ? '立即抢购' : '已抢光'))
      })
    },
    seckillBuy (item) {
      if (item.seckillEnded || item.seckillNotStarted || item.stock <= 0) {
        uni.showToast({ title: item.seckillEnded ? '活动已结束' : (item.seckillNotStarted ? '活动尚未开始' : '已抢光'), icon: 'none' })
        return
      }
      uni.setStorageSync('seckillCheckout', {
        activityId: item.seckillActivityId, setmealId: item.id,
        canteenId: this.restaurantId,
        requestId: `${Date.now().toString(36)}_${Math.random().toString(36).slice(2)}${Math.random().toString(36).slice(2)}`,
        name: item.name, image: item.image, amount: Number(item.price), number: 1,
      })
      uni.navigateTo({ url: `/pages/order/index?seckill=1&restaurantId=${this.restaurantId}` })
    },
    // 获取首页店铺信息
    async getShopInfo () {
      await getShopStatus()
        .then((res) => {
          if (res && res.code === 1) {
            this.shopStatus = res.data
            this.setShopStatus(this.shopStatus)
          }
        })
        .catch((err) => {})
    },
    // 获取店铺电话
    async getShopPhone () {
      this.phoneData = '400-618-4000'
      this.setShopPhone(this.phoneData)
    },
    // 菜单图片既支持对象存储旧地址，也支持本地静态菜单图片。
    resolveImage (image) {
      if (!image) return '../../static/imgDefault.png'
      if (/^https?:\/\//i.test(image)) return image
      // 小程序把菜单图随安装包携带，避免开发者工具或真机拦截本机图片端口。
      if (image.startsWith('/dishes/') || image.startsWith('/setmeals/')) {
        return `/static/menu${image.replace(/\.png$/i, '.jpg')}`
      }
      if (image.startsWith('/')) return `${baseUrl}${image}`
      return `${baseUrl}/common/download?name=${image}`
    },
    // 获取购物车订单列表
    async getTableOrderDishListes () {
      // 调用获取购物车集合接口
      await getShoppingCartList({})
        .then((res) => {
          if (res.code === 1) {
            this.initdishListMut(res.data)
            this.computOrderInfo()
          }
        })
        .catch((err) => { })
    },
    // 去订单页面
    goOrder () {
      uni.navigateTo({
        url: `/pages/order/index?restaurantId=${this.restaurantId}`,
      })
    },
    // 加菜 - 添加菜品
    async addDishAction (item, form) {
      if (this.shopStatus !== 1) {
        uni.showToast({ title: '当前餐厅已打烊', icon: 'none' })
        return
      }
      // 规格
      if (
        this.openMoreNormPop &&
        (!this.flavorDataes || this.flavorDataes.length <= 0)
      ) {
        uni.showToast({
          title: "请选择规格",
          icon: "none",
        })
        return false
      }
      this.openMoreNormPop = false
      // 实时更新obj.newCardNumber新添加的字段----加入购物车数量number
      this.tablewareNumber++
      this.dishDetailes.dishNumber++
      if (
        this.orderListDataes &&
        !this.orderListDataes.some((n) => n.id == item.dishId) &&
        this.flavorDataes.length > 0
      ) {
        item.flavorRemark = JSON.stringify(this.flavorDataes)
      }
      // 有sort字段是菜品
      let dishFlavorDatas = ""
      let flavorRemark = []
      if (item.flavorRemark) {
        flavorRemark = JSON.parse(item.flavorRemark)
      }
      if (item.dishFlavor !== "" && item.dishFlavor) {
        dishFlavorDatas = item.dishFlavor
      } else if (flavorRemark.length > 0) {
        dishFlavorDatas = flavorRemark[0]
      } else {
        dishFlavorDatas = null
      }
      let params = {
        dishFlavor: dishFlavorDatas,
        canteenId: this.restaurantId,
      }
      if (item.type === 1) {
        params = {
          ...params,
          dishId: item.id,
        }
      } else if (item.type === 2) {
        params = {
          ...params,
          setmealId: item.id,
        }
      } else if (form === "购物车") {
        if (item.dishId) {
          params = {
            ...params,
            dishId: item.dishId,
          }
        } else {
          params = {
            ...params,
            setmealId: item.setmealId,
          }
        }
      }
      newAddShoppingCartAdd(params)
        .then((res) => {
          if (res.code === 1) {
            // 调用一次购物车集合---初始化
            this.getTableOrderDishListes()
            // 重新调取刷新右侧具体菜品列表
            this.getDishListDataes(this.rightIdAndType)
            this.flavorDataes = []
          }
        })
        .catch((err) => {
          const message = err && err.msg ? err.msg : ''
          if (message.indexOf('CART_RESTAURANT_CONFLICT') !== -1) {
            uni.showModal({
              title: '切换餐厅购物车',
              content: '购物车中已有其他餐厅商品，是否清空后加入当前餐厅？',
              success: ({ confirm }) => {
                if (confirm) {
                  switchRestaurantCart(params).then(() => {
                    this.getTableOrderDishListes()
                    this.getDishListDataes(this.rightIdAndType)
                  })
                }
              }
            })
          } else {
            uni.showToast({ title: '加入购物车失败', icon: 'none' })
          }
        })
    },
    // 加入购物车
    addShop (item) {
      this.dishDetailes = item
      this.addDishAction(item, "普通")
    },
    // 减菜 - 添加菜品
    async redDishAction (item, form) {
      // 实时更新obj.newCardNumber新添加的字段----加入购物车数量number
      this.tablewareNumber--
      this.dishDetailes.dishNumber--
      let dishFlavorDatas = ""
      let flavorRemark = []
      if (item.flavorRemark) {
        flavorRemark = JSON.parse(item.flavorRemark)
      }
      if (item.dishFlavor !== "" && item.dishFlavor) {
        dishFlavorDatas = item.dishFlavor
      } else if (flavorRemark.length > 0) {
        dishFlavorDatas = flavorRemark[0]
      } else {
        dishFlavorDatas = null
      }
      let params = {
        dishFlavor: dishFlavorDatas,
      }
      if (item.type === 1) {
        params = {
          ...params,
          dishId: item.id,
        }
      } else if (item.type === 2) {
        params = {
          ...params,
          setmealId: item.id,
        }
      } else if (form === "购物车") {
        if (item.dishId) {
          params = {
            ...params,
            dishId: item.dishId,
          }
        } else {
          params = {
            ...params,
            setmealId: item.setmealId,
          }
        }
      }
      await newShoppingCartSub(params)
        .then((res) => {
          if (res.code === 1) {
            // 调用一次购物车集合---初始化
            this.getTableOrderDishListes()
            // 重新调取刷新右侧具体菜品列表
            this.getDishListDataes(this.rightIdAndType)
          }
        })
        .catch((err) => { })
    },
    // 清空购物车
    clearCardOrder () {
      delShoppingCart()
        .then((res) => {
          this.openOrderCartList = false
          // 调用一次购物车集合---初始化
          this.getTableOrderDishListes()
          // 重新调取刷新右侧具体菜品列表
          this.getDishListDataes(this.rightIdAndType)
        })
        .catch((err) => { })
    },
    // 打开菜品牌详情
    openDetailHandle (item) {
      this.dishDetailes = item
      if (item.type === 2) {
        querySetmealDishById({
          id: item.id,
        })
          .then((res) => {
            if (res.code === 1) {
              this.openDetailPop = true
              this.dishMealData = (res.data || []).map(item => ({
                ...item,
                image: this.resolveImage(item.image),
              }))
            }
          })
          .catch((err) => { })
      } else {
        this.openDetailPop = true
      }
    },
    // 关闭菜品详情
    dishClose () {
      this.openDetailPop = false
    },
    // 多规格数据处理
    moreNormDataesHandle (item) {
      this.flavorDataes.splice(0)
      this.moreNormDishdata = item
      this.openDetailPop = false
      this.openMoreNormPop = true
      this.moreNormdata = item.flavors.map((obj) => ({
        ...obj,
        value: JSON.parse(obj.value),
      }))
      this.moreNormdata.forEach((item) => {
        if (item.value && item.value.length > 0) {
          this.flavorDataes.push(item.value[0])
        }
      })
    },
    // 选规格 处理一行只能选择一种
    checkMoreNormPop (val) {
      let obj = val.obj
      let item = val.item
      let ind
      let findst = obj.some((n) => {
        ind = this.flavorDataes.findIndex((o) => o == n)
        return ind != -1
      })
      const num = this.flavorDataes.findIndex((it) => it == item)
      if (num == -1 && !findst) {
        this.flavorDataes.push(item)
      } else if (findst) {
        this.flavorDataes.splice(ind, 1)
        this.flavorDataes.push(item)
      } else {
        this.flavorDataes.splice(num, 1)
      }
    },
    // 关闭选规格弹窗
    closeMoreNorm (moreNormDishdata) {
      this.flavorDataes.splice(0, this.flavorDataes.length)
      this.openMoreNormPop = false
    },
    // 订单里和总订单价格计算
    computOrderInfo () {
      let oriData = this.orderListDataes
      this.orderDishNumber = this.orderDishPrice = 0
      oriData.map((n, i) => {
        this.orderDishNumber += n.number
        this.orderDishPrice += n.number * n.amount
      })
      this.orderDishPrice = this.orderDishPrice
    },
    // 处理点餐数量 - 更新菜品已点餐数量
    setOrderNum () {
      let ODate = this.dishListData
      let CData = this.orderListDataes
      ODate &&
        ODate.map((obj, index) => {
          obj.dishNumber = 0
          // 去除空的规格
          if (obj.flavors) {
            obj.flavors.forEach((value, i) => {
              if (value.name === "") {
                obj.flavors.splice(i, 1)
              }
            })
          }

          if (CData.length > 0) {
            CData &&
              CData.forEach((tg, ind) => {
                if (obj.id === tg.dishId) {
                  obj.dishNumber = tg.number
                }
                if (obj.id === tg.setmealId) {
                  obj.dishNumber = tg.number
                }
              })
          }
        })
      if (this.dishListItems.length == 0) {
        this.dishListItems = ODate
      } else {
        this.dishListItems.splice(0, this.dishListItems.length, ...ODate)
      }
    },
    // 餐厅页会在确认后清空旧购物车，避免一个订单混入多家餐厅的商品。
    switchRestaurant () {
      uni.navigateTo({ url: '/pages/restaurant/index' })
    },
    // 拨打电话弹层
    handlePhone (type) {
      this.$refs.phone.$refs.popup.open(type)
    },
    // 关闭电话弹层
    closePopup (type) {
      this.$refs.phone.$refs.popup.close(type)
    },
    // TODO保留
    // // 电话号码拨打
    // call(){
    // 	const phone = '15200000001'
    // call(this.phoneData)
    // }
    disabledScroll () {
      return false
    },
  },
  onUnload () {
    if (this.seckillTimer) clearInterval(this.seckillTimer)
  },
}
