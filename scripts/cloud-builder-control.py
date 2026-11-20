#!/usr/bin/env python3
"""Narrow GitHub WIF control plane. No SSH key, human login or artifact deletion."""
import argparse
import base64
import datetime
import ipaddress
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import urllib.parse
import uuid

PROJECT = 'diesel-ally-502307-h5'
ZONE = 'asia-east1-a'
INSTANCE = 'taku-os-builder'
INSTANCE_ID = '8380192091917522630'
WORKFLOW = '.github/workflows/taku-cloud-control.yml'
REPOSITORY = 'Taku-OS/taku-os'
BASE = 'https://compute.googleapis.com/compute/v1/projects/' + PROJECT
INSTANCE_PATH = '/zones/' + ZONE + '/instances/' + INSTANCE


def require(condition, message):
    if not condition:
        raise ValueError(message)


def request_context(env):
    require(env.get('OPERATION') in ('status', 'start', 'stop'), 'Unsupported operation')
    request_id = env.get('REQUEST_ID', '')
    require(str(uuid.UUID(request_id)) == request_id and uuid.UUID(request_id).int != 0,
            'Expected a canonical nonzero request UUID')
    require(env.get('GITHUB_REPOSITORY') == REPOSITORY
            and env.get('GITHUB_REPOSITORY_ID') == '1462376421'
            and env.get('GITHUB_REPOSITORY_OWNER_ID') == '242648642'
            and env.get('GITHUB_REF') == 'refs/heads/main'
            and env.get('GITHUB_EVENT_NAME') == 'workflow_dispatch'
            and env.get('GITHUB_WORKFLOW_REF') == REPOSITORY + '/' + WORKFLOW + '@refs/heads/main',
            'Only the pinned repository main workflow can control this builder')
    require(re.fullmatch(r'[0-9a-f]{40}', env.get('GITHUB_SHA', ''))
            and re.fullmatch(r'[1-9][0-9]*', env.get('GITHUB_RUN_ID', ''))
            and re.fullmatch(r'[1-9][0-9]*', env.get('GITHUB_RUN_ATTEMPT', '')), 'Invalid workflow provenance')
    return {'requestId': request_id, 'operation': env['OPERATION'],
            'github': {'repository': REPOSITORY, 'workflow': WORKFLOW,
                       'runId': env['GITHUB_RUN_ID'], 'runAttempt': env['GITHUB_RUN_ATTEMPT'],
                       'headSha': env['GITHUB_SHA'], 'ref': env['GITHUB_REF']}}


class GoogleApi:
    def __init__(self, token):
        require(token and '\n' not in token, 'Missing short-lived workflow credential')
        self.token = token

    def __call__(self, method, path, request_id=None):
        require(path == INSTANCE_PATH or path in (INSTANCE_PATH + '/start', INSTANCE_PATH + '/stop')
                or re.fullmatch(r'/zones/asia-east1-a/operations/[A-Za-z0-9_.-]+', path), 'Unexpected API resource')
        require(method == 'GET' or (method == 'POST' and path in (INSTANCE_PATH + '/start', INSTANCE_PATH + '/stop')),
                'Unexpected API operation')
        query = {}
        if method == 'GET' and path == INSTANCE_PATH:
            query['fields'] = 'id,name,selfLink,zone,status,networkInterfaces(accessConfigs(natIP))'
        elif '/operations/' in path or method == 'POST':
            query['fields'] = 'name,targetId,targetLink,zone,operationType,status,error,httpErrorStatusCode'
        if request_id:
            require(str(uuid.UUID(request_id)) == request_id, 'Invalid API request UUID')
            query['requestId'] = request_id
        require(method != 'POST' or request_id, 'Mutation requires an explicit request UUID')
        url = BASE + path + '?' + urllib.parse.urlencode(query)
        req = urllib.request.Request(url, data=b'{}' if method == 'POST' else None, method=method,
                headers={'Authorization': 'Bearer ' + self.token, 'Content-Type': 'application/json'})
        # Do not retry a write with uncertain delivery, or print credentials/raw API errors.
        try:
            class NoRedirect(urllib.request.HTTPRedirectHandler):
                def redirect_request(self, req, fp, code, msg, headers, newurl):
                    raise ValueError('Google API redirects are not permitted')
            with urllib.request.build_opener(NoRedirect).open(req, timeout=45) as response:
                require(response.url == url, 'Unexpected API redirect')
                data = response.read(2 * 1024 * 1024 + 1)
                require(len(data) <= 2 * 1024 * 1024, 'Unexpected API response size')
                value = json.loads(data)
        except urllib.error.HTTPError as error:
            raise RuntimeError('Google Compute API returned HTTP ' + str(error.code)) from None
        require(isinstance(value, dict), 'Unexpected API response')
        return value


def resource_link(value, path):
    url = urllib.parse.urlsplit(value or '')
    return (url.scheme == 'https' and url.netloc in ('www.googleapis.com', 'compute.googleapis.com')
            and url.path == '/compute/v1/projects/' + PROJECT + path and not url.query and not url.fragment)


