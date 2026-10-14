"""Provider-neutral OAuth/mTLS client used by owned model tool runners."""
import argparse
import asyncio
import json
from pathlib import Path
import ssl
import time
import uuid
import aiohttp

class Client:
    def __init__(self, config):
        if not config['url'].startswith('https://'): raise ValueError('HTTPS required')
        self.config=config;self.token=None;self.expires=0
        tls=ssl.create_default_context(cafile=config['ca'])
        tls.load_cert_chain(config['cert'],config['key'])
        self.session=aiohttp.ClientSession(connector=aiohttp.TCPConnector(ssl=tls),timeout=aiohttp.ClientTimeout(total=30))

    async def close(self): await self.session.close()
    async def headers(self):
        if time.monotonic()>=self.expires:
            async with self.session.post(self.config['url']+'/oauth/token',data={'grant_type':'client_credentials','client_id':self.config['client_id'],'scope':'jobs:write jobs:read'},allow_redirects=False) as r:
                r.raise_for_status();body=await r.json()
                self.token=body['access_token'];self.expires=time.monotonic()+body['expires_in']-30
        return {'Authorization':'Bearer '+self.token}

    async def submit(self,target,operation,arguments,request_key,deadline):
        headers=await self.headers();headers['Idempotency-Key']=request_key
        async with self.session.post(self.config['url']+'/v1/jobs',json={'target':target,'operation':operation,'arguments':arguments,'deadline':deadline},headers=headers,allow_redirects=False) as r:
            r.raise_for_status();return await r.json()

    async def get(self,jid):
        uuid.UUID(jid)
        async with self.session.get(self.config['url']+'/v1/jobs/'+jid,headers=await self.headers(),allow_redirects=False) as r:
            r.raise_for_status();return await r.json()

async def main(args):
    client=Client(json.loads(Path(args.config).read_text()))
    try:
        if args.job: result=await client.get(args.job)
        else: result=await client.submit(args.target,args.operation,json.loads(args.arguments),args.idempotency_key or str(uuid.uuid4()),args.deadline)
        print(json.dumps(result,indent=2))
    finally: await client.close()

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--config',required=True);p.add_argument('--job');p.add_argument('--target',default='phone');p.add_argument('--operation',default='status');p.add_argument('--arguments',default='{}');p.add_argument('--idempotency-key');p.add_argument('--deadline',type=float,default=time.time()+60)
    asyncio.run(main(p.parse_args()))
