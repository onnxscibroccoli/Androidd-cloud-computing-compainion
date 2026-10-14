"""Direct TLS termination: forwarded identity headers are never trusted."""
import argparse
import asyncio
import contextlib
import json
import logging
from pathlib import Path
import signal
import ssl
import time
import grpc
from aiohttp import web
from cryptography import x509
from cryptography.hazmat.primitives.serialization import Encoding
from . import control_pb2 as pb, control_pb2_grpc as rpc
from .auth import Auth, fingerprint
from .policy import validate
from .store import Store
from .registry import Registry

log=logging.getLogger('hybrid')
CERT_FP=web.RequestKey('certificate_fingerprint',str)

class Control(rpc.ControlServicer):
    def __init__(self, store, registry):
        self.store, self.registry, self.active = store, registry, set()

    async def Connect(self, request_iterator, context):
        certs=context.auth_context().get('x509_pem_cert',[])
        if not certs:
            await context.abort(grpc.StatusCode.UNAUTHENTICATED,'client certificate required')
        fp=fingerprint(x509.load_pem_x509_certificate(certs[0]).public_bytes(Encoding.DER))
        device=self.registry.get('devices',{}).get(fp)
        if not device or device in self.active:
            await context.abort(grpc.StatusCode.PERMISSION_DENIED,'unknown device or duplicate stream')
        self.active.add(device)
        queue=asyncio.Queue(maxsize=32)
        ready=asyncio.Event()
        seen=time.monotonic()
        sent={}
        async def receive():
            nonlocal seen
            async for event in request_iterator:
                if event.device_id != device:
                    await context.abort(grpc.StatusCode.PERMISSION_DENIED,'device mismatch')
                seen=time.monotonic()
                self.store.heartbeat(device,event.boot_id,event.readiness)
                if event.readiness=='READY': ready.set()
                else: ready.clear()
                if event.HasField('result'):
                    r=event.result
                    if not self.store.finish(device,r.job_id,r.state,r.output):
                        await context.abort(grpc.StatusCode.INVALID_ARGUMENT,'invalid or conflicting result')
                    await queue.put(pb.CloudEvent(ack_job_id=r.job_id))
            await queue.put(None)
        async def dispatch():
            while True:
                if self.registry.get('devices',{}).get(fp)!=device:
                    await context.abort(grpc.StatusCode.PERMISSION_DENIED,'device revoked')
                if time.monotonic()-seen>45:
                    await queue.put(None)
                    return
                if ready.is_set():
                    for job in self.store.pending(device):
                        if time.monotonic()-sent.get(job['id'],-100)>15:
                            self.store.dispatched(job['id'])
                            await queue.put(pb.CloudEvent(command=pb.Command(job_id=job['id'],operation=job['operation'],arguments_json=job['arguments'],deadline_ms=int(job['deadline']*1000))))
                            sent[job['id']]=time.monotonic()
                    if len(sent)>1000:
                        sent.clear()
                await asyncio.sleep(.1)
        tasks=[asyncio.create_task(receive()),asyncio.create_task(dispatch())]
        try:
            while True:
                get=asyncio.create_task(queue.get())
                done,_=await asyncio.wait([get,*tasks],return_when=asyncio.FIRST_COMPLETED)
                if get in done:
                    item=get.result()
                    if item is None: break
                    yield item
                else:
                    get.cancel()
                    with contextlib.suppress(asyncio.CancelledError): await get
                    for task in done: task.result()
                    break
        finally:
            for t in tasks: t.cancel()
            await asyncio.gather(*tasks,return_exceptions=True)
            self.active.discard(device)


