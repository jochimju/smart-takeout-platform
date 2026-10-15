import { isPaymentOrderReady } from '../../utils/payment-order'
import { calculateCheckoutAmounts } from '../../utils/checkout-money'
import uniListItem from '../../uni_modules/uni-list/components/uni-list-item/uni-list-item.vue'
import {
	openTable,
	getTableState,
	getList,
	payOrder,
	clearOrder,
	getDishList,
	addDish,
	delDish,
	getTableOrderDishList,
	// 提交订单
	submitOrderSubmit,
	// 普通下单异步结果查询
	getSubmitOrderResult,
	// 查询默认地址
	getAddressBookDefault,
	reminderOrder,
	cancelOrder,
	queryAddressBookList,
	submitSeckillOrder,
	getSeckillOrderResult,
	getMyRedPackets
} from '../api/api.js'
import initWebScoket from '../../utils/webscoket'
import {
	mapState,
	mapMutations,
	mapActions
} from 'vuex'
import {
	baseUrl
} from '../../utils/env'
import {
	getLableVal,
	dateFormat,
	presentFormat,
	getWeekDate
} from '../../utils/index.js'
import Pikers from '@/components/uni-piker/index.vue'//餐具信息
import Popup from '@/components/uni-popup/index.vue'
import AddressPop from "./components/address.vue" //地址
import DishDetail from "./components/dishDetail.vue" //菜品详情
import DishInfo from "./components/dishInfo.vue" //菜品信息
export default {
	data () {
		return {
			platform: 'ios',
			orderDishPrice: 0,
			openPayType: false,
			psersonUrl: '../../static/btn_waiter_sel.png',
			nickName: '',//名字
			gender: '0',
			phoneNumber: '',//电话
			address: '',//地址
			remark: '',//备注
			arrivalTime: '',// 送达时间
			orderTime: '',
			addressBookId: '',
			addressLabel: '',
			tagLabel: '',
			// 加入购物车数量
			orderDishNumber: 0,
			showDisplay: false,//是否显示更多收起
			type: 'center',
			expirationTime: '',
			// rocallTime:'',
			tablewareData: '无需餐具',
			tableware: '',
			packAmount: 0,
			value: [0, 0],
			timeValue: [0, 0],
			indicatorStyle: `height: 44px;color:#333`,
			tabIndex: 0,
			scrollinto: 'tab0',
			scrollH: 0,
			popleft: ['今天', '明天'],// 时间选中的左侧数据（今天、明天）
			visible: true,
			baseData: [
				'无需餐具', '1', '2', '3', '4', '5', '6', '7', '8', '9', '10'
			],
			activeRadio: '无需餐具', //存的是选中的value值
			radioGroup: ['依据餐量提供', '无需餐具'],
			popright: ['立即派送', '09:00', '09:30', '10:00', '10:30', '11:00', '11:30', '12:00', '12:30', '13:00',
				'13:30', '14:00', '14:30', '15:00', '15:30', '16:00', '16:30', '17:00', '17:30', '18:00', '18:30',
				'19:00', '19:30', '20:00', '20:30', '21:00', '21:30', '22:00', '22:30', '23:00'
			],
			newDateData: [],// 时间段
			// styleType: 'button',
			textTip: '',
			showConfirm: false,
			phoneData: '15200000001',
			toDate: null,
			tomorrowStart: null,
			newDate: null,
			selectValue: 0,
			selectDateValue: 0,
			timeout: false,
			isTomorrow: false,
			status: 0,
			num: 0,
			weeks: [],
			scrollTop: 0,
			addressList: [],
			isHandlePy: false,
			seckillCheckout: null,
			isSeckillCheckout: false,
			normalOrderRequestId: '',
			selectedRedPacketId: null,
			redPacketDiscount: 0,
			redPacketFaceAmount: 0,
			redPacketExplicitlyDisabled: false,
			availableRedPackets: [],
			baseOrderAmount: 0,
			restaurantName: '餐厅信息待确认',
			restaurantId: null
		}
	},
	computed: {
		// 菜品数据
		orderListDataes: function () {
			const items = this.isSeckillCheckout
				? (this.seckillCheckout ? [this.seckillCheckout] : [])
				: this.orderListData()
			return (Array.isArray(items) ? items : []).map(item => ({
				...item,
				image: this.resolveMenuImage(item.image)
			}))
		},
		// 菜品数据
		orderDataes: function () {
			let testList = []
			if (this.showDisplay === false) {
				if (this.orderListDataes.length > 3) {
					for (var i = 0; i < 3; i++) {
						testList.push(this.orderListDataes[i])
					}
				} else {
					testList = this.orderListDataes
				}
				return testList
			} else {
				return this.orderListDataes
			}
		}
	},
	created () {
		let time = new Date()
		this.toDate = new Date(time.toLocaleDateString()).getTime()
		this.tomorrowStart = this.toDate + 3600 * 24 * 1000
		this.newDate = time.getHours() * 3600 + time.getMinutes() * 60
		this.getDateDate()
		const weekDay = [this.toDate, this.tomorrowStart]

		weekDay.forEach((date) => {
			this.weeks.push(getWeekDate(date))

		})

		this.getAddressList()
	},
	mounted () {
		this.countdown()
	},
	onShow () {
		const selection = uni.getStorageSync('redPacketSelection')
		if (!selection || !selection.changed) return
		uni.removeStorageSync('redPacketSelection')
		this.selectedRedPacketId = selection.id || null
		this.redPacketExplicitlyDisabled = !selection.id
		this.redPacketFaceAmount = Number(selection.amount || 0)
		this.updateRedPacketPayable()
	},
	components: {
		uniListItem,
		Pikers,
		Popup,
		AddressPop,
		DishDetail,
		DishInfo
	},
		onLoad (options) {
		this.initPlatform()
		const restaurant = uni.getStorageSync('currentRestaurant') || {}
		this.restaurantId = Number((options && options.restaurantId) || restaurant.id) || null
		this.restaurantName = restaurant.name || '餐厅信息待确认'
		this.isSeckillCheckout = options && options.seckill === '1'
		this.seckillCheckout = this.isSeckillCheckout ? uni.getStorageSync('seckillCheckout') || null : null
		if (this.isSeckillCheckout && !this.seckillCheckout) {
			uni.showToast({ title: '请重新选择秒杀套餐', icon: 'none' })
			uni.redirectTo({ url: '/pages/index/index' })
			return
		}
		if (!this.isSeckillCheckout) {
			this.normalOrderRequestId = uni.getStorageSync('normalOrderSubmitRequestId') || `${Date.now().toString(36)}_${Math.random().toString(36).slice(2)}`
			uni.setStorageSync('normalOrderSubmitRequestId', this.normalOrderRequestId)
		}
		this.psersonUrl = this.$store.state.baseUserInfo && this.$store.state.baseUserInfo.avatarUrl
		this.nickName = this.$store.state.baseUserInfo && this.$store.state.baseUserInfo.nickName
		this.gender = this.$store.state.baseUserInfo && this.$store.state.baseUserInfo.gender
		this.remark = this.remarkData()
		this.init()
		// 获取一小时以后的时间
		this.getHarfAnOur()

		// 存在options说明换地址了
		if (this.addressData() && this.addressData().detail) {
			this.addressBookId = ''
			const newAddress = this.addressData()
			this.address = newAddress.provinceName + newAddress.cityName + newAddress.districtName + newAddress.detail
			this.phoneNumber = newAddress.phone
			this.nickName = newAddress.consignee
			this.gender = newAddress.sex
			this.addressBookId = newAddress.id
			this.addressLabel = getLableVal(newAddress.label)
		} else {
			// 默认地址查询
			this.getAddressBookDefault()
		}
	},
	onReady () {
		uni.getSystemInfo({
			success: (res) => {
				this.scrollH = res.windowHeight - uni.upx2px(100)
			}
		})
	},
	methods: {
		...mapState(['orderListData', 'remarkData', 'addressData']),
		...mapMutations(['setAddressBackUrl', 'setOrderData', 'setArrivalTime', 'setRemark']),
		init () {
			this.computOrderInfo()
			this.loadRedPackets()
		},
		initPlatform () {
			const res = uni.getSystemInfoSync()
			this.platform = res.platform
		},
		// 获取一小时以后的时间
		getHarfAnOur () {
			const date = new Date()
			date.setTime(date.getTime() + 3600000)
			let hours = date.getHours()
			let minutes = date.getMinutes()
			if (hours < 10) hours = '0' + hours
			if (minutes < 10) minutes = '0' + minutes
			this.arrivalTime = hours + ':' + minutes
			this.setArrivalTime(this.arrivalTime)
		},
		// 处理时间数据，超时的数据隐藏
		getDateDate () {
			let timestamp = 0
			let newArr = []
			if (this.toDate < this.tomorrowStart) {
				this.popright.forEach((item) => {
					const arr = item.split(':')
					const hour = arr[0]
					const min = arr[1]
					timestamp = Number(hour * 3600) + Number(min * 60)
					if (this.newDate < timestamp) {
						this.newDateData.push(item)
					}


				})
				this.newDateData.splice(0, 2)
				this.newDateData.unshift('立即派送')
			}
		},
		// 获取地址
		getAddressList () {
			this.testValue = false
			queryAddressBookList().then(res => {
				if (res.code === 1) {
					this.testValue = true
					this.addressList = res.data
				}
			})
		},
		// 默认地址查询
		getAddressBookDefault () {
			getAddressBookDefault().then(res => {
				if (res.code === 1) {
					this.addressBookId = ''
					this.address = res.data.provinceName + res.data.cityName + res.data.districtName + res.data
						.detail
					this.phoneNumber = res.data.phone
					this.nickName = res.data.consignee
					this.gender = res.data.sex
					this.addressBookId = res.data.id
					this.addressLabel = getLableVal(res.data.label)
					this.tagLabel = res.data.label
				}
			})
		},
		// 去地址页面
		goAddress () {
			this.setAddressBackUrl('/pages/order/index')
			if (this.addressList.length === 0) {
				uni.redirectTo({
					url: '/pages/addOrEditAddress/addOrEditAddress'
				})
			} else {
				uni.redirectTo({
					url: '/pages/address/address'
				})
			}

		},
		// // 重新拼装image
		getNewImage (image) {
			return `${baseUrl}/common/download?name=${image}`
		},
		// 与点餐页保持同一图片规则：套餐/菜品优先读取已打包进小程序的本地图片。
		resolveMenuImage (image) {
			if (!image) return '../../static/imgDefault.png'
			if (/^https?:\/\//i.test(image)) return image
			if (image.startsWith('/dishes/') || image.startsWith('/setmeals/')) {
				return `/static/menu${image.replace(/\.png$/i, '.jpg')}`
			}
			if (image.startsWith('/')) return `${baseUrl}${image}`
			return `${baseUrl}/common/download?name=${image}`
		},
		// 订单里和总订单价格计算
		computOrderInfo () {
			this.updateRedPacketPayable()
		},
		async loadRedPackets () {
			try {
				const res = await getMyRedPackets()
				this.availableRedPackets = res.data || []
				uni.setStorageSync('redPacketAvailable', this.availableRedPackets)
				if (!this.redPacketExplicitlyDisabled) {
					const packet = this.availableRedPackets.find(item => String(item.id) === String(this.selectedRedPacketId)) || this.availableRedPackets[0]
					this.selectedRedPacketId = packet ? packet.id : null
					this.redPacketFaceAmount = packet ? Number(packet.amount || 0) : 0
				}
				this.updateRedPacketPayable()
			} catch (e) { /* 红包服务不可用时仍可正常下单，由服务端决定最终结算 */ }
		},
		updateRedPacketPayable () {
			const amounts = calculateCheckoutAmounts(this.orderListDataes,
				this.selectedRedPacketId && !this.redPacketExplicitlyDisabled ? this.redPacketFaceAmount : 0)
			this.orderDishNumber = amounts.itemCount
			this.baseOrderAmount = amounts.baseAmount
			this.redPacketDiscount = amounts.discount
			this.orderDishPrice = amounts.payable
		},
		goRedPacket () {
			uni.navigateTo({ url: `/pages/redPacket/index?select=1&selectedId=${this.selectedRedPacketId || ''}` })
		},
		// 返回上一级
		goBack () {
			uni.redirectTo({
				url: '/pages/index/index'
			})
		},
		closeMask () {
			this.openPayType = false
		},
		// 支付下单
		payOrderHandle () {
			this.isHandlePy = true

			if (!this.address) {
				uni.showToast({
					title: '请选择收货地址',
					icon: 'none',
				})
				return false
			}
			let num = null
			let status = null
			const params = {
				payMethod: 1,
				addressBookId: this.addressBookId,
				remark: this.remark,
				estimatedDeliveryTime: this.arrivalTime === '立即派送' ? presentFormat() : dateFormat(this.isTomorrow,
					this.arrivalTime),
				deliveryStatus: this.arrivalTime === '立即派送' ? 1 : 0,
				remark: this.remark,
				tablewareStatus: this.status,
				tablewareNumber: this.num,
				packAmount: this.orderDishNumber,
				amount: this.orderDishPrice,
				canteenId: this.restaurantId,
				canteenName: this.restaurantName,
				requestId: this.normalOrderRequestId,
				userRedPacketId: this.selectedRedPacketId,
				useRedPacket: !this.redPacketExplicitlyDisabled
			}
			// 网络结果未知时重用首次请求正文，避免派送时间变化导致幂等冲突。
			let submit = (payload) => {
				const pending = uni.getStorageSync('normalOrderPendingPayload')
				const original = pending && pending.requestId === payload.requestId ? pending : payload
				uni.setStorageSync('normalOrderPendingPayload', original)
				return submitOrderSubmit(original)
			}
			if (this.isSeckillCheckout) {
				params.activityId = this.seckillCheckout.activityId
				params.setmealId = this.seckillCheckout.setmealId
				params.canteenId = this.seckillCheckout.canteenId
				params.requestId = this.seckillCheckout.requestId
				submit = (payload) => getSeckillOrderResult(payload.requestId, payload.activityId)
					.then(previous => previous && previous.code === 1 && previous.data ? previous : submitSeckillOrder(payload))
			}

			submit(params).then(async res => {
				if (res.code === 1) {
					this.isHandlePy = false
					let orderData = res.data || {}
					// 普通订单和秒杀均异步建单，必须查询真实订单后进入支付页。
					if (['FAILED', 'CANCELLED'].includes(orderData.submitStatus)) {
						if (this.isSeckillCheckout) this.renewSeckillRequest()
						else this.renewNormalRequest()
						uni.showToast({ title: orderData.failureMessage || '建单失败', icon: 'none' })
						return
					}
					if (!isPaymentOrderReady(orderData)) {
						const settled = await this.pollSubmitResult(params.requestId)
						if (!settled) {
							// 结果未知时保留请求，防止重复建单
							uni.showToast({
								title: '订单处理中，请在订单列表查看',
								icon: 'none',
							})
							return
						}
						orderData = settled
					}
					// 将下单时的餐厅一并带入支付页，避免用户切换餐厅后显示错位。
					this.setOrderData({ ...orderData, restaurantName: this.restaurantName })
					this.setRemark('')
					if (this.isSeckillCheckout) uni.removeStorageSync('seckillCheckout')
					else this.renewNormalRequest()
					uni.redirectTo({
						url: '/pages/pay/index?orderId=' + orderData.id
					})
				} else {
					// 后端可能已受理但订单尚未异步落库（order is processing），
					// 先查询最终结果；未知时保留请求。
					if (!this.isSeckillCheckout && typeof res.msg === 'string' && res.msg.indexOf('processing') >= 0) {
						const settled = await this.pollSubmitResult(params.requestId)
						if (settled) {
							this.setOrderData({ ...settled, restaurantName: this.restaurantName })
							this.setRemark('')
							this.renewNormalRequest()
							uni.redirectTo({
								url: '/pages/pay/index?orderId=' + settled.id
							})
							return
						}
						uni.showToast({
							title: '订单处理中，请保留原请求查询',
							icon: 'none',
						})
						return
					}
					uni.showToast({
						title: res.msg || '操作失败',
						icon: 'none',
					})
				}
			}).catch(async error => {
				this.isHandlePy = false
				const message = error && error.msg || '下单失败，请重试'
				if (error && error.seckillTerminal) this.renewSeckillRequest()
				if (error && error.normalTerminal) this.renewNormalRequest()
				if (this.isSeckillCheckout && !(error && error.seckillTerminal)) {
					try {
						const settled = await this.pollSubmitResult(params.requestId)
						if (settled) {
							this.setOrderData({ ...settled, restaurantName: this.restaurantName })
							uni.removeStorageSync('seckillCheckout')
							uni.redirectTo({ url: '/pages/pay/index?orderId=' + settled.id })
							return
						}
					} catch (terminal) {
						if (terminal && terminal.seckillTerminal) this.renewSeckillRequest()
						uni.showToast({ title: terminal.msg || message, icon: 'none' })
						return
					}
				}
				// request.js 会将 code != 1 的业务响应 reject 掉，因此
				// “order is processing” 会直接进入这里，而不会进入上面的 else。
				// 仍按异步下单处理：查询最终结果，失败时释放 requestId。
				if (!this.isSeckillCheckout && typeof message === 'string' && message.indexOf('processing') >= 0) {
					const settled = await this.pollSubmitResult(params.requestId)
					if (settled) {
						this.setOrderData({ ...settled, restaurantName: this.restaurantName })
						this.setRemark('')
						this.renewNormalRequest()
						uni.redirectTo({ url: '/pages/pay/index?orderId=' + settled.id })
						return
					}
					uni.showToast({ title: '订单处理中，请保留原请求查询', icon: 'none' })
					return
				}
				uni.showToast({ title: message, icon: 'none' })
			})
		},
		// 仅确定失败后，为用户下一次主动提交生成新请求；轮询超时保留原请求。
		renewSeckillRequest () {
			if (!this.seckillCheckout) return
			this.seckillCheckout.requestId = `${Date.now().toString(36)}_${Math.random().toString(36).slice(2)}${Math.random().toString(36).slice(2)}`
			uni.setStorageSync('seckillCheckout', this.seckillCheckout)
		},
		renewNormalRequest () {
			uni.removeStorageSync('normalOrderSubmitRequestId')
			uni.removeStorageSync('normalOrderPendingPayload')
			this.normalOrderRequestId = `${Date.now().toString(36)}_${Math.random().toString(36).slice(2)}`
			uni.setStorageSync('normalOrderSubmitRequestId', this.normalOrderRequestId)
		},
		// 轮询异步下单结果：订单由 MQ 消费者落库，最多等待约 8 秒
		async pollSubmitResult (requestId) {
			if (!requestId) return null
			for (let i = 0; i < 16; i++) {
				try {
					const res = this.isSeckillCheckout
						? await getSeckillOrderResult(requestId, this.seckillCheckout.activityId)
						: await getSubmitOrderResult(requestId)
					if (res && res.data && ['FAILED', 'CANCELLED'].includes(res.data.submitStatus)) {
						throw { msg: res.data.failureMessage || '建单失败', seckillTerminal: this.isSeckillCheckout, normalTerminal: !this.isSeckillCheckout }
					}
					if (res && res.code === 1 && res.data && isPaymentOrderReady(res.data)) {
						return res.data
					}
				} catch (e) {
					if (e && (e.seckillTerminal || e.normalTerminal)) throw e
					// 网络抖动时继续重试
				}
				await new Promise(resolve => setTimeout(resolve, 500))
			}
			return null
		},
		// 拨打电话
		call () {
			uni.makePhoneCall({
				phoneNumber: '114' //仅为示例
			})
		},
		// // 联系商家进行取消弹层
		handleContact (type) {
			this.showConfirm = false
			this.openPopuos(type)
			this.textTip = '请联系商家进行取消！'
		},
		// 联系商家进行退款弹层
		handleRefund (type) {
			this.showConfirm = false
			this.openPopuos(type)
			this.textTip = '请联系商家进行退款！'
		},
		// 进入备注页
		goRemark () {
			this.setAddressBackUrl(`/pages/order/index${this.isSeckillCheckout ? '?seckill=1' : ''}`)
			uni.redirectTo({
				url: '/pages/remark/index'
			})
		},
		// 打开参数数量弹层
		openPopuos (type) {
			// open 方法传入参数 等同在 uni-popup 组件上绑定 type属性
			this.$refs.popup.open(type)
		},
		// 关闭餐具弹层
		closePopup (type) {
			this.$refs.popup.close(type)
		},
		change (e) {
		},
		// 确定本单餐具
		handlePiker () {
			if (this.tableware !== '') {
				this.num = Number(this.tableware)
				this.status = 0
				if (this.tableware === '无需餐具') {
					this.num = 0
					this.status = 0
				}
				if (this.tableware === '依据餐量提供') {
					this.num = this.orderDishNumber
					this.status = 1
				}

				if (this.tableware !== '依据餐量提供' || this.tableware !== '无需餐具') {
					this.tablewareData = this.tableware + '份'

				} else {
					this.tablewareData = this.tableware
				}
			} else {
				//是默认值，在点击的时候抛出去
				let cont = this.baseData[this.$refs.dishinfo.$refs.piker.defaultValue[0]]
				this.tablewareData = cont
				if (this.activeRadio === '依据餐量提供') {
					this.num = this.orderDishNumber
					this.status = 1
				} else {
					this.num = 0
					this.status = 0
				}
			}
		},
		// 确定本单餐具
		changeCont (val) {
			this.tableware = val
		},
		// 餐具数量的后续订单餐具设置
		handleRadio (e) {
			this.activeRadio = e.detail.value
		},
		countdown () {
			const end = Date.parse(new Date())
		},
		// 星期几选择
		dateChange (index) {
			if (index === 1) {
				this.newDateData = this.popright.slice(1)
				this.isTomorrow = true
			} else {
				this.isTomorrow = false
				this.newDateData = []
				this.getDateDate()
			}
			// 点击的还是当前数据的时候直接return
			if (this.tabIndex == index) {
				return
			}
			this.tabIndex = index
		},
		// 选中时间段
		timeClick: function (val) {
			this.selectValue = val.i
			this.setTime(val.val)
		},
		// 设置时间
		setTime (val) {
			if (val === '立即派送') {
				this.getHarfAnOur()
			} else {
				this.arrivalTime = val
			}

			this.setArrivalTime(this.arrivalTime)

		},
		touchstart (e) {
			if (e.changedTouches[0].clientY > 400) {
			}
		}
	}
}
