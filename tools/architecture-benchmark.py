"""Isolated actual-order benchmark. Never changes business schemas or global durability settings.
Usage: python tools/architecture-benchmark.py setup|run|cleanup [run_folder] [rate] [seconds]
Requires local pymysql, requests, PyJWT, PyYAML and psutil (bundled test dependency folder supported).
"""
import pathlib,sys,json,re,time,uuid,socket,subprocess,hashlib,threading,datetime,shutil,os,platform,zipfile,types
ROOT=pathlib.Path(__file__).resolve().parents[1]; WORKSPACE=ROOT.parent
sys.path.insert(0,str(WORKSPACE/'.runtime/perf-python'))
import pymysql,requests,jwt,yaml,psutil
from performance_contract import stages,verdict,creation_wait
def value(v):return re.sub(r'\$\{[^:}]+:([^}]+)\}',r'\1',str(v))
settings=yaml.safe_load((ROOT/'trade-service/src/main/resources/application-dev.yml').read_text(encoding='utf8'))['sky']
d=settings['datasource']; dbargs=dict(host=value(d['host']),port=int(value(d['port'])),user=value(d['username']),password=value(d['password']),autocommit=True)
java=pathlib.Path('C:/Users/25692/.jdks/corretto-17.0.14/bin/java.exe')
action=sys.argv[1]
def connection(database=None):return pymysql.connect(database=database,**dbargs)
def rabbit():
 r=settings['rabbitmq'];s=requests.Session();s.auth=(value(r['username']),value(r['password']));return s,'http://'+value(r['host'])+':15672/api'
def notification_topology(vhost):
 s,url=rabbit()
 for exchange in ('sky.notification.events','sky.notification.dlx'):
  s.put(url+'/exchanges/'+vhost+'/'+exchange,json={'type':'direct','durable':True,'auto_delete':False,'internal':False,'arguments':{}},timeout=10).raise_for_status()
 for name,args in [('sky.notification.order',{'x-dead-letter-exchange':'sky.notification.dlx','x-dead-letter-routing-key':'dead'}),('sky.notification.dead',{})]:
  s.put(url+'/queues/'+vhost+'/'+name,json={'durable':True,'auto_delete':False,'arguments':args},timeout=10).raise_for_status()
 for exchange,queue,key in [('sky.notification.events','sky.notification.order','notification.order'),('sky.notification.dlx','sky.notification.dead','dead')]:
  s.post(url+'/bindings/'+vhost+'/e/'+exchange+'/q/'+queue,json={'routing_key':key,'arguments':{}},timeout=10).raise_for_status()
def checked_folder(raw):
 f=pathlib.Path(raw).resolve();assert f.parent==(ROOT/'.runtime/architecture-perf').resolve();return f
def wait_health(port):
 for _ in range(90):
  try:
   if requests.get(f'http://127.0.0.1:{port}/actuator/health',timeout=2).status_code==200:return
  except requests.RequestException:pass
  time.sleep(1)
 raise RuntimeError('Isolated service startup failed; inspect saved service logs')
