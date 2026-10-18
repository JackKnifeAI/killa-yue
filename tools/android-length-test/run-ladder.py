#!/usr/bin/env python3
"""Run on ops. All generation is on the explicitly selected S20, never ops.

The separate on-phone thermal guard survives a disconnected ADB client.
This measures the patched native engine, not Android foreground-service lifetime.
"""
import array
import email.policy
from email.parser import BytesParser
import fcntl
import hashlib
import html
import json
import math
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
import urllib.request

ROOT = Path(os.environ['KILLA_TEST_ROOT']).expanduser().resolve()
NAS = Path(os.environ['KILLA_TEST_BACKUP']).expanduser().resolve()
BACKUP_MOUNT = Path(os.environ['KILLA_TEST_BACKUP_MOUNT']).expanduser().resolve()
PHONE = '/data/local/tmp/killa-s20-length-20261018'
ADB = ['adb', '-s', os.environ['KILLA_TEST_SERIAL']]
URL = 'http://127.0.0.1:31887'
HERE = Path(__file__).resolve().parent
ROOT.mkdir(parents=True, exist_ok=True)

def command(args, timeout=45, check=True):
    p = subprocess.run(args, capture_output=True, timeout=timeout)
    if check and p.returncode:
        raise RuntimeError(f'{args}: {p.stderr.decode(errors="replace")[:600]}')
    return p.stdout.decode(errors='replace')

def shell(*args, **kw):
    return command(ADB+['shell', *args], **kw)

def write_json(path, value):
    temp = path.with_suffix(path.suffix+'.tmp')
    temp.write_text(json.dumps(value, indent=2)+'\n')
    temp.replace(path)

def http(path, body=None, timeout=30):
    req = urllib.request.Request(URL+path,
        data=None if body is None else json.dumps(body).encode(),
        headers={'Content-Type': 'application/json'})
    return urllib.request.urlopen(req, timeout=timeout)

def get_json(path, body=None):
    with http(path, body) as response:
        return json.load(response)

state = {'started': time.time(), 'status': 'preparing', 'runs': [],
    'method': 'S20 native CPU engine; Q5; 2 threads; gentle pauses; 8 render steps; 30-second acoustic chunks. Original lyrics. Semantic end suppressed until requested duration for capacity testing; context limits can still shorten output. No remote generation.',
    'thermal_policy': 'Pause at Android severe, battery/skin 42 C, AP 75 C, battery <=20%, or missing sensors. Resume after three cool readings (battery/skin <=39 C, AP <=65 C, Android <=light). Stop at critical, battery/skin 45 C, AP 85 C, or battery <=15%. These are conservative test thresholds, not manufacturer limits.'}

def publish():
    state['updated'] = time.time()
    write_json(ROOT/'status.json', state)
    rows = []
    for r in state['runs']:
        slug = r['slug']
        actual = r.get('audio', {}).get('duration_seconds', '')
        link = f'<a href="{slug}/song.mp3">Listen</a>' if actual else ''
        t = r.get('thermal_latest', {})
        rows.append('<tr>'+''.join(f'<td>{v}</td>' for v in [html.escape(r['title']),
            str(r['requested_seconds'])+' s', str(actual), html.escape(r['status']),
            f"{r.get('elapsed_seconds', 0)/60:.1f} min", html.escape(str(t.get('battery_c',''))),
            html.escape(str(t.get('skin_c',''))), link])+'</tr>')
    details = ''.join('<details><summary>'+html.escape(s['title'])+'</summary><p>'+html.escape(s['request']['style'])+'</p><pre>'+html.escape(s['request']['lyrics'])+'</pre></details>' for s in setlist)
    document = '''<!doctype html><html lang="en"><meta charset="utf-8"><meta http-equiv="refresh" content="30"><title>S20 open-source sessions</title><style>body{font:17px system-ui;max-width:1150px;margin:40px auto;padding:20px;background:#101820;color:#e9f0ed}h1{color:#8be6b4}table{width:100%;border-collapse:collapse}td,th{text-align:left;padding:12px;border-bottom:1px solid #43505a}a{color:#8bd8ff}pre{white-space:pre-wrap;line-height:1.5}details{margin:20px 0}small{color:#c2cbd0}</style>'''
    document += '<h1>S20 · The open-source sessions</h1><p>'+html.escape(state['status'])+'</p><p>'+html.escape(state['method'])+'</p><table><thead><tr><th>Song</th><th>Requested</th><th>Actual seconds</th><th>Status</th><th>Elapsed</th><th>Battery °C</th><th>Skin °C</th><th>Audio</th></tr></thead><tbody>'+''.join(rows)+'</tbody></table><p><small>'+html.escape(state['thermal_policy'])+'</small></p><p>Elapsed time includes cooling. Temperatures are current readings; files contain full history. Refreshes every 30 seconds. Test status is not a sound-quality rating.</p>'+details+'</html>'
    (ROOT/'index.html').write_text(document)