def instance_identity(value):
    require(str(value.get('id')) == INSTANCE_ID and value.get('name') == INSTANCE
            and resource_link(value.get('zone'), '/zones/' + ZONE)
            and resource_link(value.get('selfLink'), INSTANCE_PATH), 'Cloud instance identity differs from the existing pin')
    require(value.get('status') in ('PROVISIONING', 'STAGING', 'RUNNING', 'STOPPING', 'SUSPENDING',
                                   'SUSPENDED', 'TERMINATED', 'REPAIRING'), 'Unknown instance status')
    return value


def control(context, api, *, monotonic=time.monotonic, sleep=time.sleep):
    vm = instance_identity(api('GET', INSTANCE_PATH))
    operation = context['operation']
    desired = {'start': 'RUNNING', 'stop': 'TERMINATED'}.get(operation)
    if desired and vm['status'] != desired:
        require(vm['status'] == ('TERMINATED' if operation == 'start' else 'RUNNING'),
                'Instance is in transition; inspect it before a new mutation')
        result = api('POST', INSTANCE_PATH + '/' + operation, context['requestId'])
        op_name = result.get('name', '')
        require(re.fullmatch(r'[A-Za-z0-9_.-]+', op_name), 'Missing operation identity')
        op_path = '/zones/' + ZONE + '/operations/' + op_name
        deadline = monotonic() + 600
        while True:
            require(str(result.get('targetId')) == INSTANCE_ID
                    and resource_link(result.get('targetLink'), INSTANCE_PATH)
                    and resource_link(result.get('zone'), '/zones/' + ZONE)
                    and result.get('operationType') == operation
                    and result.get('name') == op_name, 'Operation does not belong to the requested builder')
            require(not result.get('error') and not result.get('httpErrorStatusCode'), 'Cloud operation failed')
            if result.get('status') == 'DONE':
                break
            require(result.get('status') in ('PENDING', 'RUNNING') and monotonic() < deadline,
                    'Cloud operation did not complete; do not repeat an uncertain write')
            sleep(3)
            result = api('GET', op_path)
        vm = instance_identity(api('GET', INSTANCE_PATH))
        require(vm['status'] == desired, 'Completed operation does not match the requested instance state')
    addresses = [a['natIP'] for n in vm.get('networkInterfaces', []) for a in n.get('accessConfigs', []) if a.get('natIP')]
    external_ip = None
    if vm['status'] == 'RUNNING':
        require(len(addresses) == 1 and ipaddress.ip_address(addresses[0]).version == 4
                and ipaddress.ip_address(addresses[0]).is_global, 'Expected exactly one public builder IPv4 address')
        external_ip = addresses[0]
    return {'schema': 'taku.cloud-builder-endpoint/1', **context, 'project': PROJECT, 'zone': ZONE,
            'instance': INSTANCE, 'instanceId': INSTANCE_ID, 'status': vm['status'], 'externalIp': external_ip,
            'observedAt': datetime.datetime.now(datetime.timezone.utc).isoformat()}


def validate_seal_public_key(pem):
    text = (pem or '').strip()
    require(text.startswith('-----BEGIN PUBLIC KEY-----') and text.endswith('-----END PUBLIC KEY-----'),
            'Missing endpoint seal public key')
    return text + '\n'


def seal_endpoint(endpoint, public_key_pem, *, openssl='openssl'):
    key_text = validate_seal_public_key(public_key_pem)
    sealed = dict(endpoint)
    if sealed.get('status') == 'RUNNING':
        payload = json.dumps({'requestId': sealed['requestId'], 'instanceId': sealed['instanceId'],
                              'externalIp': sealed['externalIp']}, separators=(',', ':')).encode()
        with tempfile.NamedTemporaryFile('w', suffix='.pem', delete=True) as key_file:
            key_file.write(key_text)
            key_file.flush()
            proc = subprocess.run(
                [openssl, 'pkeyutl', '-encrypt', '-pubin', '-inkey', key_file.name,
                 '-pkeyopt', 'rsa_padding_mode:oaep', '-pkeyopt', 'rsa_oaep_md:sha256',
                 '-pkeyopt', 'rsa_mgf1_md:sha256'],
                input=payload, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=15, check=False)
        require(proc.returncode == 0 and len(proc.stdout) >= 256, 'Failed to seal endpoint address')
        sealed['encryptedEndpoint'] = base64.b64encode(proc.stdout).decode('ascii')
        sealed['externalIp'] = None
    else:
        sealed['encryptedEndpoint'] = None
    return sealed


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--validate-only', action='store_true')
    args = parser.parse_args()
    context = request_context(os.environ)
    seal_key = os.environ.get('ENDPOINT_SEAL_PUBLIC_KEY', '')
    validate_seal_public_key(seal_key)
    if args.validate_only:
        print('Fixed builder request validated')
        return
    endpoint = seal_endpoint(control(context, GoogleApi(os.environ.get('GOOGLE_ACCESS_TOKEN', ''))), seal_key)
    target = Path('out/cloud-control/endpoint.json')
    target.parent.mkdir(parents=True, exist_ok=True)
    with target.open('x') as out:
        json.dump(endpoint, out, indent=2); out.write('\n')
    print(json.dumps({'operation': endpoint['operation'], 'status': endpoint['status'],
                      'requestId': endpoint['requestId'], 'endpointWritten': True}))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print(type(error).__name__ + ': ' + str(error), file=sys.stderr)
        raise SystemExit(1)