if action=='setup':
 if shutil.disk_usage(ROOT.anchor).free < 2*1024**3 or shutil.disk_usage(pathlib.Path.home().anchor).free < 128*1024**2:
  raise RuntimeError('Benchmark requires 2 GiB on the workspace volume and 128 MiB on the user runtime volume')
 ports={'trade':18181,'account':18182,'catalog':18183,'redis':16590}
 for port in ports.values():
  with socket.socket() as sock:sock.bind(('127.0.0.1',port))
 run='sky_arch_'+time.strftime('%Y%m%d%H%M%S')+'_'+uuid.uuid4().hex[:4]
 folder=ROOT/'.runtime/architecture-perf'/run;folder.mkdir(parents=True)
 databases={svc:run+'_'+svc for svc in ('trade','account','catalog')}
 manifest=dict(run=run,databases=databases,vhost=run,ports=ports,pids={},jarSha256={},server={'host':socket.gethostname(),'platform':platform.platform(),'logicalCpus':psutil.cpu_count(),'memoryBytes':psutil.virtual_memory().total})
 def save(): (folder/'manifest.json').write_text(json.dumps(manifest,indent=2))
 save()
 with connection() as c,c.cursor() as q:
  for svc,dest in databases.items():
   local=yaml.safe_load((ROOT/f'{svc}-service/src/main/resources/application-dev.yml').read_text(encoding='utf8'))
   main=yaml.safe_load((ROOT/f'{svc}-service/src/main/resources/application.yml').read_text(encoding='utf8'))
   configured=local.get('sky',{}).get('datasource',{}).get('database') or main.get('sky',{}).get('datasource',{}).get('database')
   source=value(configured) if configured else re.search(r'/([^/?]+)\?',value(main['spring']['datasource']['druid']['url'])).group(1)
   assert dest.startswith('sky_arch_') and dest!=source
   q.execute('create database `'+dest+'` character set utf8mb4');q.execute('select table_name from information_schema.tables where table_schema=%s',(source,))
   tables=[row[0] for row in q.fetchall()]
   for table in tables:
    assert re.fullmatch(r'[A-Za-z0-9_]+',table)
    q.execute(f'create table `{dest}`.`{table}` like `{source}`.`{table}`')
    if table=='flyway_schema_history' or (svc=='catalog' and table in ('canteen','category','dish','setmeal','setmeal_dish','dish_flavor')):
     q.execute(f'insert into `{dest}`.`{table}` select * from `{source}`.`{table}`')
  cat=databases['catalog'];q.execute(f'select id,canteen_id from `{cat}`.setmeal where status=1 order by id limit 1');product=q.fetchone();assert product
  manifest['setmealId'],manifest['canteenId']=product;save()
  q.execute(f'update `{cat}`.setmeal set stock=600000 where id=%s',(product[0],))
  account=databases['account']
  for start in range(1,70001,1000):
   ids=range(start,start+1000)
   q.executemany(f'insert into `{account}`.`user`(id,openid,name,create_time) values(%s,%s,%s,%s)',[(i,'architecture_'+str(i),'IsolatedUser',datetime.datetime.now()) for i in ids])
   q.executemany(f'insert into `{account}`.address_book(id,user_id,consignee,phone,detail,is_default) values(%s,%s,%s,%s,%s,%s)',[(i,i,'IsolatedUser','13800000000','Test address',1) for i in ids])
  trade=databases['trade'];q.executemany(f'insert into `{trade}`.shopping_cart(user_id,setmeal_id,name,number,amount,create_time,canteen_id) values(%s,%s,%s,%s,%s,%s,%s)',[(i,product[0],'IsolatedCart',1,1,datetime.datetime.now(),product[1]) for i in range(65001,66001)])
 s,url=rabbit();s.put(url+'/vhosts/'+run,json={},timeout=10).raise_for_status()
 s.put(url+'/permissions/'+run+'/'+s.auth[0],json={'configure':'.*','write':'.*','read':'.*'},timeout=10).raise_for_status()
 notification_topology(run)
 secret='architecture-isolated-user-secret'
 users=[dict(token=jwt.encode({'userId':i,'jti':'arch_'+str(i),'exp':int(time.time())+21600},secret,algorithm='HS256'),addressBookId=str(i)) for i in range(1,70001)]
 (folder/'users.json').write_text(json.dumps(users))
 redislog=(folder/'redis.log').open('w');redis=subprocess.Popen([str(WORKSPACE/'Redis-x64-3.2.100/redis-server.exe'),'--port',str(ports['redis']),'--bind','127.0.0.1','--save','','--appendonly','no','--dir',str(folder)],stdout=redislog,stderr=redislog,creationflags=0x08000000)
 manifest['pids']['redis']=redis.pid;save();time.sleep(1)
 sys.path.insert(0,str(ROOT/'tools'));from importlib import import_module
 redisclient=import_module('seckill-stage-observe').Redis(ports['redis'])
 shop_bytes=bytes.fromhex('aced0005737200116a6176612e6c616e672e496e746567657212e2a0a4f781873802000149000576616c7565787200106a6176612e6c616e672e4e756d62657286ac951d0b94e08b020000787000000001')
 with socket.create_connection(('127.0.0.1',ports['redis'])) as sock:
  parts=[b'SET',b'SHOP_STATUS',shop_bytes];sock.sendall(b'*3\r\n'+b''.join(b'$%d\r\n'%len(part)+part+b'\r\n' for part in parts));assert sock.recv(100)==b'+OK\r\n'
 for svc in ('account','catalog','trade'):
  cfg={'server.port':ports[svc],'spring.config.import':'','spring.cloud.nacos.discovery.enabled':False,'spring.cloud.nacos.config.enabled':False,'spring.cloud.sentinel.enabled':False,
   'sky.jwt.user-secret-key':secret,'sky.internal.token':'architecture-isolated-internal','sky.datasource.database':databases[svc],
   'sky.redis.port':ports['redis'],'sky.redis.database':0,'sky.redis.password':'','spring.data.redis.port':ports['redis'],'spring.data.redis.database':0,'spring.data.redis.password':'',
   'sky.rabbitmq.virtual-host':run,'spring.rabbitmq.virtual-host':run,'sky.payment.mock-enabled':True,'sky.cache.warmup-enabled':True,
   'logging.level.com.sky':'WARN','logging.level.com.sky.mapper':'WARN','logging.level.com.sky.service':'WARN','logging.level.com.sky.controller':'WARN','sky.seckill.fulfill-batch-size':16,'sky.seckill.profile-enabled':os.getenv('ARCH_PROFILE')=='1','sky.benchmark.enabled':True,'sky.seckill.publisher-workers':2}
  for key in ('spring.datasource.url','spring.datasource.druid.url'):cfg[key]=f'jdbc:mysql://{dbargs["host"]}:{dbargs["port"]}/{databases[svc]}?serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true'
  for key in ('spring.datasource.username','spring.datasource.druid.username'):cfg[key]=dbargs['user']
  for key in ('spring.datasource.password','spring.datasource.druid.password'):cfg[key]=dbargs['password']
  for peer in ('account','catalog','trade'):cfg[f'spring.cloud.openfeign.client.config.{peer}-service.url']=f'http://127.0.0.1:{ports[peer]}'
  config=folder/(svc+'.yml');config.write_text(yaml.safe_dump(cfg),encoding='utf8')
  source_jar=ROOT/f'{svc}-service/target/{svc}-service-1.0-SNAPSHOT.jar';assert source_jar.exists();jar=folder/(svc+'.jar');shutil.copyfile(source_jar,jar);manifest['jarSha256'][svc]=hashlib.sha256(jar.read_bytes()).hexdigest()
  log=(folder/(svc+'.log')).open('w')
  proc=subprocess.Popen([str(java),'-Dfile.encoding=UTF-8','-Xms256m','-Xmx768m','-jar',str(jar),'--spring.profiles.active=dev,benchmark','--spring.config.additional-location='+config.as_uri(),'--spring.config.import='],stdout=log,stderr=log,creationflags=0x08000000)
  manifest['pids'][svc]=proc.pid;save();wait_health(ports[svc])
 print(str(folder),flush=True)
