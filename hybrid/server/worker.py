"""Termux/Linux profile worker. Persist before exec, bounded output, no command interpolation."""
import argparse
import asyncio
import hashlib
import json
import os
from pathlib import Path
import signal
import sqlite3
import time
import grpc
from . import control_pb2 as pb, control_pb2_grpc as rpc

async def execute(profile):
    executable=Path(profile['argv'][0]).resolve(strict=True)
    if hashlib.sha256(executable.read_bytes()).hexdigest()!=profile['sha256']:
        return 'REJECTED','Executable digest mismatch'
    proc=await asyncio.create_subprocess_exec(*profile['argv'],cwd=profile['cwd'],env={'PATH':profile['path'],'HOME':str(Path.home()),'LANG':'C.UTF-8'},stdout=asyncio.subprocess.PIPE,stderr=asyncio.subprocess.STDOUT,start_new_session=True)
    data=bytearray()
    async def drain():
        while chunk:=await proc.stdout.read(4096):
            data.extend(chunk)
            if len(data)>65536: raise ValueError('Output limit exceeded')
        await proc.wait()
    try:
        await asyncio.wait_for(drain(),min(60,int(profile.get('timeout',60))))
        return ('SUCCEEDED' if proc.returncode==0 else 'FAILED'),data.decode(errors='replace')
    except (TimeoutError,ValueError):
        try: os.killpg(proc.pid,signal.SIGKILL)
        except ProcessLookupError: pass
        await proc.wait()
        return 'UNKNOWN','Execution timed out or exceeded output limit; reconcile effects'
    except BaseException:
        try: os.killpg(proc.pid,signal.SIGKILL)
        except ProcessLookupError: pass
        await proc.wait()
        raise

async def run(config):
    db=sqlite3.connect(config['database'],isolation_level=None)
    db.execute('PRAGMA journal_mode=WAL');db.execute('PRAGMA synchronous=FULL')
    db.execute('CREATE TABLE IF NOT EXISTS jobs(id TEXT PRIMARY KEY,payload TEXT,state TEXT,output TEXT,acked INTEGER DEFAULT 0)')
    db.execute("UPDATE jobs SET state='UNKNOWN',output='Worker restarted during execution' WHERE state='RUNNING'")
    creds=grpc.ssl_channel_credentials(*(Path(config[k]).read_bytes() for k in ('ca','key','cert')))
    target=config['device'];profiles=config['profiles'];boot=str(time.time_ns());backoff=1
    async def perform(command):
        payload=command.SerializeToString().hex()
        old=db.execute('SELECT payload FROM jobs WHERE id=?',(command.job_id,)).fetchone()
        if old:
            if old[0]!=payload: raise ValueError('Conflicting command payload')
            return
        db.execute("INSERT INTO jobs(id,payload,state,output) VALUES(?,?,'RUNNING','')",(command.job_id,payload))
        if command.deadline_ms<=time.time()*1000: state,output='EXPIRED','Deadline elapsed'
        else:
            try:
                args=json.loads(command.arguments_json)
                if command.operation!='run' or set(args)!={'profile'} or args['profile'] not in profiles: state,output='REJECTED','Unknown execution profile'
                else: state,output=await execute(profiles[args['profile']])
            except asyncio.CancelledError:
                db.execute("UPDATE jobs SET state='UNKNOWN',output='Transport interrupted execution; reconcile effects' WHERE id=?",(command.job_id,))
                raise
            except Exception as e: state,output='UNKNOWN',type(e).__name__
        # Budget UTF-8 bytes after replacement decoding as well.
        output=output.encode()[:65536].decode(errors='ignore')
        db.execute('UPDATE jobs SET state=?,output=? WHERE id=?',(state,output,command.job_id))
    while True:
        try:
            async with grpc.aio.secure_channel(config['endpoint'],creds,options=[('grpc.max_receive_message_length',131072)]) as channel:
                stream=rpc.ControlStub(channel).Connect()
                async def send():
                    while True:
                        await stream.write(pb.DeviceEvent(device_id=target,boot_id=boot,readiness='READY',time_ms=int(time.time()*1000)))
                        for jid,state,output in db.execute("SELECT id,state,output FROM jobs WHERE state!='RUNNING' AND acked=0 LIMIT 16"):
                            await stream.write(pb.DeviceEvent(device_id=target,boot_id=boot,readiness='READY',result=pb.Result(job_id=jid,state=state,output=output)))
                        await asyncio.sleep(5)
                async def receive():
                    async for event in stream:
                        if event.ack_job_id: db.execute('UPDATE jobs SET acked=1 WHERE id=?',(event.ack_job_id,))
                        if event.HasField('command'): await perform(event.command)
                tasks=[asyncio.create_task(send()),asyncio.create_task(receive())]
                try:
                    done,_=await asyncio.wait(tasks,return_when=asyncio.FIRST_COMPLETED)
                    for task in done: task.result()
                finally:
                    for task in tasks: task.cancel()
                    await asyncio.gather(*tasks,return_exceptions=True)
                backoff=1
        except (grpc.RpcError,OSError,ValueError):
            await asyncio.sleep(backoff);backoff=min(60,backoff*2)

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--config',required=True);args=p.parse_args()
    asyncio.run(run(json.loads(Path(args.config).read_text())))
