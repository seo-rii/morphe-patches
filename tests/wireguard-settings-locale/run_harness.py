#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
# See the included NOTICE file for additional GPLv3 Section 7 requirements.
"""Run the standalone locale APKs on an explicitly selected disposable emulator.

The caller owns emulator creation/cleanup. Only the two test packages are installed,
stopped and instrumented. No target-app APK, profile or server is involved.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--adb', type=Path, required=True)
    p.add_argument('--serial', required=True)
    p.add_argument('--builds', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--boot-timeout', type=int, default=480)
    p.add_argument('--case-timeout', type=int, default=120)
    p.add_argument('--emulator-exit-file', type=Path, help='Optional caller-owned launcher status; abort early if it exits')
    p.add_argument('--owned-vm-dir', type=Path, help='Optional disposable VM directory for a physical allocation bound')
    p.add_argument('--max-allocated-bytes', type=int, default=1024**3)
    p.add_argument('--max-ram-backing-bytes', type=int, default=2*1024**3)
    a = p.parse_args()
    assert a.serial.startswith('emulator-'), 'Use a disposable Android emulator'
    assert not a.output.exists(), 'Choose fresh output directory'
    a.output.mkdir(parents=True, mode=0o700)
    report = {'status':'PENDING','scope':'Standalone Android UI regression; target-app/device/network acceptance NOT RUN','serial':a.serial,'cases':[]}
    def save():
        (a.output / 'result.json').write_text(json.dumps(report, indent=2, ensure_ascii=False)+'\n')
    def adb(*args, timeout=30):
        return subprocess.run([str(a.adb), '-s', a.serial, *args], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=timeout)
    def health():
        if a.emulator_exit_file and a.emulator_exit_file.exists():
            raise RuntimeError('Disposable emulator exited before test completion: '+a.emulator_exit_file.read_text().strip())
        if a.owned_vm_dir:
            files=[f for f in a.owned_vm_dir.rglob('*') if not f.is_symlink() and f.is_file()]
            # The emulator memory-maps guest RAM here even when snapshot saving is off.
            ram=[f for f in files if f.name == 'ram.img' and f.parent.parent.name == 'snapshots']
            backing=sum(f.stat().st_blocks*512 for f in ram)
            allocated=sum(f.stat().st_blocks*512 for f in files if f not in ram)
            report['vm_disk_allocated_bytes']=allocated
            report['vm_ram_backing_allocated_bytes']=backing
            assert allocated <= a.max_allocated_bytes, 'Disposable VM exceeded disk allocation bound'
            assert backing <= a.max_ram_backing_bytes, 'Disposable VM exceeded guest RAM backing bound'
    try:
        start = time.monotonic()
        while True:
            health()
            try:
                ready = adb('shell', 'getprop', 'sys.boot_completed', timeout=10)
                report['last_boot_readiness']={'boot_completed':ready.stdout.strip(),'boot_query_exit':ready.returncode}
                if ready.returncode == 0 and ready.stdout.strip() == '1':
                    package=adb('shell','pm','path','android',timeout=10)
                    report['last_boot_readiness'].update(framework_package_exit=package.returncode,framework_package_output=package.stdout.strip()[-1000:])
                    if package.returncode == 0 and re.search(r'^package:/\S+\.apk\s*$',package.stdout,re.M): break
            except subprocess.TimeoutExpired:
                report['last_boot_readiness']={'error':'Timed out checking boot or PackageManager readiness'}
            assert time.monotonic()-start < a.boot_timeout, 'Disposable emulator boot timeout'
            time.sleep(8)
        report['boot_wait_seconds'] = round(time.monotonic()-start, 1)
        report['android_api'] = adb('shell','getprop','ro.build.version.sdk').stdout.strip()
        report['android_abi'] = adb('shell','getprop','ro.product.cpu.abi').stdout.strip()
        try:
            unlock=adb('shell','wm','dismiss-keyguard',timeout=5)
            report['keyguard_setup']={'exit_code':unlock.returncode,'output':unlock.stdout.strip()[-500:]}
        except subprocess.TimeoutExpired:
            report['keyguard_setup']={'status':'TIMEOUT (nonfatal; test windows also show when locked)'}
        for variant in ('baseline','fixed'):
            directory = a.builds / variant
            build = json.loads((directory/'build-result.json').read_text())
            assert build['status']=='PASS' and build['variant']==variant
            package = build['package']; assert package=='app.morphe.wireguard.locale.'+variant
            # These dedicated disposable test IDs never contain target-app data.
            adb('uninstall',package)
            installed=adb('install','--no-incremental','-r',str(directory/'harness.apk'),timeout=90)
            assert installed.returncode == 0 and 'Success' in installed.stdout, installed.stdout
            keys = sorted(e.get('name') for e in ET.parse(directory/'res/values/strings.xml').getroot())
            for scenario in ('activity-ko-cached-en','activity-ko-unprimed','morphe-ko-cached-en','english-fallback'):
                health()
                adb('shell','am','force-stop',package)
                command = ('shell','am','instrument','-w','-r','-e','scenario',scenario,'-e','keys',','.join(keys),package+'/app.morphe.wireguard.locale.RegressionInstrumentation')
                result = adb(*command,timeout=a.case_timeout)
                log=a.output/(variant+'-'+scenario+'.txt');log.write_text(result.stdout);log.chmod(0o600)
                values = dict(re.findall(r'^INSTRUMENTATION_RESULT: (\w+)=(.*)$',result.stdout,re.M))
                status=values.get('status','MISSING')
                entry={'variant':variant,'scenario':scenario,'exit_code':result.returncode,'status':status,'checks':int(values.get('checks','0')),'log':str(log),'expected':'FAIL' if variant=='baseline' else 'PASS'}
                if 'error' in values:entry['error']=values['error']
                report['cases'].append(entry);save()
                assert result.returncode==0 and status==entry['expected'], result.stdout[-4000:]
                assert entry['checks'] >= 30, 'Too few UI checks executed'
                print(variant,scenario,status,entry['checks'],'checks',flush=True)
        report['status']='PASS';save();print('PASS expected baseline failures and fixed Android UI cases',flush=True)
    except BaseException as error:
        report['status']='FAIL';report['error']=str(error)[-5000:];save();raise


if __name__ == '__main__': main()