elif action=='run':
 folder=checked_folder(sys.argv[2]);m=json.loads((folder/'manifest.json').read_text());rate=int(sys.argv[3]);seconds=int(sys.argv[4]);assert rate*seconds<65000
 database=m['databases']['trade'];assert database.startswith('sky_arch_');label='arch_'+uuid.uuid4().hex[:16]
 normal_rate=int(os.getenv('ARCH_NORMAL_RATE','5'));assert normal_rate*seconds<5000
 with connection(database) as c,c.cursor() as q:
  q.execute('select count(*) from seckill_submit_request where state="ACCEPTED"');assert q.fetchone()[0]==0
  q.execute('select count(*) from order_submit_request where state="ACCEPTED" and command_body is not null');assert q.fetchone()[0]==0
  q.execute('delete from shopping_cart where user_id between 65001 and %s',(65000+normal_rate*seconds+10,))
  q.executemany('insert into shopping_cart(user_id,setmeal_id,name,number,amount,create_time,canteen_id) values(%s,%s,%s,%s,%s,%s,%s)',[(i,m['setmealId'],'IsolatedCart',1,1,datetime.datetime.now(),m['canteenId']) for i in range(65001,65001+normal_rate*seconds+10)])
  if os.getenv('ARCH_NORMAL_ONLY')=='1':
   q.execute('select max(id) from seckill_activity');activity=q.fetchone()[0];assert activity,'Run a seckill smoke test first'
  else:
   q.execute('insert into seckill_activity(setmeal_id,stock,remaining_stock,stock_version,purchase_limit,seckill_price,begin_time,end_time,status,create_time,update_time,async_initialized) values(%s,80000,80000,0,1,1.00,now()-interval 1 hour,now()+interval 4 hour,1,now(),now(),0)',(m['setmealId'],));activity=q.lastrowid
 sys.path.insert(0,str(ROOT/'tools'));from importlib import import_module
 redisclient=import_module('seckill-stage-observe').Redis(m['ports']['redis']);prefix='seckill:v3:{'+str(activity)+'}:'
 for _ in range(50):
  if redisclient.command('HGET',prefix+'meta','ready')=='1':break
  time.sleep(1)
 else:raise RuntimeError('Activity not ready; inspect trade log')
 def metrics():
  response=requests.get(f'http://127.0.0.1:{m["ports"]["trade"]}/actuator/prometheus',headers={'X-Internal-Token':'architecture-isolated-internal'},timeout=10);response.raise_for_status();return response.text
 before_metrics=metrics();(folder/(label+'-metrics-before.txt')).write_text(before_metrics)
 samples=[];stop=threading.Event();psutil.cpu_percent()
 def observe():
  with connection(database) as c,c.cursor() as q:
   while not stop.is_set():
    q.execute('select state,count(*) from seckill_submit_request where activity_id=%s group by state',(activity,));row={'time':time.time(),'states':dict(q.fetchall()),'cpuPercent':psutil.cpu_percent(),'availableMemoryBytes':psutil.virtual_memory().available}
    if len(samples)%5==0:
     q.execute('select routing_key,status,count(*) from mq_fail_message group by routing_key,status');row['publication']=q.fetchall()
     q.execute('select l.OBJECT_NAME,count(*) from performance_schema.data_lock_waits w join performance_schema.data_locks l on w.REQUESTING_ENGINE_LOCK_ID=l.ENGINE_LOCK_ID and w.ENGINE=l.ENGINE where l.OBJECT_SCHEMA=%s group by l.OBJECT_NAME',(database,));row['lockWaits']=q.fetchall()
    samples.append(row);stop.wait(1)
 observer=threading.Thread(target=observe);observer.start()
 output=folder/(label+'.json');cmd=[str(WORKSPACE/'.runtime/k6/k6-v2.3.0-windows-amd64/k6.exe'),'run','--quiet']
 variables={'USERS_FILE':str(folder/'users.json'),'RUN_ID':label,'BASE_URL':f'http://127.0.0.1:{m["ports"]["trade"]}','RATE':str(rate),'SECONDS':str(seconds),'ACTIVITY_ID':str(activity),'SETMEAL_ID':str(m['setmealId']),'REPORT_FILE':str(output)}
 variables.update(NORMAL_RATE=str(normal_rate),NORMAL_ONLY=os.getenv('ARCH_NORMAL_ONLY','0'),NORMAL_MODE=os.getenv('ARCH_NORMAL_MODE','async'))
 for key,val in variables.items():cmd+=['--env',key+'='+val]
 cmd+=[str(ROOT/'tools/architecture-load.js')]
 start=time.time()
 if os.getenv('ARCH_REMOTE')=='1':
  assert os.getenv('ARCH_BASE_URL','').startswith(('http://','https://')),'Provide a service URL reachable from the load machine'
  variables['BASE_URL']=os.environ['ARCH_BASE_URL'];bundle=folder/(label+'-remote.zip')
  config={'variables':variables,'reportName':output.name};config_file=folder/(label+'-remote-config.json');config_file.write_text(json.dumps(config,indent=2))
  with zipfile.ZipFile(bundle,'w',zipfile.ZIP_DEFLATED) as z:
   for file,name in [(config_file,'config.json'),(folder/'users.json','users.json'),(ROOT/'tools/architecture-load.js','architecture-load.js'),(ROOT/'tools/architecture-remote-runner.py','architecture-remote-runner.py')]:z.write(file,name)
  print('Remote bundle ready: '+str(bundle)+'; return '+output.name+' and its .client.json to this directory',flush=True)
  wait_until=time.monotonic()+seconds+300
  while not (output.exists() and pathlib.Path(str(output)+'.client.json').exists()) and time.monotonic()<wait_until:time.sleep(1)
  if not output.exists():stop.set();observer.join();raise RuntimeError('No remote result received; no independent-host claim')
  client_metadata=json.loads(pathlib.Path(str(output)+'.client.json').read_text());result=types.SimpleNamespace(returncode=client_metadata['exitCode'])
 else:
  with (folder/(label+'-k6.log')).open('w') as log:result=subprocess.run(cmd,stdout=log,stderr=log)
  client_metadata={'host':socket.gethostname(),'independentVerified':False,'mode':'same-machine'}
 end=time.time();stop.set();observer.join();raw=json.loads(output.read_text()) if output.exists() else {}
 deadline=time.monotonic()+145
 with connection(database) as c,c.cursor() as q:
  while True:
   q.execute('select state,count(*) from seckill_submit_request where activity_id=%s group by state',(activity,));states=dict(q.fetchall())
   q.execute('select state,count(*) from order_submit_request where request_id like %s group by state',(label+'_n_%',));normal_states=dict(q.fetchall())
   if (states.get('ACCEPTED',0)==0 and normal_states.get('ACCEPTED',0)==0) or time.monotonic()>deadline:break
   time.sleep(2)
  q.execute('select count(*) from seckill_order_guard where activity_id=%s',(activity,));guards=q.fetchone()[0]
  q.execute('select count(*) from seckill_submit_request r join orders o on o.number=r.order_number where r.activity_id=%s and r.state="SUCCEEDED"',(activity,));actual_orders=q.fetchone()[0]
  q.execute('select count(*) from (select user_id from seckill_order_guard where activity_id=%s group by user_id having count(*)>1) d',(activity,));duplicates=q.fetchone()[0]
  q.execute('select sum(remaining_stock) from seckill_stock_bucket where activity_id=%s',(activity,));remaining=int(q.fetchone()[0] or 0)
  q.execute('select count(*) from order_submit_request where request_id like %s and state="SUCCEEDED"',(label+'_n_%',));normal=q.fetchone()[0]
  q.execute('select count(*) from order_submit_request where request_id like %s and state="FAILED"',(label+'_n_%',));normal_failed=q.fetchone()[0]
 load_start=raw.get('metrics',{}).get('start_epoch_ms',{}).get('values',{}).get('value',start*1000)/1000
 eligible=[s for s in samples if load_start+5<=s['time']<=load_start+seconds]
 tps=(eligible[-1]['states'].get('SUCCEEDED',0)-eligible[0]['states'].get('SUCCEEDED',0))/(eligible[-1]['time']-eligible[0]['time']) if len(eligible)>1 else None
 cfg=yaml.safe_load((folder/'trade.yml').read_text());tested_settings={key:val for key,val in cfg.items() if key.startswith('sky.seckill.')}
 assert actual_orders==states.get('SUCCEEDED',0)==guards, 'Success ledger and actual orders/qualification differ'
 assert remaining+actual_orders==80000 and duplicates==0, 'Inventory conservation or user uniqueness failed'
 after_metrics=metrics();(folder/(label+'-metrics-after.txt')).write_text(after_metrics)
 report=dict(label=label,activity=activity,rate=rate,seconds=seconds,k6Exit=result.returncode,started=start,ended=end,samples=samples,actualCommittedTps=tps,states=states,guards=guards,duplicates=duplicates,remaining=remaining,redisStock=int(redisclient.command('GET',prefix+'stock')),outstanding=int(redisclient.command('HLEN',prefix+'outstanding')),normalSucceeded=normal,normalFailed=normal_failed,normalStates=normal_states,jarSha256=m['jarSha256'],testedSettings=tested_settings,raw=raw,client=client_metadata,server=m.get('server'),stageTimings=stages(before_metrics,after_metrics),workload={k:variables[k] for k in ('NORMAL_RATE','NORMAL_ONLY','NORMAL_MODE')},loadScriptSha256=hashlib.sha256((ROOT/'tools/architecture-load.js').read_bytes()).hexdigest())
 report['acceptanceVerdict']=verdict(report)
 report['creationWait']=creation_wait(before_metrics,after_metrics)
 (folder/(label+'-verification.json')).write_text(json.dumps(report,indent=2));print(json.dumps({key:report[key] for key in ('label','actualCommittedTps','states','guards','duplicates','remaining','redisStock','outstanding','normalSucceeded','normalFailed','k6Exit')}),flush=True)
