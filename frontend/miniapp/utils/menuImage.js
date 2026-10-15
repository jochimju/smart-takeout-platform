import { baseUrl } from './env'

// 菜品、套餐图片统一解析，避免不同页面直接请求后端不存在的 PNG 文件。
export function resolveMenuImage (image) {
  if (!image) return '/static/imgDefault.png'
  if (/^https?:\/\//i.test(image)) return image
  if (image.startsWith('/dishes/') || image.startsWith('/setmeals/')) {
    return `/static/menu${image.replace(/\.png$/i, '.jpg')}`
  }
  if (image.startsWith('/')) return `${baseUrl}${image}`
  return `${baseUrl}/common/download?name=${image}`
}
