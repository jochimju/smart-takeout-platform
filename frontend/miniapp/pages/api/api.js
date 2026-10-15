import {request} from "../../utils/request.js"

// 多餐厅 V1
export const getRestaurantList = () => request({ url: '/user/restaurants', method: 'GET' })
export const getRestaurantDetail = (restaurantId) => request({ url: `/user/restaurants/${restaurantId}`, method: 'GET' })
export const getRestaurantCategories = (restaurantId) => request({ url: `/user/restaurants/${restaurantId}/categories`, method: 'GET' })
export const getRestaurantDishes = (restaurantId, categoryId) => request({
	url: `/user/restaurants/${restaurantId}/dishes`, method: 'GET', params: { categoryId }
})
export const getRestaurantSetmeals = (restaurantId, categoryId) => request({
	url: `/user/restaurants/${restaurantId}/setmeals`, method: 'GET', params: { categoryId }
})
export const switchRestaurantCart = (params) => request({
	url: '/user/shoppingCart/switch', method: 'POST', params
})

// 限时秒杀订单独立于普通购物车。
// 各餐厅的"限时秒杀套餐"入口共用同一份活动数据；不传 canteenId 时后端返回全部进行中的活动。
export const querySeckillActivities = (canteenId) => request({
	url: '/user/seckill/activity/list', method: 'GET',
	params: canteenId ? { canteenId } : {}
})
export const submitSeckillOrder = (params) => request({ url: '/user/order/seckill/submit', method: 'POST', params })
export const getSeckillOrderResult = (requestId, activityId) => request({
	url: '/user/order/seckill/result', method: 'GET', params: { requestId, activityId }
})

export const getRedPacketPackages = () => request({ url: '/user/red-packet/package', method: 'GET' })
export const getMyRedPackets = () => request({ url: '/user/red-packet/my', method: 'GET' })
export const createRedPacketPurchase = (params) => request({ url: '/user/red-packet/purchase-orders', method: 'POST', params })
export const createRedPacketPayment = (params) => request({ url: '/user/red-packet/purchase-orders/payment', method: 'PUT', params })
export const confirmMockRedPacketPayment = (params) => request({ url: '/user/red-packet/purchase-orders/payment/mock/confirm', method: 'PUT', params })

// 开桌
export const openTable = (params) => 
	request({
		url: `/user/table/open/${params.tableId}/${params.seatNumber}`,
		method: 'GET',
		params
	})

// 获取桌台状态
export const getTableState = (params) => 
	request({
		url: `/user/table/tableStatus/${params.shopId}/${params.storeId}/${params.tableId}`,
		method: 'GET',
		params,
	})
	
// 获取购物车菜品
export const getTableOrderDishList = (params) =>
	request({
		url: `/user/order/shopCart//${params.tableId}`,
		method: 'GET',
		params
	})
// 获取菜品规格
export const getMoreNorm = (params) => 
	request({
		url: `/user/dish/flavor/${params.dishId}`,
		method: 'GET',
		params,
	})	
	
// 获取菜品分类列表	
export const getList = (params) => 
	request({
		url: `/user/dish/category`,
		method: 'GET',
		params,
	})
	
// 获取菜品详情	
export const getDishDetail = (params) => 
	request({
		url: `/user/dish/setmealDishList/${params.setmealId}`,
		method: 'GET',
		params,
	})
	
// 根据分类获取菜品列表
export const getDishList = (params) => 
	request({
		url: `/user/dish/dishPageList/${params.categoryId}/${params.type}/${params.page}/${params.pageSize}`,
		method: 'GET',
		params
	})

// 加菜
export const addDish = (params) => 
	request({
		url: `/user/order/addDish`,
		method: 'POST',
		params
	})
	
// 减菜
export const delDish = (params) => 
	request({
		url: `/user/order/decreaseDish/${params.tableId}/${params.dishId}`,
		method: 'GET',
		params
	})	
	
// 清空购物车
export const clearOrder = (params) => 
	request({
		url: `/user/order/cleanShopCart/${params.tableId}`,
		method: 'GET',
		params
	})
	
// 提交订单 
export const payOrder = (params) => 
	request({
		url: `/user/order/pay/${params.tableId}/${params.jsCode}`,
		method: 'GET',
		params
	})
	
	
// 用户登录
export const userLogin = (params) => {
	return request({
		url: '/user/user/login',
		method: 'POST',
		params
	})
}

// 菜品和套餐的分类
export const getCategoryList = (params) => {
	return request({
		url: '/user/category/list',
		method: 'GET',
		params
	})
}

// 查询菜品管理列表
export const dishListByCategoryId = (params) => {
	return request({
		url: '/user/dish/list',
		method: 'GET',
		params
	})
}

// 文件下载---预览
export const commonDownload = (params) => {
	return request({
		url: '/user/common/download',
		method: 'GET',
		params
	})
}


