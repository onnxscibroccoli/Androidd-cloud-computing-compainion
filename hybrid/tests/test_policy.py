import pytest
from server.policy import validate

@pytest.mark.parametrize('op,args',[('tap',{'x':True,'y':3}),('tap',{'x':-1,'y':3}),('launch',{'component':'a/b;id'}),('shell',{}),('status',{'extra':1}),('run',{'profile':'../secret'})])
def test_rejects_invalid_input(op,args):
    with pytest.raises(ValueError):validate(op,args)

@pytest.mark.parametrize('op,args',[('tap',{'x':1,'y':3}),('status',{}),('snapshot',{}),('launch',{'component':'com.termux/.app.TermuxActivity'}),('run',{'profile':'tests'})])
def test_accepts_typed_operations(op,args):validate(op,args)