elif action=='flow':
 folder=checked_folder(sys.argv[2]);m=json.loads((folder/'manifest.json').read_text());user=json.loads((folder/'users.json').read_text())[64001]
 with connection(m['databases']['trade']) as c,c.cursor() as q:
  q.execute('select max(id) from seckill_activity');activity=q.fetchone()[0]
 headers={'authentication':user['token']};base=f'http://127.0.0.1:{m["ports"]["trade"]}/user/order'
 def command(key):return dict(requestId=key,addressBookId=user['addressBookId'],activityId=activity,setmealId=m['setmealId'],useRedPacket=False,tablewareNumber=0,tablewareStatus=1)
 def submit(body):return requests.post(base+'/seckill/submit',json=body,headers=headers,timeout=10).json()
 def settled(key):
  for _ in range(100):
   r=requests.get(base+'/seckill/result',params={'requestId':key,'activityId':activity},headers=headers,timeout=10).json();d=r.get('data') or {}
   if d.get('submitStatus')=='SUCCEEDED' and d.get('id'):return d
   if d.get('submitStatus') in ('FAILED','CANCELLED'):raise AssertionError(d)
   time.sleep(.1)
  raise AssertionError('Order did not settle')
 first='flow_'+uuid.uuid4().hex;assert submit(command(first))['code']==1;order=settled(first)
 assert submit(command(first))['code']==1 and settled(first)['id']==order['id']
 duplicate='flow_'+uuid.uuid4().hex;assert submit(command(duplicate))['code']!=1
 for _ in range(2):assert requests.put(base+'/cancel/'+str(order['id']),headers=headers,timeout=10).json()['code']==1
 second='flow_'+uuid.uuid4().hex
 for _ in range(100):
  if submit(command(second))['code']==1:break
  time.sleep(.1)
 else:raise AssertionError('Cancellation did not release qualification')
 replacement=settled(second);assert replacement['id']!=order['id']
 with connection(m['databases']['trade']) as c,c.cursor() as q:
  user_id=jwt.decode(user['token'],options={'verify_signature':False})['userId']
  q.execute('select count(*) from seckill_order_guard where activity_id=%s and user_id=%s',(activity,user_id));assert q.fetchone()[0]==1
 report={'requestReplaySameOrder':True,'secondActiveOrderRejected':True,'repeatCancellationIdempotent':True,'cancelThenRebuySucceeded':True,'firstOrderId':order['id'],'replacementOrderId':replacement['id']}
 (folder/'flow-verification.json').write_text(json.dumps(report,indent=2));print(json.dumps(report),flush=True)
