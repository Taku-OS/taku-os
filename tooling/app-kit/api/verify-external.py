import json, urllib.request, urllib.error
from pathlib import Path

evidence=Path(__file__).resolve().parents[1]/'evidence/api'
records={}
def call(app,method,params,key=None,expected=200):
    body={'method':method,'params':params}
    if key:body['idempotencyKey']=key
    request=urllib.request.Request('http://127.0.0.1:8765/v1/apps/'+app+'/call',data=json.dumps(body).encode(),headers={'Content-Type':'application/json'})
    try:
        response=urllib.request.urlopen(request,timeout=10);status=response.status;result=json.load(response)
    except urllib.error.HTTPError as error:
        status=error.code;result=json.load(error)
    assert status==expected,(status,result)
    return result

records['conversion']=call('converter','length.convert',{'value':100,'from':'cm','to':'m'})
assert records['conversion']['result']['value']==1
records['invalid_type']=call('converter','length.convert',{'value':'100','from':'cm','to':'m'},expected=400)
assert records['invalid_type']['error']['code']=='INVALID_PARAMS'
records['missing_method']=call('converter','not.a.method',{},expected=404)
name='External API acceptance task'
params={'text':name,'priority':'低'}
records['add']=call('board','tasks.add',params,'external-api-add-20261009')
records['replay']=call('board','tasks.add',params,'external-api-add-20261009')
task_id=records['add']['result']['task']['id']
assert records['replay']['result']['task']['id']==task_id and records['replay']['replayed']
records['conflict']=call('board','tasks.add',{'text':name+' changed'},'external-api-add-20261009',409)
assert records['conflict']['error']['code']=='KEY_CONFLICT'
records['list']=call('board','tasks.list',{})
assert sum(t['text']==name for t in records['list']['result']['tasks'])==1
records['remove']=call('board','tasks.remove',{'taskId':task_id},'external-api-remove-20261009')
assert not any(t['id']==task_id for t in records['remove']['result']['tasks'])
evidence.mkdir(exist_ok=True)
(evidence/'external-http-verification.json').write_text(json.dumps(records,ensure_ascii=False,indent=2))
print('PASS: external HTTP conversion, argument validation, unknown method, idempotency replay/conflict, create/list/remove')
