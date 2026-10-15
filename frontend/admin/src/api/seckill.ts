import request from '@/utils/request'
export const adjustSeckillStock = (id: number, total: number, expectedTotal: number) => request({ url: `/seckill/activity/${id}/stock`, method: 'put', data: { total, expectedTotal } })

export const getSeckillActivities = () => request({ url: '/seckill/activity', method: 'get' })
export const createSeckillActivity = (data: any) => request({ url: '/seckill/activity', method: 'post', data })
export const updateSeckillActivity = (data: any) => request({ url: '/seckill/activity', method: 'put', data })
export const deleteSeckillActivity = (id: number) => request({ url: `/seckill/activity/${id}`, method: 'delete' })
export const warmUpSeckillActivity = (id: number) => request({ url: `/seckill/activity/${id}/warm-up`, method: 'post' })