// 购物车----加菜功能实现
export const addShoppingCart = (params) => {
	return request({
		url: '/user/shoppingCart',
		method: 'POST',
		params
	})
}

// 根据type类型查询是套餐的接口
export const querySetmeaList = (params) => {
	return request({
		url: '/user/setmeal/list',
		method: 'GET',
		params
	})
}

// 获取购物车集合
export const getShoppingCartList = (params) => {
	return request({
		url: '/user/shoppingCart/list',
		method: 'GET',
		params
	})
}


// 修改购物车数量功能
export const editHoppingCart = (params) => {
	return request({
		url: '/user/shoppingCart',
		method: 'PUT',
		params
	})
}


// 购物车新增接口-new
export const newAddShoppingCartAdd = (params) => {
	return request({
		url: '/user/shoppingCart/add',
		method: 'POST',
		params
	})
}


// 购物车减菜接口-new 
export const newShoppingCartSub = (params) => {
	return request({
		url: '/user/shoppingCart/sub',
		method: 'POST',
		params
	})
}


// 清除购物车
export const delShoppingCart = (params) => {
	return request({
		url: '/user/shoppingCart/clean',
		method: 'DELETE',
		params
	})
}


// 最近订单和历史订单
export const queryOrderUserPage = (params) => {
	return request({
		url: '/user/order/userPage',
		method: 'GET',
		params
	})
}


// 用户下单
export const submitOrderSubmit = (params) => {
	return request({
		url: '/user/order/submit',
		method: 'POST',
		params
	})
}

// 普通下单为异步受理，轮询该接口获取最终落库的订单（含 id 与 orderAmount）
export const getSubmitOrderResult = (requestId) => {
	return request({
		url: '/user/order/submit-result',
		method: 'GET',
		params: { requestId }
	})
}


// 查询地址列表
export const queryAddressBookList = (params) => {
	return request({
		url: '/user/addressBook/list',
		method: 'GET',
		params
	})
}

// 新增默认接口
export const putAddressBookDefault = (params) => {
	return request({
		url: '/user/addressBook/default',
		method: 'PUT',
		params
	})
}


// 新增地址接口
export const addAddressBook = (params) => {
	return request({
		url: '/user/addressBook',
		method: 'POST',
		params
	})
}

// 修改地址接口
export const editAddressBook = (params) => {
	return request({
		url: '/user/addressBook',
		method: 'PUT',
		params
	})
}

// 删除地址
export const delAddressBook = (id) => {
	return request({
		url: `/user/addressBook/?id=${id}`,
		method: 'DELETE',
		params: { id }
	})
}

// 查询地址通过id
export const queryAddressBookById = (params) => {
	return request({
		url: `/user/addressBook/${params.id}`,
		method: 'GET',
		params
	})
}


// 再来一单
export const oneOrderAgain = (params) => {
	return request({
		url: '/user/order/again',
		method: 'POST',
		params
	})
}

// 查询默认地址
export const getAddressBookDefault = () => {
	return request({
		url: '/user/addressBook/default',
		method: 'GET'
	})
}


// 此接口为首页查询套餐详情展示的接口
export const querySetmealDishById = (params) => {
	return request({
		url: `/user/setmeal/dish/${params.id}`,
		method: 'GET'
	})
}
// v2.0添加接口
// 获取首页店铺信息
export const getShopStatus = (params) => {
	return request({
		url: `/user/shop/status`,
		method: 'GET'
	})
}
// 获取店铺电话
export const getShopPhone = (params) => {
	return request({
		url: `/user/shop/phone`,
		method: 'GET'
	})
}
// 历史订单
export const getOrderPage = (params) => {
	return request({
		url: '/user/order/historyOrders',
		method: 'GET',
		params
	})
}
// 从当前用户的历史订单中删除（后端保留订单记录，仅对当前用户隐藏）
export const deleteHistoryOrder = (id) =>
request({
	url: `/user/order/${id}`,
	method: 'DELETE'
})
// 订单详情
export const getOrderDetail = (params) => 
request({
	url: `/user/order/orderDetail/${params}`,
	method: 'GET'
})
// 取消订单
export const cancelOrder = (params) => 
request({
	url: `/user/order/cancel/${params}`,
	method: 'PUT'
})
// 催单
export const reminderOrder = (params) => 
request({
	url: `/user/order/reminder/${params}`,
	method: 'GET'
})
// 订单支付
export const paymentOrder = (params) => 
request({
	url: `/user/order/payment`,
	method: 'PUT',
	params
})
// 本地开发环境的模拟支付确认；确认后订单才会从待支付变为待接单。
export const confirmMockOrderPayment = (params) =>
request({
	url: '/user/order/payment/mock/confirm',
	method: 'PUT',
	params
})
// 再来一单
export const repetitionOrder = (params) => 
request({
	url: `/user/order/repetition/${params}`,
	method: 'POST',
	params
})
