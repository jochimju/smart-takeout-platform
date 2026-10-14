const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const test = require('node:test');
const { spawnSync } = require('node:child_process');
const file = path.resolve(__dirname, '../../../../../project-rjwm-weixin-uniapp/pages/order/index.js');
const source = fs.readFileSync(file, 'utf8');
const payment = vm.runInNewContext(fs.readFileSync(path.resolve(path.dirname(file), '../../utils/payment-order.js'), 'utf8').replace(/export /g, '') + '\n({isPaymentOrderReady})');
const methodsText = source.slice(source.indexOf('renewSeckillRequest () {'), source.indexOf('// 拨打电话'));
function methods(overrides = {}) {
  return vm.runInNewContext('({' + methodsText + '})', {
    isPaymentOrderReady: payment.isPaymentOrderReady,
    getSeckillOrderResult: async () => ({code: 1, data: {id: 9, orderNumber: "SK9", orderAmount: 15.9}}),
    getSubmitOrderResult: async () => ({code: 1, data: {id: 8, orderNumber: "N8", orderAmount: 20}}),
    setTimeout: callback => callback(), uni: {setStorageSync() {}}, ...overrides
  });
}
test('frontend modules parse', () => {
  for (const name of [file, path.resolve(path.dirname(file), '../api/api.js')]) {
    const result = spawnSync(process.execPath, ['--input-type=module', '--check'], {input: fs.readFileSync(name)});
    assert.equal(result.status, 0, result.stderr.toString());
  }
});
test('seckill polling uses its own endpoint and activity', async () => {
  let called;
  const m = methods({getSeckillOrderResult: async (...args) => {called = args; return {code:1,data:{id:9,orderNumber:"SK9",orderAmount:7,submitStatus:"SUCCEEDED"}};},
    getSubmitOrderResult: async () => {throw Error('wrong endpoint');}});
  const ctx = {isSeckillCheckout:true,seckillCheckout:{activityId:1}};
  assert.equal((await m.pollSubmitResult.call(ctx, 'original_request')).id, 9);
  assert.deepEqual(called, ['original_request',1]);
});
test('ordinary order polling keeps original endpoint', async () => {
  const m = methods({getSeckillOrderResult: async () => {throw Error('wrong endpoint');}});
  assert.equal((await m.pollSubmitResult.call({isSeckillCheckout:false}, 'normal_request')).id,8);
});
test('ordinary terminal failure stops polling and exposes reason', async () => {
  const m=methods({getSubmitOrderResult:async()=>({code:1,data:{submitStatus:'FAILED',failureMessage:'库存不足'}})});
  await assert.rejects(m.pollSubmitResult.call({isSeckillCheckout:false},'original'),error=>error.normalTerminal===true && error.msg==='库存不足');
});
test('ordinary unknown result does not become a terminal failure', async () => {
  let count=0;const m=methods({getSubmitOrderResult:async request=>{assert.equal(request,'original');count++;return {code:1,data:{submitStatus:'ACCEPTED'}};}});
  assert.equal(await m.pollSubmitResult.call({isSeckillCheckout:false},'original'),null);assert.equal(count,16);
});
test('terminal failure stops polling and exposes reason', async () => {
  const m = methods({getSeckillOrderResult: async () => ({code:1,data:{submitStatus:'FAILED',failureMessage:'红包不可用'}})});
  await assert.rejects(m.pollSubmitResult.call({isSeckillCheckout:true,seckillCheckout:{activityId:1}},'request'),
    error => error.seckillTerminal === true && error.msg === '红包不可用');
});
test('poll timeout preserves same request and does not manufacture failure', async () => {
  let calls=0;
  const m=methods({getSeckillOrderResult:async request => {calls++;assert.equal(request,'original');return {code:1,data:{submitStatus:'ACCEPTED'}};}});
  const checkout={activityId:1,requestId:'original'};
  assert.equal(await m.pollSubmitResult.call({isSeckillCheckout:true,seckillCheckout:checkout},'original'),null);
  assert.equal(calls,16);assert.equal(checkout.requestId,'original');
});
test('explicit failure permits next user submission with a fresh request', () => {
  let saved;
  const m=methods({uni:{setStorageSync:(key,value)=>{saved={key,value};}}});
  const ctx={seckillCheckout:{activityId:1,requestId:'old'}};
  m.renewSeckillRequest.call(ctx);
  assert.notEqual(ctx.seckillCheckout.requestId,'old');assert.equal(saved.key,'seckillCheckout');
  assert.equal(saved.value.activityId,1);
});

test('ordinary retry preserves the original payload including delivery time', async () => {
  const saved=new Map();let sent;
  const block=source.slice(source.indexOf('let submit = (payload) =>'),source.indexOf('if (this.isSeckillCheckout)',source.indexOf('let submit = (payload) =>')));
  const submit=vm.runInNewContext(block+'; submit', {uni:{getStorageSync:key=>saved.get(key),setStorageSync:(key,value)=>saved.set(key,value)},submitOrderSubmit:async body=>{sent=body;return body;}});
  await submit({requestId:'same',estimatedDeliveryTime:'first'});
  await submit({requestId:'same',estimatedDeliveryTime:'later'});
  assert.equal(sent.estimatedDeliveryTime,'first');
});

test('ordinary terminal result clears old payload and renews the in-memory request', () => {
  const removed=[];let saved;
  const m=methods({uni:{removeStorageSync:key=>removed.push(key),setStorageSync:(key,value)=>{saved=value;}}});
  const ctx={normalOrderRequestId:'old'};m.renewNormalRequest.call(ctx);
  assert.notEqual(ctx.normalOrderRequestId,'old');assert.equal(saved,ctx.normalOrderRequestId);
  assert.deepEqual(removed,['normalOrderSubmitRequestId','normalOrderPendingPayload']);
});
