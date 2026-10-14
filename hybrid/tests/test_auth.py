import pytest
from cryptography.hazmat.primitives.asymmetric import rsa
from server.auth import Auth

@pytest.fixture
def auth():
    key=rsa.generate_private_key(public_exponent=65537,key_size=2048)
    return Auth({'clients':[{'id':'a','fingerprint':'fp-a','scopes':['jobs:write'],'targets':['phone']},
                            {'id':'b','fingerprint':'fp-b','scopes':['jobs:write'],'targets':[]}]},key,key.public_key(),'https://issuer')

def test_certificate_bound_token(auth):
    t=auth.issue('fp-a','a','jobs:write')
    assert auth.verify(t,'fp-a','jobs:write')['id']=='a'
    with pytest.raises(PermissionError): auth.verify(t,'fp-b','jobs:write')

def test_scope_and_revocation(auth):
    with pytest.raises(PermissionError): auth.issue('fp-a','a','admin')
    t=auth.issue('fp-a','a','jobs:write')
    with pytest.raises(PermissionError): auth.verify(t,'fp-a','jobs:read')
    auth.registry['clients'][0]['disabled']=True
    with pytest.raises(PermissionError): auth.verify(t,'fp-a','jobs:write')
