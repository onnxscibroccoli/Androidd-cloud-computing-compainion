"""Reload operator-owned registry; malformed updates fail closed. Replace files atomically."""
import json
from pathlib import Path

class Registry:
    def __init__(self,path):
        self.path=Path(path)
    def read(self):
        try:
            data=json.loads(self.path.read_text())
            if not isinstance(data.get('clients'),list) or not isinstance(data.get('devices'),dict):
                return {'clients':[],'devices':{}}
            return data
        except (OSError,ValueError,AttributeError):
            return {'clients':[],'devices':{}}
    def __getitem__(self,key): return self.read()[key]
    def get(self,key,default=None): return self.read().get(key,default)
