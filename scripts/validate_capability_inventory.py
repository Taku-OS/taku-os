#!/usr/bin/env python3
"""Evidence gates, source date audit, generated docs and JS syntax. No device mutation."""
import argparse, collections, datetime, hashlib, json, pathlib, re, subprocess, tempfile, urllib.parse
ROOT = pathlib.Path(__file__).resolve().parents[1]
REQUIRED = 'id title purpose accessTier androidSupport permissions entrypoints examples limitations status evidence sources validation verificationScope romRequirement'.split()
def schema_errors(value, schema, path='$'):
    """Validate the subset actually used by capability.schema.json, without dependencies."""
    out=[]
    if 'oneOf' in schema:
        matches=[s for s in schema['oneOf'] if not schema_errors(value,s,path)]
        return [] if len(matches)==1 else [f'{path}: oneOf must match exactly once']
    types={'object':dict,'array':list,'string':str,'integer':int,'null':type(None),'boolean':bool}
    expected=schema.get('type')
    if expected and type(value) is not types[expected]:return [f'{path}: expected {expected}']
    if 'enum' in schema and value not in schema['enum']:out.append(f'{path}: enum mismatch')
    if 'minimum' in schema and value<schema['minimum']:out.append(f'{path}: below minimum')
    if 'minLength' in schema and len(value)<schema['minLength']:out.append(f'{path}: below minLength')
    if isinstance(value,dict):
        for k in schema.get('required',[]):
            if k not in value:out.append(f'{path}: missing {k}')
        for k,s in schema.get('properties',{}).items():
            if k in value:out.extend(schema_errors(value[k],s,path+'.'+k))
    return out
def validate(data):
    errors=[]; ids=set(); counts=collections.Counter()
    def fail(id, text): errors.append(f'{id}: {text}')
    for c in data.get('capabilities',[]):
        id=c.get('id','<missing>')
        if id in ids: fail(id,'duplicate id')
        ids.add(id)
        for key in REQUIRED:
            if key not in c: fail(id,f'missing {key}')
        if any(key not in c for key in REQUIRED): continue
        if c['status'] not in ('research','emulator_verified','device_verified'):fail(id,'bad status')
        if c['accessTier'] not in ('ordinary','adb_shizuku','root_system','rom_framework'):fail(id,'bad accessTier')
        counts[c['status']]+=1
        for key in ('permissions','entrypoints','examples','limitations','sources'):
            if not isinstance(c[key],list) or not c[key]:fail(id,f'empty {key}')
        support=c['androidSupport']
        if support.get('apiMin') is not None and (type(support['apiMin']) is not int or support['apiMin']<1):fail(id,'bad API minimum')
        if not support.get('minimumBasis') or 'exampleApiMin' not in support:fail(id,'missing minimum declaration / example basis')
        if c['status']=='emulator_verified':
            if not c['evidence'] or not support.get('verified'):fail(id,'emulator status without evidence/version')
            if c['validation']['emulator'].get('status')!='passed_in_scope':fail(id,'main status inconsistent with emulator validation')
        if c['status']=='research' and c['validation']['emulator'].get('status')=='passed_in_scope':fail(id,'research cannot claim emulator passed')
        if c['status']=='device_verified':
            p=c['validation']['physicalDevice']
            if p.get('status')!='passed_in_scope' or not p.get('devices') or not p.get('evidence'):fail(id,'physical-device status requires exact device and evidence')
        if c['validation']['physicalDevice'].get('status')=='not_verified' and c['validation']['physicalDevice'].get('evidence'):fail(id,'unverified physical status has evidence claim')
        for e in c['examples']:
            for k in ('kind','environment','preconditions','code','expected','executedThisRun'):
                if k not in e or (k!='executedThisRun' and not e[k]):fail(id,f'bad example field {k}')
            if e.get('executedThisRun') and not any(v.get('kind')=='current_probe' for v in c['evidence']):fail(id,'example claims executed this run without fresh probe')
        for e in c['evidence']:
            path=ROOT/e.get('path','')
            if not path.is_file():fail(id,f'evidence missing {e.get("path")}')
            if not e.get('scope') or not e.get('verifiedAt') or not e.get('executionDate'):fail(id,'evidence missing scope/date')
            if e.get('kind')=='historical_artifact' and e.get('executionDate')=='this_run':fail(id,'historical evidence inflated to fresh test')
            if path.suffix=='.txt' and 'test' in path.name:
                text=path.read_text()
                if not re.search(r'OK \(\d+ tests?\)',text) or 'FAILURES!!!' in text:fail(id,'test artifact has no passed result / has failures')
        for s in c['sources']:
            p=urllib.parse.urlparse(s.get('url',''))
            if p.scheme!='https' or not p.netloc:fail(id,'source must have direct HTTPS URL')
            if not s.get('title') or not s.get('audit') or not s.get('registeredAt'):fail(id,'missing source provenance')
            if s.get('audit')=='web_content_reviewed' and not s.get('accessedAt'):fail(id,'reviewed source has no access date')
            if s.get('audit')=='official_reference' and s.get('accessedAt') is not None:fail(id,'unread source may not claim access date')
    expected=dict(counts); expected.setdefault('device_verified',0)
    if data.get('counts')!=expected:errors.append('top-level counts inconsistent')
    if data.get('verificationTarget',{}).get('physicalDevicesVerified')==0 and counts['device_verified']>0:errors.append('physical target count contradicts capability statuses')
    return errors

