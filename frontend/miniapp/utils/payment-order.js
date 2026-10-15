export function isPaymentOrderReady (order) {
  if (!order || !/^[1-9]\d*$/.test(String(order.id || ''))) return false
  if (order.submitStatus && order.submitStatus !== 'SUCCEEDED') return false
  const amount = order.orderAmount
  if (amount == null || String(amount).trim() === '' || !Number.isFinite(Number(amount)) || Number(amount) < 0) return false
  return typeof order.orderNumber === 'string' && order.orderNumber.length > 0
}
