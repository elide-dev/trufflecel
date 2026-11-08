#!/usr/bin/env python3
"""Balanced confirmation of the primary JVM scalar-JIT control; preserve every fork."""
import hashlib,itertools,json,math,os,pathlib,re,subprocess,sys,datetime
BASE=pathlib.Path(__file__).resolve().parent
sys.path.insert(0,str(BASE/'scripts'))
from evaluation_refresh import JVM_FLAGS
from optional_regex_evaluation import validate_policy
os.sched_setaffinity(0,set(range(2,10)))
JAVA='/home/sam/trufflecel-010-bench-20261108/toolchain/graalvm-ce-25.5.5-elide.27-linux-amd64/bin/java'
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
expected=json.loads((BASE/'staged-sha256.json').read_text())
def check():
 for n,h in expected.items():
  if sha(BASE/n)!=h:raise ValueError('Changed staged file: '+n)
check()
out=BASE/'scalar-confirmation';out.mkdir()
meta=dict(schema=1,complete=False,script_sha256=sha(pathlib.Path(__file__)),staged_sha256=expected,started_at=datetime.datetime.now(datetime.timezone.utc).isoformat(),cpus=sorted(os.sched_getaffinity(0)),load_start=os.getloadavg(),order='All six permutations, one independent fork per variant in each block',commands=[],results=[])
def save(): (out/'metadata.json').write_text(json.dumps(meta,indent=2)+'\n')
save()
for block,order in enumerate(itertools.permutations(('baseline','candidate','only'))):
 for position,r in enumerate(order):
  directory=BASE/('candidate' if r=='only' else r)
  flag='-Dtrufflecel.benchmark.regexOnly='+str(r=='only').lower()
  flags=[*JVM_FLAGS,flag];key=f'{block}-{position}-{r}';result=out/(key+'.json')
  command=[JAVA,*flags,'-jar',str(directory/'jmh_deploy.jar'),'dev.trufflecel.SteadyState.evaluate','-p','engine=truffle-jit','-p','workload=scalar-wide','-f','1','-wi','5','-i','5','-w','1s','-r','1s','-foe','true','-rf','json','-rff',str(result),'-jvmArgsAppend',' '.join(flags)]
  meta['commands'].append(dict(variant=r,block=block,position=position,argv=command,load_before=os.getloadavg()));save()
  log=out/(key+'.log')
  with log.open('w') as f: subprocess.run(command,stdout=f,stderr=subprocess.STDOUT,check=True,timeout=120)
  rows=json.loads(result.read_text())
  if len(rows)!=1:raise ValueError('Incorrect JMH inventory')
  row=rows[0]
  if (row['benchmark'],row['params'],row['forks'],row['primaryMetric']['scoreUnit'])!=('dev.trufflecel.SteadyState.evaluate',dict(engine='truffle-jit',workload='scalar-wide'),1,'ns/op'):raise ValueError('Mislabeled fork')
  values=row['primaryMetric']['rawData']
  if len(values)!=1 or len(values[0])!=5 or any(not math.isfinite(x) or x<=0 for x in values[0]):raise ValueError('Incorrect samples')
  markers=re.findall(r'REGEX_PATH (\S+) (\d+) (\d+) (\d+) (\S+) (\S+) (\d+)',log.read_text())
  if len(markers)!=1:raise ValueError('Missing actual policy proof')
  b,m,a,n,p,e,c=markers[0];proof=dict(backend=b,match_nodes=int(m),accelerated_nodes=int(a),ast_nodes=int(n),regex_policy=p,regex_execution=e,scoped_regexes=int(c))
  validate_policy(proof,'truffle-only' if r=='only' else 'compatibility','scalar-wide')
  meta['results'].append(dict(variant=r,block=block,position=position,file=result.name,log=log.name,regex_path=proof,mean_ns=sum(values[0])/5));save();print('qualified scalar confirmation',key,flush=True)
check()
if len(meta['results'])!=18:raise ValueError('Incomplete confirmation')
meta.update(complete=True,completed_at=datetime.datetime.now(datetime.timezone.utc).isoformat(),load_end=os.getloadavg());save()
