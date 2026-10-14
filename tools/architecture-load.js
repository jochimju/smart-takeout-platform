import http from 'k6/http';
import exec from 'k6/execution';
import {SharedArray} from 'k6/data';
import {Counter,Trend,Gauge} from 'k6/metrics';
const users=new SharedArray('isolated-users',()=>JSON.parse(open(__ENV.USERS_FILE)));
const accepted=new Counter('accepted'),rejected=new Counter('rejected'),unknown=new Counter('unknown'),latency=new Trend('accepted_ms',true);
const normalAccepted=new Counter('normal_accepted'),normalRejected=new Counter('normal_rejected'),normalLatency=new Trend('normal_accepted_ms',true);
const startEpoch=new Gauge('start_epoch_ms');
const rate=Number(__ENV.RATE),seconds=Number(__ENV.SECONDS),run=__ENV.RUN_ID;
const normalRate=Number(__ENV.NORMAL_RATE||5),normalOnly=__ENV.NORMAL_ONLY==='1';
if(rate*seconds>=65000 || !/^[A-Za-z0-9_-]{16,40}$/.test(run))throw Error('Invalid isolated load bounds');
if(normalRate*seconds>=5000)throw Error('Normal fixture bounds exceeded');
const scenarios={
 seckill:{executor:'constant-arrival-rate',exec:'seckill',rate,timeUnit:'1s',duration:`${seconds}s`,preAllocatedVUs:Math.max(100,rate*2),maxVUs:Math.max(400,rate*4)},
 normal:{executor:'constant-arrival-rate',exec:'normal',rate:normalRate,timeUnit:'1s',duration:`${seconds}s`,preAllocatedVUs:Math.max(20,normalRate*2),maxVUs:Math.max(100,normalRate*4)}
};
if(normalOnly)delete scenarios.seckill;
export const options={scenarios,thresholds:{dropped_iterations:['count==0']},summaryTrendStats:['avg','med','p(95)','p(99)','max']};
function submit(index,kind){
 const user=users[index];const body={requestId:`${run}_${kind}_${index}`,addressBookId:user.addressBookId,payMethod:1,deliveryStatus:1,tablewareNumber:0,tablewareStatus:1,useRedPacket:false};
 if(kind==='s'){body.activityId=__ENV.ACTIVITY_ID;body.setmealId=__ENV.SETMEAL_ID;}
 const response=http.post(__ENV.BASE_URL+'/user/order/'+(kind==='s'?'seckill/submit':__ENV.NORMAL_MODE==='sync'?'benchmark/sync':'submit'),JSON.stringify(body),{headers:{authentication:user.token,'Content-Type':'application/json'},timeout:'10s',tags:{name:kind==='s'?'seckill_submit':'normal_submit'}});
 let result;try{result=response.json();}catch(_){}
 if(result && result.code===1 && result.data){if(kind==='s'){accepted.add(1);latency.add(response.timings.duration);}else{normalAccepted.add(1);normalLatency.add(response.timings.duration);}}
 else if(kind==='s'){if(response.status===0)unknown.add(1);else rejected.add(1);}else normalRejected.add(1);
}
export function seckill(){startEpoch.add(exec.scenario.startTime);submit(exec.scenario.iterationInTest,'s');}
export function normal(){startEpoch.add(exec.scenario.startTime);submit(65000+exec.scenario.iterationInTest,'n');}
export function handleSummary(data){return {[__ENV.REPORT_FILE]:JSON.stringify(data,null,2)};}
