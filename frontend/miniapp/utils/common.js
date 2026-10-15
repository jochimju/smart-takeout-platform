	import {  
		reminderOrder,
		cancelOrder
	} from '../pages/api/api.js'
	import { reorderToRestaurant } from './reorder.js'
export default {
	data(){
		return {
			textTip:'',
			showConfirm:false,
		}
	},
	// created(){
	// },
	methods: {
		// 催单
		handleReminder(type,id){
			reminderOrder(id).then(res => {
				if(res.code===1){
					this.showConfirm=true
					this.textTip='您的催单信息已发出！'
					this.$refs.commonPopup.open(type)
				}
			})
		},
		// 取消订单
		handleCancel(type,obj){
			if(obj===1){
				cancelOrder(obj.id).then(res => {
					if(res.code===1){
						this.showConfirm=true
						this.textTip='您的订单已取消！'
						this.$refs.commonPopup.open(type)
					}
				})
			}else{
				this.showConfirm=false
				this.$refs.commonPopup.open(type)
				this.textTip='请联系商家进行取消！'
			}	
		},
		// 再来一单
		async oneMoreOrder(id){
			try {
				await reorderToRestaurant(id)
			} catch (err) {
				uni.showToast({ title: err.msg || err.message || '再来一单失败', icon: 'none' })
			}
		},
		// 拨打电话弹层
		handlePhone(type) {
			// 暂时关闭打电话
			// this.$refs.phone.open(type)
		},
		// 关闭弹层
		closePopup(type){
			this.$refs.commonPopup.close(type)
		},
	}
}
