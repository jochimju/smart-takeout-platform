"""Shared validation and low-cardinality stage analysis; never manufacture a missing baseline."""
import re

def prometheus(text):
    result={}
    for line in text.splitlines():
        match=re.fullmatch(r'([a-zA-Z_:][a-zA-Z0-9_:]*)(\{[^}]*\})?\s+([-+0-9.eE]+)',line)
        if match:
            tags=dict(re.findall(r'(\w+)="([^"\\]*)"',match[2] or ''))
            result[(match[1],tuple(sorted(tags.items())))]=float(match[3])
    return result

def stages(before,after):
    a,b=prometheus(before),prometheus(after);rows=[]
    for (name,tags),total in b.items():
        if name!='seckill_profile_seconds_sum':continue
        count_key=('seckill_profile_seconds_count',tags);count=b.get(count_key,0)-a.get(count_key,0)
        if count>0:rows.append(dict(dict(tags),calls=count,averageMs=1000*(total-a.get((name,tags),0))/count))
    return sorted(rows,key=lambda row:row['averageMs'],reverse=True)

def verdict(report):
    metrics=report.get('raw',{}).get('metrics',{})
    values=lambda key:metrics.get(key,{}).get('values',{})
    dropped=values('dropped_iterations').get('count',0)
    unknown=values('unknown').get('count',0)
    complete=not report.get('states',{}).get('ACCEPTED',0) and not report.get('normalStates',{}).get('ACCEPTED',0)
    inventory=report.get('remaining')==report.get('redisStock') and report.get('outstanding')==0 and report.get('duplicates')==0
    executed=values('http_reqs').get('count',0)>0
    valid=report.get('k6Exit')==0 and executed and dropped==0 and unknown==0 and complete and inventory
    tps=report.get('actualCommittedTps')
    return dict(validLoad=valid,actual800TpsPassed=valid and report.get('seconds',0)>=60 and tps is not None and tps>=800,
                independentClient=report.get('client',{}).get('independentVerified',False),
                droppedIterations=dropped,unknownRequests=unknown,
                seckillAcceptance=values('accepted_ms'),normalAcceptance=values('normal_accepted_ms'),
                rejectionCount=values('rejected').get('count',0),normalRejected=values('normal_rejected').get('count',0))

def creation_wait(before,after):
    a,b=prometheus(before),prometheus(after);result={}
    for mode in ('normal','seckill'):
        sums=[(tags,value) for (name,tags),value in b.items() if name=='order_creation_wait_seconds_sum' and dict(tags).get('mode')==mode]
        for tags,value in sums:
            n=b.get(('order_creation_wait_seconds_count',tags),0)-a.get(('order_creation_wait_seconds_count',tags),0)
            if n>0:result[mode]={'commitsObserved':n,'averageMs':1000*(value-a.get(('order_creation_wait_seconds_sum',tags),0))/n}
        for (name,tags),value in b.items():
            t=dict(tags)
            if name=='order_creation_wait_seconds' and t.get('mode')==mode and t.get('quantile')=='0.95' and mode in result:
                result[mode]['rollingP95Ms']=value*1000
                result[mode]['p95Scope']='Meter rolling window; mean and count use run counter deltas'
    return result
