// Payment mode comes only from the server response. A client payment success is not an order receipt.
export async function executePayment(response, options) {
  if (!response || Number(response.code) !== 1 || !response.data) {
    throw new Error((response && response.msg) || '支付请求失败');
  }
  const data = response.data;
  if (data.mockPayment === true) {
    const accepted = await new Promise((resolve, reject) => options.showModal({
      title: '本地测试支付',
      content: `确认支付 ¥${Number(options.amount).toFixed(2)} 吗？不会产生真实扣款。`,
      confirmText: '确认支付',
      success: result => resolve(result.confirm === true),
      fail: reject,
    }));
    if (!accepted) throw Object.assign(new Error('已取消支付'), { cancelled: true });
    const confirmed = await options.confirmMock();
    if (!confirmed || Number(confirmed.code) !== 1) throw new Error((confirmed && confirmed.msg) || '支付确认失败');
    return;
  }
  if (data.mockPayment !== undefined && data.mockPayment !== false) throw new Error('支付方式返回异常');
  if (data.signType !== 'RSA' || !String(data.packageStr || '').startsWith('prepay_id=') ||
      !data.timeStamp || !data.nonceStr || !data.paySign) throw new Error('微信支付参数不完整');
  await new Promise((resolve, reject) => options.requestPayment({
    provider: 'wxpay', timeStamp: String(data.timeStamp), nonceStr: data.nonceStr,
    package: data.packageStr, signType: data.signType, paySign: data.paySign,
    success: resolve,
    fail: error => reject(Object.assign(new Error(/cancel/i.test((error && error.errMsg) || '') ? '已取消支付' : '微信支付未完成'),
      { cancelled: /cancel/i.test((error && error.errMsg) || '') })),
  }));
}

export async function waitForOrderPayment(fetchOrder, options = {}) {
  const attempts = options.attempts || 8;
  const wait = options.wait || (ms => new Promise(resolve => setTimeout(resolve, ms)));
  for (let attempt = 0; attempt < attempts; attempt++) {
    const response = await fetchOrder();
    if (!response || Number(response.code) !== 1 || !response.data) throw new Error('支付结果查询失败，请查看订单详情');
    const order = response.data;
    if (Number(order.status) === 6 || Number(order.payStatus) === 2)
      throw new Error('订单已关闭，已付款金额请查看退款进度');
    if (Number(order.payStatus) === 1 && [2, 3, 4, 5].includes(Number(order.status))) return order;
    if (attempt + 1 < attempts) await wait(500);
  }
  throw Object.assign(new Error('支付结果确认中，请在订单详情查看'), { pending: true });
}
