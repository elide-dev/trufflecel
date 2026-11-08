#!/usr/bin/env python3
import hashlib,json,os,platform,subprocess,time
from pathlib import Path

root=Path(__file__).resolve().parent
java=Path('/home/sam/trufflecel-010-bench-20261108/toolchain/graalvm-ce-25.5.5-elide.27-linux-amd64/bin/java')
lock=Path('/tmp/trufflecel-010-remote-measurement.lock')
while True:
    try:
        lock.mkdir()
        break
    except FileExistsError:
        time.sleep(1)
try:
    meta={'started_utc':time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime()),'host':platform.platform(),
          'java_version':subprocess.run([str(java),'-version'],capture_output=True,text=True,check=True).stderr,
          'affinity':sorted(os.sched_getaffinity(0)), 'load_start':os.getloadavg(),
          'cpuinfo':Path('/proc/cpuinfo').read_text(),
          'meminfo':Path('/proc/meminfo').read_text(),
          'conditions':'Dedicated benchmark host, no concurrent agent measurements; affinity2-9; no host-global settings changed. Timing has no allocation profiler.',
          'commands':[], 'artifacts':{}}
    for variant in ('baseline','candidate'):
        jar=root/variant/'jmh.jar'
        meta['artifacts'][variant]={'sha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'source_revision':(root/variant/'source-revision.txt').read_text().strip()}
    for fork in range(3):
        for variant in (('baseline','candidate') if fork%2==0 else ('candidate','baseline')):
            output=root/variant/f'timing-{fork}.json'
            command=['taskset','-c','2-9',str(java),'--enable-native-access=ALL-UNNAMED','-XX:-UseJVMCICompiler','-Xms512m','-Xmx512m','-jar',str(root/variant/'jmh.jar'),
                     'IngestionStages.(decodeCel|decodeNative|decodeStock|snapshotCel|snapshotBound)',
                     '-f','1','-wi','3','-i','3','-w','500ms','-r','500ms','-rf','json','-rff',str(output),
                     '-jvmArgsAppend','-XX:-UseJVMCICompiler -Xms512m -Xmx512m']
            meta['commands'].append(command)
            (root/'timing-metadata.json').write_text(json.dumps(meta,indent=2)+'\n')
            with (root/variant/f'timing-{fork}.log').open('w') as log:
                subprocess.run(command,stdout=log,stderr=subprocess.STDOUT,check=True)
            data=json.loads(output.read_text())
            if len(data)!=5: raise AssertionError('Missing benchmark result')
            print(variant,fork,{x['benchmark'].rsplit('.',1)[-1]:round(x['primaryMetric']['score'],2) for x in data},flush=True)
    meta['load_end']=os.getloadavg()
    meta['finished_utc']=time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime())
    (root/'timing-metadata.json').write_text(json.dumps(meta,indent=2)+'\n')
finally:
    lock.rmdir()
