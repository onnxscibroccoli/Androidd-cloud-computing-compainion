"""RFC 8705 certificate-bound client credentials. Registry changes revoke access immediately."""
import base64
import hashlib
import hmac
import time
import uuid
import jwt

def fingerprint(der):
    return base64.urlsafe_b64encode(hashlib.sha256(der).digest()).rstrip(b'=').decode()

class Auth:
    def __init__(self, registry, signing_key, verification_key, issuer):
        self.registry, self.key, self.public, self.issuer = registry, signing_key, verification_key, issuer

    def client(self, cert_fp):
        matches = [c for c in self.registry['clients'] if hmac.compare_digest(c['fingerprint'],cert_fp) and not c.get('disabled',False)]
        if len(matches)!=1:
            raise PermissionError('unregistered certificate')
        return matches[0]

    def issue(self, cert_fp, client_id, scopes):
        client = self.client(cert_fp)
        requested = set(scopes.split())
        if client['id'] != client_id or not requested or not requested <= set(client['scopes']):
            raise PermissionError('client or scope not authorized')
        now = int(time.time())
        return jwt.encode({'iss':self.issuer,'aud':'broccoli-control','sub':client_id,
            'iat':now,'nbf':now,'exp':now+300,'jti':str(uuid.uuid4()),
            'scope':' '.join(sorted(requested)), 'cnf':{'x5t#S256':cert_fp}}, self.key, algorithm='RS256')

    def verify(self, token, cert_fp, scope):
        client = self.client(cert_fp)
        try:
            claims = jwt.decode(token, self.public, algorithms=['RS256'], audience='broccoli-control', issuer=self.issuer,
                options={'require':['exp','iat','nbf','sub','cnf','scope','jti']})
            if not hmac.compare_digest(claims['cnf']['x5t#S256'],cert_fp):
                raise PermissionError('certificate binding mismatch')
            if claims['sub'] != client['id'] or scope not in claims['scope'].split() or scope not in client['scopes']:
                raise PermissionError('scope denied')
        except (jwt.PyJWTError, KeyError, TypeError) as error:
            raise PermissionError('invalid access token') from error
        return client
