"""Run from the exported directory on a separate load machine. Standard library only.
Supply an appropriate k6 executable (PATH or --k6); send report JSON and client JSON back to the server.
"""
import argparse,json,pathlib,platform,socket,subprocess,time,os
def main():
 p=argparse.ArgumentParser();p.add_argument('config',type=pathlib.Path);p.add_argument('--k6',default='k6');args=p.parse_args()
 cfg=json.loads(args.config.read_text());folder=args.config.resolve().parent
 env=dict(cfg['variables']);env['USERS_FILE']=str(folder/'users.json');env['REPORT_FILE']=str(folder/cfg['reportName'])
 cmd=[args.k6,'run','--quiet']
 for key,val in env.items():cmd+=['--env',key+'='+str(val)]
 cmd+=[str(folder/'architecture-load.js')]
 started=time.time();result=subprocess.run(cmd,cwd=folder)
 metadata={'host':socket.gethostname(),'platform':platform.platform(),'started':started,'ended':time.time(),'exitCode':result.returncode,
  'independentVerified':False,'note':'Hostname alone is not proof of a separate machine; verify deployment externally.'}
 (folder/(cfg['reportName']+'.client.json')).write_text(json.dumps(metadata,indent=2));raise SystemExit(result.returncode)
if __name__=='__main__':main()
