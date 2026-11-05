import datetime,json,os,subprocess,time
from pathlib import Path
from representative import FLAGS,sha
root=Path('/home/sam/trufflecel-feedback-20261105')
os.chdir(root);os.sched_setaffinity(0,set(range(2,10)))
while not json.loads((root/'borrowed-native-v1/metadata.json').read_text())['complete']:time.sleep(15)
out=root/'borrowed-snapshot-followup-v1';out.mkdir(exist_ok=False)
java=root/'graalvm-ce-25.5.5-elide.24-linux-amd64/bin/java';jar=root/'trufflecel-borrowed-v1.jar'
base=json.loads((root/'borrowed-jvm-v1/metadata.json').read_text());assert base['complete'] and sha(jar)==base['jar_sha256']
command=[str(java),'-jar',str(jar),'dev.trufflecel.ActivationEvaluation.evaluateSnapshot','-p','engine=truffle-interpreter','-p','workload=optimizer-policy','-f','3','-wi','15','-i','5','-w','1s','-r','1s','-foe','true','-prof','gc','-rf','json','-rff',str(out/'jmh.json'),'-jvmArgsAppend',' '.join(FLAGS)]
meta=dict(base,kind='snapshot-drift-followup',captured_at=datetime.datetime.now(datetime.timezone.utc).isoformat(),commands=[command],driver_sha256=sha(Path(__file__)),load_start=os.getloadavg(),complete=False,reason='Original interpreter/optimizer-policy snapshot fork 2 had +14.36% first-two/last-two measurement drift; retain original and rerun with 15 warmups.')
meta.pop('load_end',None)
def save():(out/'metadata.json').write_text(json.dumps(meta,indent=2)+'\n')
save()
with (out/'jmh.log').open('w') as log:subprocess.run(command,stdout=log,stderr=subprocess.STDOUT,check=True,timeout=600)
assert sha(jar)==meta['jar_sha256']
meta.update(complete=True,load_end=os.getloadavg());save()
