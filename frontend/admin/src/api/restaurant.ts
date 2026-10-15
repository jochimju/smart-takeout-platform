import request from '@/utils/request'

/** 餐厅管理接口。请求基础地址已在开发环境配置为 /admin。 */
export const getRestaurantPage = (params: any) => request({
  url: '/restaurant/page', method: 'get', params
})

export const getCampusZones = () => request({
  url: '/restaurant/campus-zones', method: 'get'
})

export const addRestaurant = (data: any) => request({
  url: '/restaurant', method: 'post', data
})

export const editRestaurant = (data: any) => request({
  url: '/restaurant', method: 'put', data
})

export const changeRestaurantStatus = (id: number, status: number) => request({
  url: `/restaurant/${id}/status`, method: 'put', params: { status }
})

export const deleteRestaurant = (id: number) => request({
  url: `/restaurant/${id}`, method: 'delete'
})