elif action=='cache-mixed':
 import random
 folder=checked_folder(sys.argv[2]);m=json.loads((folder/'manifest.json').read_text());database=m['databases']['catalog']
 with connection(database) as c,c.cursor() as q:
  q.execute("select 'dish',category_id from dish where status=1 group by category_id union all select 'setmeal',category_id from setmeal where status=1 group by category_id");categories=q.fetchall()
 assert {r[0] for r in categories}=={'dish','setmeal'},'Mixed test requires both menu types'
 rng=random.Random(20261014);hot=[next(r for r in categories if r[0]==kind) for kind in ('dish','setmeal')]
 workload=[rng.choice(hot if i%5 else categories) for i in range(200)]
 sys.path.insert(0,str(ROOT/'tools'));from importlib import import_module
 redisclient=import_module('seckill-stage-observe').Redis(m['ports']['redis']);token=json.loads((folder/'users.json').read_text())[0]['token']
 def query(kind,category):
  response=requests.get(f'http://127.0.0.1:{m["ports"]["catalog"]}/user/{kind}/list',params={'categoryId':category},headers={'authentication':token},timeout=10);response.raise_for_status();assert response.json()['code']==1
 def counter():
  with connection() as c,c.cursor() as q:
   q.execute("select coalesce(sum(COUNT_STAR),0) from performance_schema.events_statements_summary_by_digest where SCHEMA_NAME=%s and lower(DIGEST_TEXT) like 'select%%' and (lower(DIGEST_TEXT) like '%%dish%%' or lower(DIGEST_TEXT) like '%%setmeal%%')",(database,));return int(q.fetchone()[0])
 before=counter()
 for kind,category in workload:redisclient.command('INCR',kind+':cache:version');query(kind,category)
 baseline=counter()-before
 for kind in ('dish','setmeal'):redisclient.command('INCR',kind+':cache:version')
 for kind,category in hot:query(kind,category)
 before=counter()
 for kind,category in workload:query(kind,category)
 cached=counter()-before;assert baseline>0
 report={'requests':200,'seed':20261014,'hotSelectionFraction':.8,'categories':len(categories),'menuTypes':['dish','setmeal'],'baselineSelects':baseline,'cachedSelects':cached,'selectReduction':1-cached/baseline,'platformWideClaim':False,'note':'Controlled menu mixture; hot categories prewarmed, remaining categories cold initially. Does not measure all database traffic.'}
 (folder/'cache-mixed-verification.json').write_text(json.dumps(report,indent=2));print(json.dumps(report),flush=True)