def app(store, auth):
    buckets={}
    @web.middleware
    async def errors(request, handler):
        try:
            tls=request.transport.get_extra_info('ssl_object')
            der=tls.getpeercert(binary_form=True) if tls else None
            if not der: raise PermissionError('mTLS required')
            request[CERT_FP]=fingerprint(der)
            auth.client(request[CERT_FP])
            now=time.monotonic()
            start,count=buckets.get(request[CERT_FP],(now,0))
            if now-start>=60: start,count=now,0
            if count>=120: raise web.HTTPTooManyRequests()
            buckets[request[CERT_FP]]=(start,count+1)
            return await handler(request)
        except PermissionError:
            return web.json_response({'error':'unauthorized'},status=401,headers={'WWW-Authenticate':'Bearer'})
        except (ValueError,KeyError,TypeError,json.JSONDecodeError) as e:
            return web.json_response({'error':str(e)},status=400)
    application=web.Application(middlewares=[errors],client_max_size=16384)
    def authorize(request,scope):
        header=request.headers.get('Authorization','')
        if not header.startswith('Bearer '): raise PermissionError('bearer required')
        return auth.verify(header[7:],request[CERT_FP],scope)
    async def token(request):
        data=await request.post()
        if data.get('grant_type')!='client_credentials':
            return web.json_response({'error':'unsupported_grant_type'},status=400)
        access=auth.issue(request[CERT_FP],data['client_id'],data['scope'])
        return web.json_response({'access_token':access,'token_type':'Bearer','expires_in':300,'scope':data['scope']},headers={'Cache-Control':'no-store','Pragma':'no-cache'})
    async def submit(request):
        client=authorize(request,'jobs:write')
        data=await request.json()
        if set(data)!={'target','operation','arguments','deadline'}: raise ValueError('invalid job schema')
        if data['target'] not in client['targets']: raise PermissionError('target denied')
        if data['operation'] not in client['operations']: raise PermissionError('operation denied')
        validate(data['operation'],data['arguments'])
        if type(data['deadline']) not in (int,float): raise ValueError('deadline must be epoch seconds')
        job=store.submit(client['id'],request.headers.get('Idempotency-Key'),data['target'],data['operation'],data['arguments'],data['deadline'])
        log.info(json.dumps({'event':'job_submitted','id':job['id'],'owner':client['id'],'target':job['target']}))
        return web.json_response(job,status=202)
    async def get(request):
        client=authorize(request,'jobs:read')
        job=store.get(request.match_info['id'])
        if not job or job['owner']!=client['id']: raise web.HTTPNotFound()
        return web.json_response(job)
    async def health(request):
        authorize(request,'jobs:read')
        return web.json_response({'status':'ready','queue':store.db.execute("SELECT count(*) FROM jobs WHERE state='QUEUED'").fetchone()[0]})
    application.add_routes([web.post('/oauth/token',token),web.post('/v1/jobs',submit),web.get('/v1/jobs/{id}',get),web.get('/health',health)])
    return application

async def serve(config):
    store=Store(config['database'])
    registry=Registry(config['registry'])
    key=Path(config['jwt_key']).read_bytes()
    public=Path(config['jwt_public']).read_bytes()
    auth=Auth(registry,key,public,config['issuer'])
    cert,key_tls,ca=(Path(config[k]).read_bytes() for k in ('cert','key','ca'))
    server=grpc.aio.server(options=[('grpc.max_receive_message_length',131072),('grpc.max_send_message_length',131072),('grpc.max_connection_age_ms',300000)])
    rpc.add_ControlServicer_to_server(Control(store,registry),server)
    if not server.add_secure_port(config['grpc_listen'],grpc.ssl_server_credentials([(key_tls,cert)],root_certificates=ca,require_client_auth=True)):
        raise RuntimeError('gRPC bind failed')
    tls=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    tls.minimum_version=ssl.TLSVersion.TLSv1_2
    tls.load_cert_chain(config['cert'],config['key'])
    tls.load_verify_locations(config['ca'])
    tls.verify_mode=ssl.CERT_REQUIRED
    runner=web.AppRunner(app(store,auth),access_log=None)
    await runner.setup()
    site=web.TCPSite(runner,config.get('https_host','127.0.0.1'),config['https_port'],ssl_context=tls)
    await server.start()
    await site.start()
    jobs=[]
    if config.get('oci_profiles'):
        from .oci_worker import run
        jobs.append(asyncio.create_task(run(store,config['oci_profiles'])))
    stop=asyncio.Event()
    for sig in (signal.SIGTERM,signal.SIGINT): asyncio.get_running_loop().add_signal_handler(sig,stop.set)
    try: await stop.wait()
    finally:
        await server.stop(5)
        await runner.cleanup()
        for task in jobs: task.cancel()
        await asyncio.gather(*jobs,return_exceptions=True)
        store.db.close()

if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--config',required=True)
    args=parser.parse_args()
    logging.basicConfig(level=logging.INFO,format='%(message)s')
    asyncio.run(serve(json.loads(Path(args.config).read_text())))
