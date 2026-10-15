// 金额先转换为整数分；红包仅抵扣餐品，不抵扣打包费和配送费。
export function moneyToCents (amount) {
  const value = Number(amount)
  if (!Number.isFinite(value) || value <= 0) return 0
  return Math.round((value + Number.EPSILON) * 100)
}

export function calculateCheckoutAmounts (items, packetFaceAmount = 0) {
  let foodCents = 0
  let itemCount = 0
  for (const item of Array.isArray(items) ? items : []) {
    const quantity = Math.max(0, Math.trunc(Number(item.number) || 0))
    foodCents += moneyToCents(item.amount) * quantity
    itemCount += quantity
  }
  const baseCents = foodCents + 600 + itemCount * 100
  const discountCents = Math.min(foodCents, moneyToCents(packetFaceAmount))
  return {
    itemCount,
    baseAmount: baseCents / 100,
    discount: discountCents / 100,
    payable: (baseCents - discountCents) / 100
  }
}
