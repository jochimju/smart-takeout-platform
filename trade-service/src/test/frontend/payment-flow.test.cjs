const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),test=require('node:test'),assert=require('node:assert/strict');
const root=path.resolve(__dirname,'../../../../../project-rjwm-weixin-uniapp');
const {executePayment,waitForOrderPayment}=vm.runInNewContext(fs.readFileSync(path.join(root,'utils/payment-flow.js'),'utf8')
  .replace(/export /g,'')+'\n({executePayment,waitForOrderPayment})',{setTimeout});
const real={code:1,data:{timeStamp:'123',nonceStr:'nonce',packageStr:'prepay_id=test',signType:'RSA',paySign:'signature'}};

test('real chain calls native payment and never calls simulation confirmation',async()=>{
  let parameters;
  await executePayment(real,{requestPayment:p=>{parameters=p;p.success({});},confirmMock:()=>assert.fail('simulation called'),showModal:()=>assert.fail('simulation dialog')});
  assert.equal(parameters.package,'prepay_id=test'); assert.equal(parameters.signType,'RSA'); assert.equal(parameters.provider,'wxpay');
});
test('local chain requires confirmation and successful server receipt',async()=>{
  let calls=0,dialog;
  await executePayment({code:1,data:{mockPayment:true}},{amount:7.35,showModal:p=>{dialog=p.content;p.success({confirm:true});},
    confirmMock:async()=>{calls++;return {code:1};},requestPayment:()=>assert.fail('real payment called')});
  assert.equal(calls,1);assert.match(dialog,/7\.35/);
  await assert.rejects(executePayment({code:1,data:{mockPayment:true}},{amount:7,showModal:p=>p.success({confirm:true}),confirmMock:async()=>({code:0,msg:'expired'})}),/expired/);
});
test('cancelling local or real payment never acknowledges success',async()=>{
  await assert.rejects(executePayment({code:1,data:{mockPayment:true}},{amount:7,showModal:p=>p.success({confirm:false}),confirmMock:()=>assert.fail()}),/已取消/);
  await assert.rejects(executePayment(real,{requestPayment:p=>p.fail({errMsg:'requestPayment:fail cancel'})}),/已取消/);
});
test('malformed preparation or mode never invokes either payment path',async()=>{
  for(const response of [{code:0,msg:'invalid'}, {code:1,data:{}}, {code:1,data:{mockPayment:'true'}}])
    await assert.rejects(executePayment(response,{requestPayment:()=>assert.fail(),confirmMock:()=>assert.fail(),showModal:()=>assert.fail()}));
});
test('payment success waits for authoritative receipt and tolerates callback delay',async()=>{
  let calls=0;
  const order=await waitForOrderPayment(async()=>({code:1,data:++calls<3?{status:1,payStatus:0}:{status:2,payStatus:1}}),{wait:async()=>{}});
  assert.equal(calls,3);assert.equal(order.payStatus,1);
});
test('late payment on cancelled order and missing receipt cannot show success',async()=>{
  await assert.rejects(waitForOrderPayment(async()=>({code:1,data:{status:6,payStatus:1}})),/已关闭/);
  await assert.rejects(waitForOrderPayment(async()=>({code:1,data:{status:1,payStatus:0}}),{attempts:2,wait:async()=>{}}),/确认中/);
});

function page(dependencies) {
  const module={exports:{}};
  const script=fs.readFileSync(path.join(root,'pages/pay/index.vue'),'utf8').split('<script>')[1].split('</script>')[0]
    .replace(/^import[\s\S]*?from\s*["'][^"']+["'];?/gm,'').replace('export default','module.exports =');
  const redirects=[],notices=[];
  vm.runInNewContext(script,{module,mapState:()=>({}),executePayment,waitForOrderPayment,isPaymentOrderReady:()=>true,
    uni:{redirectTo:p=>redirects.push(p.url),showToast:p=>notices.push(p.title),requestPayment:p=>p.success({}),showModal:p=>p.success({confirm:true})},
    setTimeout:()=>1,clearTimeout(){},...dependencies});
  const ctx={...module.exports.data(),...module.exports.methods,orderReady:true,orderId:9,
    orderDataInfo:{orderNumber:'TEST9',orderAmount:7,expireTime:new Date(Date.now()+600000).toISOString()}};
  return {ctx,redirects,notices};
}
test('order page enters success only after backend payment status is confirmed',async()=>{
  const p=page({paymentOrder:async()=>real,getOrderDetail:async()=>({code:1,data:{status:2,payStatus:1}})});
  await p.ctx.handleSave();assert.deepEqual(p.redirects,['/pages/success/index?orderId=9']);assert.equal(p.ctx.paying,false);
});
test('order page routes unresolved paid attempt to details, never fake success',async()=>{
  const p=page({paymentOrder:async()=>real,getOrderDetail:async()=>({code:1,data:{status:6,payStatus:1}})});
  await p.ctx.handleSave();assert.deepEqual(p.redirects,['/pages/details/index?orderId=9']);assert.match(p.notices[0],/已关闭/);
});