elif action=='cache':
 folder=checked_folder(sys.argv[2]);m=json.loads((folder/'manifest.json').read_text());database=m['databases']['catalog'];assert database.startswith('sky_arch_')
 with connection(database) as c,c.cursor() as q:
  q.execute('select category_id from setmeal where id=%s',(m['setmealId'],));category=q.fetchone()[0]
 sys.path.insert(0,str(ROOT/'tools'));from importlib import import_module
 redisclient=import_module('seckill-stage-observe').Redis(m['ports']['redis']);token=json.loads((folder/'users.json').read_text())[0]['token']
 endpoint=f'http://127.0.0.1:{m["ports"]["catalog"]}/user/setmeal/list?categoryId={category}'
 def counter():
  with connection() as c,c.cursor() as q:
   q.execute("select coalesce(sum(COUNT_STAR),0) from performance_schema.events_statements_summary_by_digest where SCHEMA_NAME=%s and lower(DIGEST_TEXT) like 'select%%' and lower(DIGEST_TEXT) like '%%setmeal%%'",(database,));return int(q.fetchone()[0])
 def query():
  response=requests.get(endpoint,headers={'authentication':token},timeout=5);response.raise_for_status();assert response.json()['code']==1
 before=counter()
 for _ in range(100):redisclient.command('INCR','setmeal:cache:version');query()
 baseline=counter()-before;before=counter()
 for _ in range(100):query()
 cached=counter()-before
 assert baseline>0,'SQL digest counters unavailable; do not infer pressure reduction'
 report={'scenario':'100 same-category reads; baseline forces a cache miss per read; warm group reuses cache','baselineSelects':baseline,'cachedSelects':cached,'selectReduction':1-cached/baseline,'productionClaim':False}
 (folder/'cache-verification.json').write_text(json.dumps(report,indent=2));print(json.dumps(report),flush=True)