def sync_nas():
    if not os.path.ismount(BACKUP_MOUNT):
        state['backup_warning'] = 'Backup mount unavailable; local evidence retained'
        return
    NAS.mkdir(parents=True, exist_ok=True)
    command(['rsync','-a','--exclude=*.tmp',str(ROOT)+'/',str(NAS)+'/'],timeout=120)
    state.pop('backup_warning', None)

def collect(r, checkpoint=False):
    directory = ROOT/r['slug']
    remote = PHONE+'/'+r['slug']
    for name in ['engine.log','thermal.csv','events.log','terminal','exit-code']:
        command(ADB+['pull',remote+'/'+name,str(directory/name)],timeout=30,check=False)
    if checkpoint:
        command(ADB+['pull',remote+'/checkpoint',str(directory)],timeout=90,check=False)
    path = directory/'thermal.csv'
    if path.exists():
        import csv
        samples=list(csv.DictReader(path.read_text().splitlines()))
        if samples:
            r['thermal_latest']=samples[-1]
            r['thermal_peak']={k:max(float(s[k]) for s in samples if s.get(k)) for k in ['battery_c','skin_c','ap_c','rss_kib','hwm_kib'] if any(s.get(k) for s in samples)}
            r['min_available_kib']=min(int(s['available_kib']) for s in samples if s.get('available_kib'))
            r['paused_samples']=sum(s['paused']=='1' for s in samples)
    log=directory/'engine.log'
    if log.exists():
        lines=log.read_text(errors='replace').splitlines()
        r['progress']=lines[-8:]

