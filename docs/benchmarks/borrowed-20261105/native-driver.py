import datetime,json,os,platform,random,subprocess
from pathlib import Path
from representative import NATIVE_FLAGS,sha,validate_lifecycle
root=Path('/home/sam/trufflecel-feedback-20261105')
os.chdir(root);os.sched_setaffinity(0,set(range(2,10)))
base=json.loads((root/'borrowed-jvm-v1/metadata.json').read_text());assert base['complete']
out=root/'borrowed-native-v1';out.mkdir(exist_ok=False)
binary=root/'trufflecel-borrowed-native-v1'
meta=dict(base,kind='targeted-native',captured_at=datetime.datetime.now(datetime.timezone.utc).isoformat(),native_sha256=sha(binary),driver_sha256=sha(Path(__file__)),commands=[],load_start=os.getloadavg(),complete=False)
meta.pop('load_end',None)
def save():(out/'metadata.json').write_text(json.dumps(meta,indent=2)+'\n')
save()
jobs=[(e,w,f) for e in ('truffle-interpreter','truffle-jit','cel-java-optimized') for w in ('scalar-wide','proto-policy','optimizer-policy') for f in range(3)]
random.Random(20261105).shuffle(jobs)
rows=[]
for i,(engine,workload,fork) in enumerate(jobs):
 print(f'{i+1}/{len(jobs)} {engine}/{workload}/{fork}',flush=True)
 command=[str(binary),*NATIVE_FLAGS,engine,workload,'steady']
 meta['commands'].append(command);save()
 log=out/f'{engine}-{workload}-{fork}.log'
 with log.open('w') as stream:subprocess.run(command,stdout=stream,stderr=subprocess.STDOUT,check=True,timeout=300)
 records=[json.loads(line) for line in log.read_text().splitlines() if line.startswith('{')];assert len(records)==1
 row=records[0];validate_lifecycle(row,'svm',engine,workload,'steady');row['fork']=fork;rows.append(row)
 (out/'results.partial.json').write_text(json.dumps(rows,indent=2)+'\n')
assert sha(binary)==meta['native_sha256']
(out/'results.partial.json').rename(out/'results.json')
meta.update(complete=True,load_end=os.getloadavg());save()
