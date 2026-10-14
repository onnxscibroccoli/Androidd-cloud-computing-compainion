import asyncio
import hashlib
import sys
from pathlib import Path
import pytest
from server.worker import execute

def profile(code):
    executable=Path(sys.executable).resolve()
    return {'argv':[str(executable),'-c',code],'sha256':hashlib.sha256(executable.read_bytes()).hexdigest(),'cwd':'/tmp','path':'/usr/bin:/bin','timeout':1}

@pytest.mark.asyncio
async def test_real_profile_execution():
    assert await execute(profile('print("working")'))==('SUCCEEDED','working\n')
    assert (await execute(profile('raise SystemExit(3)')))[0]=='FAILED'

@pytest.mark.asyncio
async def test_digest_gate_and_timeout():
    p=profile('print("not-run")');p['sha256']='0'*64
    assert (await execute(p))[0]=='REJECTED'
    assert (await execute(profile('import time;time.sleep(20)')))[0]=='UNKNOWN'

@pytest.mark.asyncio
async def test_output_limit():
    assert (await execute(profile('print("x"*100000)')))[0]=='UNKNOWN'
