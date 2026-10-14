"""Transactional queue. Redelivery is permitted; side effects are never presumed repeatable."""
import hashlib
import json
import sqlite3
import time
import uuid

TERMINAL = frozenset({'SUCCEEDED', 'FAILED', 'EXPIRED', 'UNKNOWN', 'REJECTED'})

class Store:
    def __init__(self, path):
        self.db = sqlite3.connect(path, isolation_level=None)
        self.db.row_factory = sqlite3.Row
        self.db.executescript('''PRAGMA journal_mode=WAL; PRAGMA synchronous=FULL;
        PRAGMA busy_timeout=5000;
        CREATE TABLE IF NOT EXISTS jobs (
          id TEXT PRIMARY KEY, owner TEXT NOT NULL, request_key TEXT NOT NULL,
          digest TEXT NOT NULL, target TEXT NOT NULL, operation TEXT NOT NULL,
          arguments TEXT NOT NULL, deadline REAL NOT NULL, state TEXT NOT NULL,
          output TEXT NOT NULL DEFAULT '', created REAL NOT NULL,
          UNIQUE(owner, request_key));
        CREATE INDEX IF NOT EXISTS pending ON jobs(target,state,created);
        CREATE TABLE IF NOT EXISTS devices(id TEXT PRIMARY KEY, boot TEXT, readiness TEXT, seen REAL);
        ''')

    def submit(self, owner, key, target, operation, arguments, deadline):
        now = time.time()
        if not key or len(key)>128 or not now < deadline <= now+86400:
            raise ValueError('invalid idempotency key or deadline')
        body = json.dumps([target, operation, arguments, deadline], sort_keys=True, separators=(',', ':'))
        digest = hashlib.sha256(body.encode()).hexdigest()
        self.db.execute('BEGIN IMMEDIATE')
        try:
            old = self.db.execute('SELECT * FROM jobs WHERE owner=? AND request_key=?', (owner,key)).fetchone()
            if old:
                if old['digest'] != digest:
                    raise ValueError('idempotency key reused with different request')
                self.db.execute('COMMIT')
                return dict(old)
            self.db.execute("UPDATE jobs SET state='EXPIRED' WHERE state='QUEUED' AND deadline<=?",(now,))
            count = self.db.execute("SELECT count(*) FROM jobs WHERE state IN ('QUEUED','RUNNING','DISPATCHED')").fetchone()[0]
            if count >= 10000:
                raise ValueError('queue capacity exceeded')
            jid = str(uuid.uuid4())
            self.db.execute('INSERT INTO jobs VALUES(?,?,?,?,?,?,?,?,?,?,?)',
                (jid,owner,key,digest,target,operation,json.dumps(arguments,sort_keys=True),deadline,'QUEUED','',now))
            self.db.execute('COMMIT')
            return self.get(jid)
        except BaseException:
            if self.db.in_transaction:
                self.db.execute('ROLLBACK')
            raise

    def get(self, jid):
        row = self.db.execute('SELECT * FROM jobs WHERE id=?',(jid,)).fetchone()
        return dict(row) if row else None

    def pending(self, target):
        self.db.execute("UPDATE jobs SET state='EXPIRED' WHERE state='QUEUED' AND deadline<=?",(time.time(),))
        return [dict(r) for r in self.db.execute("SELECT * FROM jobs WHERE target=? AND state IN ('QUEUED','DISPATCHED') ORDER BY created LIMIT 16",(target,))]

    def dispatched(self, jid):
        return self.db.execute("UPDATE jobs SET state='DISPATCHED' WHERE id=? AND state='QUEUED'",(jid,)).rowcount == 1

    def claim(self, jid):
        return self.db.execute("UPDATE jobs SET state='RUNNING' WHERE id=? AND state='QUEUED' AND deadline>?",(jid,time.time())).rowcount == 1

    def recover(self, target):
        self.db.execute("UPDATE jobs SET state='UNKNOWN',output='Executor restarted during execution; reconcile before a new request' WHERE target=? AND state='RUNNING'",(target,))

    def finish(self, target, jid, state, output):
        if state not in TERMINAL or len(output.encode())>65536:
            return False
        row = self.get(jid)
        if not row or row['target'] != target:
            return False
        if row['state'] in TERMINAL:
            return row['state'] == state and row['output'] == output
        self.db.execute('UPDATE jobs SET state=?,output=? WHERE id=?',(state,output,jid))
        return True

    def heartbeat(self, target, boot, readiness):
        self.db.execute('INSERT OR REPLACE INTO devices VALUES(?,?,?,?)',(target,boot[:128],readiness[:128],time.time()))
