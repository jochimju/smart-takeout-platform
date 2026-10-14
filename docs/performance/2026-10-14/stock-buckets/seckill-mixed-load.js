import http from 'k6/http';
import { sleep } from 'k6';
import exec from 'k6/execution';
import { SharedArray } from 'k6/data';
import { Counter, Trend, Gauge } from 'k6/metrics';
const users=new SharedArray('users',()=>JSON.parse(open(__ENV.USERS_FILE)));
const base=__ENV.BASE_URL||'http://127.0.0.1:18081', run=__ENV.RUN_ID;
if(!run || !/^[A-Za-z0-9_-]{16,40}$/.test(run))throw new Error('Valid RUN_ID required');
const seconds=Number(__ENV.SECONDS||60),rate=Number(__ENV.RATE||800),normalRate=Number(__ENV.NORMAL_RATE||5);
if(rate*seconds>=50000 || normalRate*seconds>600)throw new Error('Isolated user ranges exhausted');
const accepted=new Counter('seckill_accepted'),rejected=new Counter('seckill_capacity_rejected'),other=new Counter('seckill_other_rejected'),unknown=new Counter('seckill_unknown');
const completed=new Counter('seckill_completed'),failed=new Counter('seckill_terminal_failed'),pollTimeout=new Counter('seckill_poll_timeout');
const submit=new Trend('seckill_submit_ms',true),wait=new Trend('seckill_ready_ms',true),startEpoch=new Gauge('seckill_start_epoch_ms');
const normalAccepted=new Counter('normal_accepted'),normalCompleted=new Counter('normal_completed'),normalFailed=new Counter('normal_failed'),normalSubmit=new Trend('normal_submit_ms',true),normalWait=new Trend('normal_ready_ms',true);
export const options={
 systemTags:["status","method","name","scenario","expected_response","error","error_code","check"],scenarios:{
 seckill:{executor:'constant-arrival-rate',exec:'seckill',rate,timeUnit:'1s',duration:`${seconds}s`,preAllocatedVUs:Number(__ENV.SECK_PREALLOCATED||800),maxVUs:Number(__ENV.SECK_MAX_VUS||1200),gracefulStop:'150s'},
 normal:{executor:'constant-arrival-rate',exec:'normal',rate:normalRate,timeUnit:'1s',duration:`${seconds}s`,preAllocatedVUs:50,maxVUs:200,gracefulStop:'150s'}
},thresholds:{dropped_iterations:['count==0']},summaryTrendStats:['avg','med','p(95)','p(99)','max']};
function parse(response){try{return response.json();}catch(_){return null;}}
function ready(data){return data && data.id!=null && data.orderAmount!=null;}
function headers(user){return {'Content-Type':'application/json',authentication:user.token};}
function request(user,id){return {requestId:id,addressBookId:user.addressBookId,payMethod:1,deliveryStatus:1,tablewareNumber:0,tablewareStatus:1,packAmount:1,canteenId:1,useRedPacket:false};}
export function seckill(){
 startEpoch.add(exec.scenario.startTime);
 const user=users[exec.scenario.iterationInTest],id=`${run}_s_${exec.scenario.iterationInTest}`,started=Date.now();
 const response=http.post(base+'/user/order/seckill/submit',JSON.stringify({...request(user,id),activityId:__ENV.ACTIVITY_ID,setmealId:50}),{headers:headers(user),timeout:'10s',tags:{operation:'seckill_submit',name:'seckill_submit'}});
 let result=parse(response);if(!result || result.code!==1 || !result.data){
  if(response.status===0)unknown.add(1);else if(result && result.msg==='秒杀建单繁忙，请稍后重试')rejected.add(1);else other.add(1);return;
 }
 accepted.add(1);submit.add(response.timings.duration);
 while(Date.now()-started<145000){
  if(result && result.code===1 && ready(result.data)){completed.add(1);wait.add(Date.now()-started);return;}
  if(result && result.data && ['FAILED','CANCELLED'].includes(result.data.submitStatus)){failed.add(1);return;}
  sleep(1);
  result=parse(http.get(`${base}/user/order/seckill/result?requestId=${encodeURIComponent(id)}&activityId=${__ENV.ACTIVITY_ID}`,{headers:headers(user),timeout:'10s',tags:{operation:'seckill_poll',name:'seckill_poll'}}));
 }
 pollTimeout.add(1);
}
export function normal(){
 const user=users[50000+exec.scenario.iterationInTest],id=`${run}_n_${exec.scenario.iterationInTest}`,started=Date.now();
 const response=http.post(base+'/user/order/submit',JSON.stringify(request(user,id)),{headers:headers(user),timeout:'10s',tags:{operation:'normal_submit',name:'normal_submit'}});
 let result=parse(response);if(!result || result.code!==1 || !result.data){normalFailed.add(1);return;}
 normalAccepted.add(1);normalSubmit.add(response.timings.duration);
 while(Date.now()-started<145000){
  if(result && result.code===1 && ready(result.data)){normalCompleted.add(1);normalWait.add(Date.now()-started);return;}
  sleep(1);result=parse(http.get(base+'/user/order/submit-result?requestId='+encodeURIComponent(id),{headers:headers(user),timeout:'10s',tags:{operation:'normal_poll',name:'normal_poll'}}));
 }
 normalFailed.add(1);
}
export function handleSummary(data){return {[__ENV.REPORT_FILE]:JSON.stringify({runId:run,rate,normalRate,seconds,startEpochMs:data.metrics.seckill_start_epoch_ms?.values.value,raw:data},null,2)};}
