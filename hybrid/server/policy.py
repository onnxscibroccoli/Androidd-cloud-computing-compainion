"""Shared wire validation. No free-form command or environment injection."""
import re

def validate(operation, args):
    if not isinstance(args,dict):
        raise ValueError('arguments must be an object')
    if operation in {'status','snapshot'} and not args:
        return
    if operation == 'tap' and set(args)=={'x','y'} and all(type(args[k]) is int and 0<=args[k]<=16384 for k in args):
        return
    if operation == 'key' and set(args)=={'code'} and type(args['code']) is int and args['code'] in {3,4,19,20,21,22,23,61,66,67,82,111}:
        return
    if operation == 'launch' and set(args)=={'component'} and isinstance(args['component'],str) and re.fullmatch(r'[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+',args['component']):
        return
    if operation in {'provision','run'} and set(args)=={'profile'} and isinstance(args['profile'],str) and re.fullmatch(r'[a-zA-Z0-9_-]{1,64}',args['profile']):
        return
    raise ValueError('operation or arguments not allowed')
