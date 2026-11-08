#!/usr/bin/env python3
import hashlib,json,os,platform,subprocess,time
from pathlib import Path

root=Path(__file__).resolve().parent
java=Path('/private/tmp/trufflecel-010-ingestion-profile/bazel-bin/benchmarks/representative/ingestion_profile.runfiles/rules_graalvm++graalvm+graalvm_macos_aarch64/bin/java')
lock=Path('/tmp/trufflecel-010-local-measurement.lock')
while True:
    try: lock.mkdir(); break
    except FileExistsError: time.sleep(1)
try:
    flags=['--enable-native-access=ALL-UNNAMED','-XX:-UseJVMCICompiler','-Xbatch','-Xms512m','-Xmx512m','-Dpolyglot.engine.Compilation=false']
    meta={'started_utc':time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime()),'host':platform.platform(),
          'java_version':subprocess.run([str(java),'-version'],capture_output=True,text=True,check=True).stderr,
          'hardware':subprocess.run(['sysctl','hw.model','hw.memsize','hw.physicalcpu','hw.logicalcpu'],capture_output=True,text=True,check=True).stdout,
          'load_start':os.getloadavg(), 'conditions':'Shared developer host; serialized measurement lock; no timing observations. Separate JFR processes use sampled allocation weights.',
          'commands':[], 'artifacts':{}}
    for variant in ('baseline','candidate'):
        jar=root/variant/'ingestion-profile.jar'
        meta['artifacts'][variant]={'sha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'source_revision':(root/variant/'source-revision.txt').read_text().strip()}
    for fork in range(3):
        for variant in (('baseline','candidate') if fork%2==0 else ('candidate','baseline')):
            for fixture in ('ordinary','unknown64k','messageset'):
                command=[str(java),*flags,'-jar',str(root/variant/'ingestion-profile.jar'),'allocate',fixture]
                meta['commands'].append(command)
                with (root/variant/f'allocation-{fixture}-{fork}.jsonl').open('w') as output, (root/variant/f'allocation-{fixture}-{fork}.stderr').open('w') as error:
                    subprocess.run(command,stdout=output,stderr=error,check=True)
                print(variant,fixture,fork,'done',flush=True)
    for variant in ('baseline','candidate'):
        for fixture,stage in (('ordinary','decode-cel'),('ordinary','snapshot-cel'),('unknown64k','decode-cel')):
            path=root/variant/f'{fixture}-{stage}.jfr'
            command=[str(java),*flags,'-jar',str(root/variant/'ingestion-profile.jar'),'profile',fixture,stage,str(path)]
            meta['commands'].append(command)
            with path.with_suffix('.jsonl').open('w') as output,path.with_suffix('.stderr').open('w') as error:
                subprocess.run(command,stdout=output,stderr=error,check=True)
            print(variant,fixture,stage,'JFR done',flush=True)
    meta['load_end']=os.getloadavg()
    meta['finished_utc']=time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime())
    (root/'allocation-metadata.json').write_text(json.dumps(meta,indent=2)+'\n')
finally: lock.rmdir()
