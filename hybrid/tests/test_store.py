import time
import pytest
from server.store import Store


def test_idempotency_is_body_bound(tmp_path):
    s = Store(tmp_path / 'db')
    j = s.submit('alice', 'k', 'phone', 'status', {}, time.time()+60)
    assert s.submit('alice', 'k', 'phone', 'status', {}, j['deadline'])['id'] == j['id']
    with pytest.raises(ValueError):
        s.submit('alice', 'k', 'phone', 'tap', {'x':1,'y':2}, j['deadline'])


def test_result_is_target_bound_and_immutable(tmp_path):
    s = Store(tmp_path / 'db')
    j = s.submit('alice', 'key', 'phone', 'status', {}, time.time()+60)
    assert s.pending('phone')[0]['id'] == j['id']
    assert not s.finish('other', j['id'], 'SUCCEEDED', 'x')
    assert s.finish('phone', j['id'], 'SUCCEEDED', 'ok')
    assert not s.finish('phone', j['id'], 'FAILED', 'changed')
    assert s.get(j['id'])['output'] == 'ok'
    assert s.pending('phone') == []


def test_deadline_expiry(tmp_path):
    s = Store(tmp_path / 'db')
    with pytest.raises(ValueError):
        s.submit('alice', 'key', 'phone', 'status', {}, time.time()-1)


def test_crash_does_not_requeue_cloud_side_effect(tmp_path):
    s = Store(tmp_path / 'db')
    j = s.submit('alice', 'key', 'oci', 'provision', {'profile':'test'}, time.time()+60)
    assert s.claim(j['id'])
    assert not s.claim(j['id'])
    s.recover('oci')
    assert s.get(j['id'])['state'] == 'UNKNOWN'
    assert s.pending('oci') == []


def test_dispatched_side_effect_can_report_after_deadline(tmp_path):
    s=Store(tmp_path/'db')
    j=s.submit('a','k','phone','status',{},time.time()+1)
    assert s.dispatched(j['id'])
    s.db.execute('UPDATE jobs SET deadline=? WHERE id=?',(time.time()-1,j['id']))
    assert s.pending('phone')[0]['state']=='DISPATCHED'
    assert s.finish('phone',j['id'],'SUCCEEDED','completed before disconnect')
