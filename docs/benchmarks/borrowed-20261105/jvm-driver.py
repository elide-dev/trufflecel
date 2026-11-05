import datetime,json,math,os,platform,subprocess
from pathlib import Path
from representative import FLAGS,WORKLOADS,sha
root=Path('/home/sam/trufflecel-feedback-20261105')
os.chdir(root);os.sched_setaffinity(0,set(range(2,10)))
out=root/'borrowed-jvm-v1';out.mkdir(exist_ok=False)
java=root/'graalvm-ce-25.5.5-elide.24-linux-amd64/bin/java'
jar=root/'trufflecel-borrowed-v1.jar'
meta=dict(captured_at=datetime.datetime.now(datetime.timezone.utc).isoformat(),host=platform.node(),kernel=platform.release(),cpus=sorted(os.sched_getaffinity(0)),lscpu=subprocess.check_output(['lscpu'],text=True),java_version=subprocess.check_output([str(java),'-version'],text=True,stderr=subprocess.STDOUT),java_sha256=sha(java),jar_sha256=sha(jar),driver_sha256=sha(Path(__file__)),source_manifest=json.loads((root/'trufflecel-borrowed-source.json').read_text()),commands=[],load_start=os.getloadavg(),complete=False)
def save():(out/'metadata.json').write_text(json.dumps(meta,indent=2)+'\n')
save()
for name,benchmark,engines in [('comparison','SteadyState.evaluate',('truffle-interpreter','truffle-jit','cel-java-optimized')),('snapshots','ActivationEvaluation.evaluateSnapshot',('truffle-interpreter','truffle-jit'))]:
 command=[str(java),'-jar',str(jar),'dev.trufflecel.'+benchmark,'-p','engine='+','.join(engines),'-p','workload='+','.join(WORKLOADS),'-f','3','-wi','5','-i','5','-w','1s','-r','1s','-foe','true','-prof','gc','-rf','json','-rff',str(out/(name+'.json')),'-jvmArgsAppend',' '.join(FLAGS)]
 meta['commands'].append(command);save();print(name,flush=True)
 with (out/(name+'.log')).open('w') as log:subprocess.run(command,stdout=log,stderr=subprocess.STDOUT,check=True,timeout=7200)
 rows=json.loads((out/(name+'.json')).read_text());seen=set()
 for row in rows:
  key=row['params']['engine'],row['params']['workload']
  assert key not in seen and key[0] in engines and key[1] in WORKLOADS
  seen.add(key)
  assert row['benchmark']=='dev.trufflecel.'+benchmark and row['primaryMetric']['scoreUnit']=='ns/op'
  for metric in [row['primaryMetric'],row['secondaryMetrics']['gc.alloc.rate.norm']]:
   data=metric['rawData'];assert len(data)==3 and all(len(f)==5 for f in data)
   assert all(math.isfinite(x) and x>=0 for f in data for x in f)
 assert len(seen)==len(engines)*len(WORKLOADS)
assert sha(jar)==meta['jar_sha256']
meta.update(complete=True,load_end=os.getloadavg());save()
