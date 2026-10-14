const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),test=require('node:test'),assert=require('node:assert/strict');
const root=path.resolve(__dirname,'../../../../../project-rjwm-weixin-uniapp');
const isPaymentOrderReady=vm.runInNewContext(fs.readFileSync(path.join(root,'utils/payment-order.js'),'utf8').replace(/export /g,'')+'\nisPaymentOrderReady');
function payPage(detail,overrides={}) {
  let calls=0;const notices=[];
  const module={exports:{}};
  const script=fs.readFileSync(path.join(root,'pages/pay/index.vue'),'utf8').split('<script>')[1].split('</script>')[0]
    .replace(/^import[\s\S]*?from\s*["'][^"']+["'];?/gm,'').replace('export default','module.exports =');
  vm.runInNewContext(script,{module,mapState:()=>({}),isPaymentOrderReady,getOrderDetail:async()=>{calls++;return {code:1,data:detail};},
    uni:{showToast:notice=>notices.push(notice)},setTimeout:()=>1,clearTimeout() {},...overrides});
  const ctx={...module.exports.data(),...module.exports.methods,orderId:'9'};
  return {ctx,notices,calls:()=>calls};
}
const valid={id:9,number:'SK9',amount:7,status:1,orderTime:new Date().toISOString(),expireTime:new Date(Date.now()+900000).toISOString()};
test('accepted, null, string-null, blank, invalid amount cannot enter payment',()=>{
  for(const order of [null,{id:null,orderAmount:null},{id:'null',orderNumber:'SK9',orderAmount:7},
    {id:9,orderNumber:'SK9',orderAmount:null},{id:9,orderNumber:'SK9',orderAmount:'null'},
    {id:9,orderNumber:'SK9',orderAmount:''},{id:9,orderNumber:'SK9',orderAmount:7,submitStatus:'ACCEPTED'}])
    assert.equal(isPaymentOrderReady(order),false);
  assert.equal(isPaymentOrderReady({id:9,orderNumber:'SK9',orderAmount:0,submitStatus:'SUCCEEDED'}),true);
});
test('payment loads authoritative amount instead of cached acceptance',async()=>{
  const {ctx}=payPage(valid);ctx.orderDataInfo={orderNumber:'SK9',orderAmount:null};
  await ctx.loadPaymentOrder();assert.equal(ctx.orderReady,true);assert.equal(ctx.orderDataInfo.orderAmount,7);assert.match(ctx.rocallTime,/\d\d:\d\d/);
});
test('null route ID cannot query or pay',async()=>{
  const page=payPage(valid);page.ctx.orderId='null';await page.ctx.loadPaymentOrder();
  assert.equal(page.calls(),0);assert.equal(page.ctx.orderReady,false);page.ctx.handleSave();assert.equal(page.calls(),0);
});
test('missing server amount disables payment',async()=>{
  const page=payPage({...valid,amount:null});await page.ctx.loadPaymentOrder();assert.equal(page.ctx.orderReady,false);assert.equal(page.notices.length,1);
});
test('cancelled order disables payment',async()=>{
  const page=payPage({...valid,status:6});await page.ctx.loadPaymentOrder();assert.equal(page.ctx.orderReady,false);assert.match(page.notices[0].title,/待支付/);
});
test('missing deadline cannot crash countdown or enable payment',async()=>{
  const page=payPage({...valid,orderTime:null,expireTime:null});await page.ctx.loadPaymentOrder();assert.equal(page.ctx.orderReady,false);assert.match(page.notices[0].title,/期限/);
});