elif action=='restart':
 folder=checked_folder(sys.argv[2]);m=json.loads((folder/'manifest.json').read_text());database=m['databases']['trade'];assert database.startswith('sky_arch_')
 with connection(database) as c,c.cursor() as q:
  q.execute("select count(*) from seckill_submit_request where state='ACCEPTED'");assert q.fetchone()[0]==0
  q.execute("select count(*) from seckill_cache_event");assert q.fetchone()[0]==0
 notification_topology(m['vhost'])
 for svc in (('trade',) if '--trade-only' in sys.argv else ('trade','catalog','account')):
  try:
   process=psutil.Process(m['pids'][svc]);assert (folder/(svc+'.yml')).as_uri() in ' '.join(process.cmdline());process.terminate();process.wait(30)
  except psutil.NoSuchProcess:pass
 with socket.create_connection(('127.0.0.1',m['ports']['redis'])) as sock:
  parts=[b'SET',b'SHOP_STATUS',bytes.fromhex('aced0005737200116a6176612e6c616e672e496e746567657212e2a0a4f781873802000149000576616c7565787200106a6176612e6c616e672e4e756d62657286ac951d0b94e08b020000787000000001')]
  sock.sendall(b'*3\r\n'+b''.join(b'$%d\r\n'%len(part)+part+b'\r\n' for part in parts));assert sock.recv(100)==b'+OK\r\n'
 for svc in (('trade',) if '--trade-only' in sys.argv else ('account','catalog','trade')):
  file=folder/(svc+'.yml');cfg=yaml.safe_load(file.read_text());
  for component in ('mapper','service','controller'):cfg['logging.level.com.sky.'+component]='WARN'
  cfg['sky.seckill.profile-enabled']=os.getenv('ARCH_PROFILE')=='1'
  cfg['sky.benchmark.enabled']=True;cfg['sky.order.publisher-enabled']=os.getenv('ARCH_NORMAL_MODE')!='sync'
  if len(sys.argv)>3:cfg['sky.seckill.max-outstanding']=int(sys.argv[3])
  file.write_text(yaml.safe_dump(cfg),encoding='utf8');source_jar=ROOT/f'{svc}-service/target/{svc}-service-1.0-SNAPSHOT.jar';jar=folder/(svc+'.jar');shutil.copyfile(source_jar,jar)
  artifact_hash=hashlib.sha256(jar.read_bytes()).hexdigest()
  if '--updated' in sys.argv:
   m.setdefault('buildHistory',[]).append({'service':svc,'priorSha256':m['jarSha256'][svc],'newSha256':artifact_hash,'time':time.time()});m['jarSha256'][svc]=artifact_hash
  else:assert artifact_hash==m['jarSha256'][svc]
  log=(folder/(svc+'.log')).open('a');proc=subprocess.Popen([str(java),'-Dfile.encoding=UTF-8','-Xms256m','-Xmx768m','-jar',str(jar),'--spring.profiles.active=dev,benchmark','--spring.config.additional-location='+file.as_uri(),'--spring.config.import='],stdout=log,stderr=log,creationflags=0x08000000)
  m['pids'][svc]=proc.pid;(folder/'manifest.json').write_text(json.dumps(m,indent=2));wait_health(m['ports'][svc])
 print('Isolated services restarted with bounded queue settings and SQL debug disabled',flush=True)
