import json
from server.registry import Registry

def test_registry_updates_and_invalid_edits_fail_closed(tmp_path):
    path=tmp_path/'registry'
    path.write_text(json.dumps({'clients':[],'devices':{'fp':'phone'}}))
    registry=Registry(path)
    assert registry.get('devices')['fp']=='phone'
    path.write_text('{broken')
    assert registry.get('devices')=={}
    path.write_text(json.dumps({'clients':[],'devices':{}}))
    assert registry.get('devices')=={}
