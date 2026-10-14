const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const test = require('node:test');
const root = path.resolve(__dirname, '../../../../../project-rjwm-weixin-uniapp');
const money = vm.runInNewContext(fs.readFileSync(path.join(root, 'utils/checkout-money.js'), 'utf8').replace(/export /g, '') + '\n({calculateCheckoutAmounts, moneyToCents})');
const source = fs.readFileSync(path.join(root, 'pages/order/index.js'), 'utf8');
function page(overrides = {}) {
  const methods = vm.runInNewContext('({' + source.slice(source.indexOf('computOrderInfo () {'), source.indexOf('goRedPacket () {')) + '})', {
    calculateCheckoutAmounts: money.calculateCheckoutAmounts,
    getMyRedPackets: async () => ({data: [{id:1,amount:5},{id:2,amount:2}]}),
    uni: {setStorageSync() {}}, ...overrides
  });
  return {orderListDataes:[{amount:0.01,number:1}], selectedRedPacketId:null, redPacketFaceAmount:0,
    redPacketExplicitlyDisabled:false, availableRedPackets:[], ...methods};
}
test('one-cent seckill with five-yuan packet discounts exactly one cent', () => {
  const result = money.calculateCheckoutAmounts([{amount:0.01,number:1}],5);
  assert.equal(result.discount,0.01); assert.equal(result.payable,7); assert.equal(result.baseAmount,7.01);
});
test('ordinary multi-item order uses cents and never discounts delivery or packaging', () => {
  const result = money.calculateCheckoutAmounts([{amount:0.1,number:1},{amount:0.2,number:1}],5);
  assert.equal(result.discount,0.3); assert.equal(result.payable,8); assert.equal(result.itemCount,2);
});
test('auto-selected packet and manual selection use the same food cap', async () => {
  const ctx=page(); await ctx.loadRedPackets();
  assert.equal(ctx.redPacketFaceAmount,5); assert.equal(ctx.redPacketDiscount,0.01); assert.equal(ctx.orderDishPrice,7);
  ctx.redPacketFaceAmount=2; ctx.selectedRedPacketId=2; ctx.updateRedPacketPayable();
  assert.equal(ctx.redPacketDiscount,0.01); assert.equal(ctx.orderDishPrice,7);
});
test('manual selection return keeps face value separate from actual discount', () => {
  const selection={changed:true,id:1,amount:5};
  const lifecycle=vm.runInNewContext('({' + source.slice(source.indexOf('onShow () {'),source.indexOf('components: {')) + '})', {
    uni:{getStorageSync:()=>selection,removeStorageSync() {}}
  });
  const ctx=page(); lifecycle.onShow.call(ctx);
  assert.equal(ctx.redPacketFaceAmount,5); assert.equal(ctx.redPacketDiscount,0.01); assert.equal(ctx.orderDishPrice,7);
});
test('reload preserves chosen packet and explicit no-packet choice', async () => {
  const ctx=page(); ctx.selectedRedPacketId=2; await ctx.loadRedPackets();
  assert.equal(ctx.selectedRedPacketId,2); assert.equal(ctx.redPacketFaceAmount,2);
  ctx.selectedRedPacketId=null;ctx.redPacketExplicitlyDisabled=true;await ctx.loadRedPackets();
  assert.equal(ctx.redPacketDiscount,0); assert.equal(ctx.orderDishPrice,7.01);
});
test('empty packet response clears stale discount', async () => {
  const ctx=page({getMyRedPackets:async()=>({data:[]})});ctx.selectedRedPacketId=1;ctx.redPacketFaceAmount=5;
  await ctx.loadRedPackets();assert.equal(ctx.selectedRedPacketId,null);assert.equal(ctx.redPacketDiscount,0);
});
test('changing food amount recalculates discount from original packet face value', () => {
  const ctx=page();ctx.selectedRedPacketId=1;ctx.redPacketFaceAmount=5;
  ctx.orderListDataes=[{amount:8.9,number:1}];ctx.computOrderInfo();
  assert.equal(ctx.redPacketDiscount,5);assert.equal(ctx.orderDishPrice,10.9);
  ctx.orderListDataes=[{amount:0.01,number:1}];ctx.computOrderInfo();assert.equal(ctx.redPacketDiscount,0.01);assert.equal(ctx.orderDishPrice,7);
});