elif action=='stop':
 folder=checked_folder(sys.argv[2]);m=json.loads((folder/'manifest.json').read_text());assert m['run'].startswith('sky_arch_')
 for svc in ('trade','catalog','account'):
  try:
   process=psutil.Process(m['pids'][svc]);assert (folder/(svc+'.yml')).as_uri() in ' '.join(process.cmdline());process.terminate();process.wait(30)
  except psutil.NoSuchProcess:pass
 print('Isolated Java services stopped; database, Redis and reports retained',flush=True)
elif action=='cleanup':
 folder=checked_folder(sys.argv[2]);m=json.loads((folder/'manifest.json').read_text());assert m['run'].startswith('sky_arch_')
 for svc,pid in m['pids'].items():
  try:
   process=psutil.Process(pid);cmd=' '.join(process.cmdline())
   assert str(folder) in cmd or pathlib.Path(folder).as_uri() in cmd
   process.terminate();process.wait(30)
  except psutil.NoSuchProcess:pass
 s,url=rabbit();response=s.delete(url+'/vhosts/'+m['vhost'],timeout=10);assert response.status_code in (204,404)
 with connection() as c,c.cursor() as q:
  for database in m['databases'].values():assert database.startswith(m['run']+'_');q.execute('drop database if exists `'+database+'`')
 for file in [folder/'users.json']+[folder/(svc+'.yml') for svc in ('account','catalog','trade')]:file.unlink(missing_ok=True)
 (folder/'cleanup.json').write_text(json.dumps({'isolatedProcessesStopped':True,'isolatedDatabasesRemoved':True,'isolatedVhostRemoved':True,'credentialFilesRemoved':True}));print('Isolated resources cleaned; reports retained')
else:raise ValueError('Unknown action')
