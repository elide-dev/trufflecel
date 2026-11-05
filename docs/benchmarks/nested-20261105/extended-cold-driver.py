import datetime,json,os,random,subprocess
from pathlib import Path
from prepared_lifecycle import validate
from representative import FLAGS,sha
root=Path('/home/sam/trufflecel-feedback-20261105')
os.chdir(root);os.sched_setaffinity(0,set(range(2,10)))
base=json.loads((root/'nested-lifecycle-v2-0a48e49/metadata.json').read_text())
assert base['complete']
out=root/'nested-extended-cold-v2';out.mkdir(exist_ok=False)
java=root/'graalvm-ce-25.5.5-elide.24-linux-amd64/bin/java'
jar=root/'trufflecel-nested-lifecycle-v2.jar'
assert sha(java)==base['java_sha256'] and sha(jar)==base['jar_sha256']
meta=dict(base,kind='extended-snapshot-cold',maximum=10_000_000,complete=False,commands=[],captured_at=datetime.datetime.now(datetime.timezone.utc).isoformat(),driver_sha256=sha(Path(__file__)),load_start=os.getloadavg())
meta.pop('load_end',None)
def save():(out/'metadata.json').write_text(json.dumps(meta,indent=2)+'\n')
save()
jobs=[('jvm',e,w,'snapshot','cold',f) for w in ('scalar-wide','proto-policy') for e in ('truffle-interpreter','truffle-jit') for f in range(3)]
random.Random(20261105).shuffle(jobs)
rows=[]
for i,job in enumerate(jobs):
 runtime,engine,workload,entry,mode,fork=job
 command=[str(java),*FLAGS,'-jar',str(jar),engine,workload,entry,mode,'10000000']
 meta['commands'].append(command);save();print(i+1,job,flush=True)
 log=out/('-'.join(map(str,job))+'.log')
 with log.open('w') as stream:subprocess.run(command,stdout=stream,stderr=subprocess.STDOUT,check=True,timeout=300)
 records=[json.loads(line) for line in log.read_text().splitlines() if line.startswith('{')]
 assert len(records)==1
 row=records[0];validate(row,job,10_000_000);row['fork']=fork;rows.append(row)
 (out/'results.partial.json').write_text(json.dumps(rows,indent=2)+'\n')
assert sha(jar)==meta['jar_sha256']
(out/'results.partial.json').rename(out/'results.json')
meta.update(complete=True,load_end=os.getloadavg());save()