def main():
    data=json.loads((ROOT/'spec/capabilities.json').read_text()); errors=validate(data)
    schema=json.loads((ROOT/'spec/capability.schema.json').read_text())
    for c in data['capabilities']:
        errors.extend(schema_errors(c,schema,c['id']))
    index=json.loads((ROOT/'evidence/api-inventory/historical-index.json').read_text())
    for e in index:
        p=ROOT/e['path']
        if not p.exists() or hashlib.sha256(p.read_bytes()).hexdigest()!=e['sha256']:errors.append(f'Historical SHA mismatch: {e["path"]}')
    for c in data['capabilities']:
        ext='kt' if c['examples'][0]['kind']=='android_api_snippet' else 'sh'
        p=ROOT/'examples/capabilities'/f'{c["id"]}.{ext}'
        if not p.exists() or c['examples'][0]['code'] not in p.read_text():errors.append(f'Example missing/drift: {c["id"]}')
    page=(ROOT/'site/capabilities.html').read_text()
    embedded=re.search(r'<script id="data" type="application/json">(.*?)</script>',page,re.S)
    if not embedded or json.loads(embedded.group(1))!=data:errors.append('HTML data stale')
    scripts=re.findall(r'<script>(.*?)</script>',page,re.S)
    for s in scripts:
        with tempfile.NamedTemporaryFile('w',suffix='.js') as f:
            f.write(s);f.flush()
            p=subprocess.run(['node','--check',f.name],capture_output=True,text=True)
            if p.returncode:errors.append('HTML JS syntax: '+p.stderr)
    # A real negative gate: an unsupported physical claim must be refused.
    bad=json.loads(json.dumps(data));bad['capabilities'][0]['status']='device_verified'
    if not any('physical-device status requires' in x for x in validate(bad)):errors.append('Negative physical-device claim gate failed')
    # Evidence absence must also be refused when a row is made green.
    bad=json.loads(json.dumps(data));bad['capabilities'][0]['evidence']=[]
    if not any('without evidence' in x for x in validate(bad)):errors.append('Negative missing-evidence gate failed')
    report={'checkedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'ok':not errors,
            'capabilities':len(data['capabilities']),'counts':data['counts'],'historicalHashFiles':len(index),
            'checks':['capability.schema.json actual declared types/required/enum/oneOf/minima','required fields/enums','permission/authorization and version basis','emulator evidence+scope+dates','physical device negative gate','missing evidence negative gate','source HTTPS/date/audit','passed UI log marker','historical SHA-256','generated examples and HTML data equality','node --check HTML scripts'],
            'errors':errors,'boundary':'Static/evidence gates, not fresh GUI re-execution or browser visual acceptance'}
    parser=argparse.ArgumentParser()
    parser.add_argument('--output', default=str(ROOT/'out/checks/capability-inventory.json'))
    output=pathlib.Path(parser.parse_args().output);output.parent.mkdir(parents=True,exist_ok=True)
    output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(report,ensure_ascii=False,indent=2));raise SystemExit(1 if errors else 0)
if __name__=='__main__':main()
