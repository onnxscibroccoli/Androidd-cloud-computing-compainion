#!/usr/bin/env python3
"""Create a private deployment PKI and enrollment, refusing to overwrite existing identities."""
import argparse
import base64
import datetime
import json
import os
from pathlib import Path
import re
import secrets
import sys
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives.serialization import pkcs12
from cryptography.x509.oid import NameOID, ExtendedKeyUsageOID
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from server.auth import fingerprint

p=argparse.ArgumentParser();p.add_argument('--out',required=True);p.add_argument('--host',required=True);p.add_argument('--device',default='phone');p.add_argument('--client',default='model-runner');p.add_argument('--https-port',type=int,default=8443);p.add_argument('--grpc-port',type=int,default=8444)
a=p.parse_args()
if not re.fullmatch(r'[a-zA-Z0-9.-]{1,253}',a.host): p.error('host must be a DNS name')
if not all(re.fullmatch(r'[a-zA-Z0-9_-]{1,64}',v) for v in (a.device,a.client)): p.error('invalid identity')
if not all(1<=v<=65535 for v in (a.https_port,a.grpc_port)): p.error('invalid port')
os.umask(0o077);root=Path(a.out).resolve();root.mkdir(parents=True,exist_ok=False)
now=datetime.datetime.now(datetime.timezone.utc)
ca_key=rsa.generate_private_key(public_exponent=65537,key_size=3072)
name=x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,'Broccoli Deployment CA')])
ca=x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(ca_key.public_key()).serial_number(x509.random_serial_number()).not_valid_before(now-datetime.timedelta(minutes=5)).not_valid_after(now+datetime.timedelta(days=3650)).add_extension(x509.BasicConstraints(ca=True,path_length=0),True).add_extension(x509.KeyUsage(False,False,False,False,False,True,True,False,False),True).sign(ca_key,hashes.SHA256())
def save_key(name,key):
    (root/name).write_bytes(key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()))
save_key('ca.key',ca_key);(root/'ca.pem').write_bytes(ca.public_bytes(serialization.Encoding.PEM))
identities={}
for role,cn in [('server',a.host),('device',a.device),('client',a.client)]:
    key=rsa.generate_private_key(public_exponent=65537,key_size=3072)
    builder=x509.CertificateBuilder().subject_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,cn)])).issuer_name(name).public_key(key.public_key()).serial_number(x509.random_serial_number()).not_valid_before(now-datetime.timedelta(minutes=5)).not_valid_after(now+datetime.timedelta(days=90)).add_extension(x509.BasicConstraints(ca=False,path_length=None),True).add_extension(x509.ExtendedKeyUsage([ExtendedKeyUsageOID.SERVER_AUTH if role=='server' else ExtendedKeyUsageOID.CLIENT_AUTH]),False)
    if role=='server': builder=builder.add_extension(x509.SubjectAlternativeName([x509.DNSName(a.host)]),False)
    cert=builder.sign(ca_key,hashes.SHA256());save_key(role+'.key',key);(root/(role+'.pem')).write_bytes(cert.public_bytes(serialization.Encoding.PEM));identities[role]=(key,cert)
jwt_key=rsa.generate_private_key(public_exponent=65537,key_size=3072);save_key('jwt.key',jwt_key);(root/'jwt.pub').write_bytes(jwt_key.public_key().public_bytes(serialization.Encoding.PEM,serialization.PublicFormat.SubjectPublicKeyInfo))
password=secrets.token_urlsafe(32)
key,cert=identities['device']
bundle=pkcs12.serialize_key_and_certificates(b'client',key,cert,[ca],serialization.BestAvailableEncryption(password.encode()))
def write(name,value): (root/name).write_text(json.dumps(value,indent=2)+'\n')
write('android-enrollment.json',{'host':a.host,'port':a.grpc_port,'device':a.device,'password':password,'pkcs12':base64.b64encode(bundle).decode(),'ca_pem':ca.public_bytes(serialization.Encoding.PEM).decode()})
write('registry.json',{'devices':{fingerprint(cert.public_bytes(serialization.Encoding.DER)):a.device},'clients':[{'id':a.client,'fingerprint':fingerprint(identities['client'][1].public_bytes(serialization.Encoding.DER)),'scopes':['jobs:read','jobs:write'],'targets':[a.device],'operations':['status']}]})
write('server.json',{'database':str(root/'jobs.db'),'registry':str(root/'registry.json'),'jwt_key':str(root/'jwt.key'),'jwt_public':str(root/'jwt.pub'),'issuer':f'https://{a.host}:{a.https_port}','cert':str(root/'server.pem'),'key':str(root/'server.key'),'ca':str(root/'ca.pem'),'https_host':'127.0.0.1','https_port':a.https_port,'grpc_listen':f'127.0.0.1:{a.grpc_port}'})
write('client.json',{'url':f'https://{a.host}:{a.https_port}','client_id':a.client,'cert':str(root/'client.pem'),'key':str(root/'client.key'),'ca':str(root/'ca.pem')})
print('Created private deployment material at '+str(root))
print('Default policy permits status only. Keep ca.key offline; enroll each additional client/device explicitly. Leaf certificates expire in 90 days.')
