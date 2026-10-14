"""Exercise real TLS and real gRPC sockets; no auth or transport mocks."""
import asyncio
import datetime
import ssl
import time
from pathlib import Path
import aiohttp
from aiohttp import web
import grpc
import pytest
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID, ExtendedKeyUsageOID
from server.auth import Auth, fingerprint
from server.main import Control, app
from server.store import Store
from server import control_pb2 as pb, control_pb2_grpc as rpc

@pytest.fixture
def pki(tmp_path):
    now=datetime.datetime.now(datetime.timezone.utc)
    ca_key=rsa.generate_private_key(public_exponent=65537,key_size=2048)
    name=x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,'Test CA')])
    ca=x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(ca_key.public_key()).serial_number(x509.random_serial_number()).not_valid_before(now-datetime.timedelta(minutes=1)).not_valid_after(now+datetime.timedelta(days=1)).add_extension(x509.BasicConstraints(ca=True,path_length=0),True).sign(ca_key,hashes.SHA256())
    result={'ca':ca.public_bytes(serialization.Encoding.PEM)}
    for role in ['server','phone','client','other']:
        key=rsa.generate_private_key(public_exponent=65537,key_size=2048)
        cert=x509.CertificateBuilder().subject_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,role)])).issuer_name(name).public_key(key.public_key()).serial_number(x509.random_serial_number()).not_valid_before(now-datetime.timedelta(minutes=1)).not_valid_after(now+datetime.timedelta(days=1)).add_extension(x509.SubjectAlternativeName([x509.DNSName('localhost')]),False).add_extension(x509.ExtendedKeyUsage([ExtendedKeyUsageOID.SERVER_AUTH if role=='server' else ExtendedKeyUsageOID.CLIENT_AUTH]),False).sign(ca_key,hashes.SHA256())
        result[role]={'key':key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()),'cert':cert.public_bytes(serialization.Encoding.PEM),'fp':fingerprint(cert.public_bytes(serialization.Encoding.DER))}
        for kind in ('key','cert'):(tmp_path/f'{role}.{kind}').write_bytes(result[role][kind])
    (tmp_path/'ca.pem').write_bytes(result['ca'])
    result['path']=tmp_path
    return result

@pytest.mark.asyncio
async def test_grpc_identity_dispatch_result_ack_and_reconnect(pki,tmp_path):
    store=Store(tmp_path/'jobs')
    serv=grpc.aio.server()
    control=Control(store,{'devices':{pki['phone']['fp']:'phone'}})
    rpc.add_ControlServicer_to_server(control,serv)
    port=serv.add_secure_port('localhost:0',grpc.ssl_server_credentials([(pki['server']['key'],pki['server']['cert'])],root_certificates=pki['ca'],require_client_auth=True))
    await serv.start()
    credentials=grpc.ssl_channel_credentials(pki['ca'],pki['phone']['key'],pki['phone']['cert'])
    channel=grpc.aio.secure_channel(f'localhost:{port}',credentials)
    try:
        stream=rpc.ControlStub(channel).Connect()
        await stream.write(pb.DeviceEvent(device_id='phone',boot_id='1',readiness='READY'))
        job=store.submit('a','k','phone','status',{},time.time()+60)
        event=await asyncio.wait_for(stream.read(),3)
        assert event.command.job_id==job['id']
        await stream.write(pb.DeviceEvent(device_id='phone',boot_id='1',readiness='READY',result=pb.Result(job_id=job['id'],state='SUCCEEDED',output='SM-A146U')))
        ack=await asyncio.wait_for(stream.read(),3)
        assert ack.ack_job_id==job['id']
        assert store.get(job['id'])['output']=='SM-A146U'
        stream.cancel()
        await asyncio.sleep(.1)
        mismatch=rpc.ControlStub(channel).Connect()
        await mismatch.write(pb.DeviceEvent(device_id='impostor',readiness='READY'))
        with pytest.raises(grpc.aio.AioRpcError) as e: await mismatch.read()
        assert e.value.code()==grpc.StatusCode.PERMISSION_DENIED
    finally:
        await channel.close();await serv.stop(0)

@pytest.mark.asyncio
async def test_https_certificate_token_scope_and_target(pki,tmp_path):
    key=rsa.generate_private_key(public_exponent=65537,key_size=2048)
    registry={'clients':[{'id':'client','fingerprint':pki['client']['fp'],'scopes':['jobs:write','jobs:read'],'targets':['phone'],'operations':['status']},
                         {'id':'other','fingerprint':pki['other']['fp'],'scopes':['jobs:write','jobs:read'],'targets':[],'operations':[]}]}
    auth=Auth(registry,key,key.public_key(),'https://issuer')
    runner=web.AppRunner(app(Store(tmp_path/'https-db'),auth));await runner.setup()
    tls=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);tls.load_cert_chain(pki['path']/'server.cert',pki['path']/'server.key');tls.load_verify_locations(pki['path']/'ca.pem');tls.verify_mode=ssl.CERT_REQUIRED
    site=web.TCPSite(runner,'localhost',0,ssl_context=tls);await site.start()
    port=site._server.sockets[0].getsockname()[1]
    url=f'https://localhost:{port}'
    def client_tls(role=None):
        context=ssl.create_default_context(cafile=str(pki['path']/'ca.pem'))
        if role: context.load_cert_chain(pki['path']/f'{role}.cert',pki['path']/f'{role}.key')
        return context
    try:
        async with aiohttp.ClientSession(connector=aiohttp.TCPConnector(ssl=client_tls('client'))) as client:
            async with client.post(url+'/oauth/token',data={'grant_type':'client_credentials','client_id':'client','scope':'jobs:write jobs:read'}) as r:
                assert r.status==200;token=(await r.json())['access_token']
            headers={'Authorization':'Bearer '+token,'Idempotency-Key':'k'}
            body={'target':'phone','operation':'status','arguments':{},'deadline':time.time()+60}
            async with client.post(url+'/v1/jobs',json=body,headers=headers) as r: assert r.status==202;job=await r.json()
            async with client.post(url+'/v1/jobs',json=body,headers=headers) as r: assert (await r.json())['id']==job['id']
            body['target']='not-owned'
            async with client.post(url+'/v1/jobs',json=body,headers=headers) as r: assert r.status==401
        async with aiohttp.ClientSession(connector=aiohttp.TCPConnector(ssl=client_tls('other'))) as client:
            async with client.get(url+'/v1/jobs/'+job['id'],headers=headers) as r: assert r.status==401
        async with aiohttp.ClientSession(connector=aiohttp.TCPConnector(ssl=client_tls())) as client:
            with pytest.raises(aiohttp.ClientError): await client.get(url+'/health')
    finally: await runner.cleanup()