def save_result(r, jid):
    directory=ROOT/r['slug']
    with http('/job?id='+jid+'&result=1',timeout=180) as response:
        data=response.read()
        message=BytesParser(policy=email.policy.default).parsebytes(('Content-Type: '+response.headers['Content-Type']+'\r\nMIME-Version: 1.0\r\n\r\n').encode()+data)
    parts=list(message.iter_parts())
    if len(parts)!=3: raise RuntimeError('Expected metadata, MP3 and latent parts')
    meta=json.loads(parts[0].get_payload(decode=True))
    (directory/'song.mp3').write_bytes(parts[1].get_payload(decode=True))
    latent=parts[2].get_payload(decode=True)
    (directory/'latents.f32').write_bytes(latent)
    values=array.array('f'); values.frombytes(latent)
    if not values or not all(math.isfinite(v) for v in values): raise RuntimeError('Invalid latent output')
    decoded=subprocess.run(['ffmpeg','-v','error','-i',str(directory/'song.mp3'),'-f','f32le','-'],capture_output=True,check=True)
    samples=array.array('f');samples.frombytes(decoded.stdout)
    if not samples or not all(math.isfinite(v) for v in samples): raise RuntimeError('Invalid audio samples')
    probe=json.loads(command(['ffprobe','-v','error','-show_format','-show_streams','-of','json',str(directory/'song.mp3')]))
    duration=float(probe['format']['duration'])
    frames=len(meta.get('semantic_tokens','').split(','))
    r['audio']={'duration_seconds':duration,'semantic_frames':frames,'semantic_seconds':frames/25,
        'sample_rate':probe['streams'][0]['sample_rate'],'channels':probe['streams'][0]['channels'],
        'all_finite':True,'peak':max(abs(v) for v in samples),'rms':math.sqrt(sum(v*v for v in samples)/len(samples)),
        'sha256':hashlib.sha256((directory/'song.mp3').read_bytes()).hexdigest(),
        'listened':False}
    base='20261018-open-source-'+r['slug']
    meta.update(_title=r['title'],_audio=base+'.mp3',_created=int(time.time()*1000),_seconds=frames//25)
    write_json(directory/'song.json',meta)
    write_json(directory/'audio-check.json',r['audio'])
    command(ADB+['push',str(directory/'song.mp3'),'/sdcard/KillaYuE/library/'+base+'.mp3'])
    command(ADB+['push',str(directory/'song.json'),'/sdcard/KillaYuE/library/'+base+'.json'])
    r['status']='completed' if abs(frames/25-r['requested_seconds'])<0.1 else 'completed-shorter-than-requested'

setlist=json.loads((HERE/'setlist.json').read_text())
lock=(ROOT/'runner.lock').open('w')
fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
if (ROOT/'status.json').exists():
    raise SystemExit('Existing run state: inspect before restarting to avoid duplicate work')
try:
    if shell('getprop','ro.product.model').strip()!='SM-G988W': raise RuntimeError('S20 identity mismatch')
    if shell('pidof','yue-server-fixed',check=False).strip(): raise RuntimeError('Native helper already running')
    command(ADB+['forward','tcp:31887','tcp:18087'])
    shell('mkdir','-p',PHONE)
    command(ADB+['push',str(HERE/'thermal-guard.sh'),PHONE+'/thermal-guard.sh'])
    shutil.copy2(HERE/'setlist.json',ROOT/'setlist.json')
    shutil.copytree(HERE,ROOT/'harness',dirs_exist_ok=True)
    for song in setlist:
        r={k:song[k] for k in ['slug','title','context']}
        r.update(requested_seconds=song['request']['duration'],status='waiting-for-cool-start',started=time.time())
        state['runs'].append(r)
        directory=ROOT/r['slug'];directory.mkdir(exist_ok=True)
        remote=PHONE+'/'+r['slug']
        shell('mkdir','-p',remote)
        req=dict(song['request'],checkpoint=remote+'/checkpoint')
        write_json(directory/'request.json',req)
        state['status']='Running '+r['title'];publish();sync_nas()
        # All interpolated remote paths/contexts are generated constants.
        shell(f'nohup /system/bin/sh {PHONE}/thermal-guard.sh {remote} {r["context"]} > {remote}/guard.log 2>&1 < /dev/null &')
        deadline=time.time()+3600
        while True:
            try:
                if get_json('/health')['status']=='ok': break
            except Exception: pass
            collect(r)
            if (directory/'terminal').exists(): raise RuntimeError('Guard/engine ended during startup')
            r['elapsed_seconds']=time.time()-r['started'];publish()
            if time.time()>deadline: raise RuntimeError('Engine startup/cooling exceeded one hour')
            time.sleep(20)
        jid=get_json('/synth',req)['id'];r['job_id']=jid;r['status']='generating';publish()
        last_backup=0; errors=0
        while True:
            if (ROOT/'STOP').exists(): raise RuntimeError('User stop file present')
            try:
                job=get_json('/job?id='+jid);errors=0
                write_json(directory/'job.json',job)
            except Exception as e:
                errors+=1;job={'status':'unreachable'};r['connection_note']=str(e)[:300]
            collect(r,checkpoint=time.time()-last_backup>=300)
            r['elapsed_seconds']=time.time()-r['started']
            r['status']='cooling' if r.get('thermal_latest',{}).get('paused')=='1' else 'generating'
            if (directory/'terminal').exists(): raise RuntimeError((directory/'terminal').read_text())
            if job['status']=='done':
                save_result(r,jid);break
            if job['status'] in ['failed','cancelled','error']: raise RuntimeError('Job ended: '+str(job))
            # A paused server cannot answer HTTP; the on-phone guard continues independently.
            if errors>=30: raise RuntimeError('No HTTP response for approximately 30 minutes; retained checkpoints')
            publish()
            if time.time()-last_backup>=300: sync_nas();last_backup=time.time()
            time.sleep(30)
        shell('touch',remote+'/STOP')
        time.sleep(20)
        collect(r,checkpoint=True)
        r['elapsed_seconds']=time.time()-r['started']
        write_json(directory/'run-summary.json',r)
        publish();sync_nas()
    state['status']='All five duration runs finished';publish();sync_nas()
except Exception as e:
    state['status']='Stopped for investigation: '+str(e)
    if state['runs']:
        r=state['runs'][-1];r['status']='stopped';r['error']=str(e)
        try:
            shell('touch',PHONE+'/'+r['slug']+'/STOP')
            time.sleep(20);collect(r,checkpoint=True)
        except Exception as backup_error: r['collection_error']=str(backup_error)
    publish()
    try: sync_nas()
    except Exception: pass
    raise
