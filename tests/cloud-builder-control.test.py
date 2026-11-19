#!/usr/bin/env python3
import copy
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('controller', Path(__file__).resolve().parents[1] / 'scripts/cloud-builder-control.py')
c = importlib.util.module_from_spec(spec); spec.loader.exec_module(c)
REQUEST = '14889d25-6301-48dc-9396-df0209e7c723'
ENV = {'OPERATION': 'status', 'REQUEST_ID': REQUEST, 'GITHUB_REPOSITORY': c.REPOSITORY,
       'GITHUB_REPOSITORY_ID': '1462376421', 'GITHUB_REPOSITORY_OWNER_ID': '242648642',
       'GITHUB_REF': 'refs/heads/main', 'GITHUB_EVENT_NAME': 'workflow_dispatch',
       'GITHUB_WORKFLOW_REF': c.REPOSITORY + '/' + c.WORKFLOW + '@refs/heads/main',
       'GITHUB_SHA': 'a'*40, 'GITHUB_RUN_ID': '123', 'GITHUB_RUN_ATTEMPT': '1'}
LINK = 'https://www.googleapis.com/compute/v1/projects/' + c.PROJECT


def vm(status='RUNNING'):
    return {'id': c.INSTANCE_ID, 'name': c.INSTANCE, 'zone': LINK + '/zones/' + c.ZONE,
            'selfLink': LINK + c.INSTANCE_PATH, 'status': status,
            'networkInterfaces': [{'accessConfigs': [{'natIP': '35.221.149.4'}]}]}


def operation(status='DONE'):
    return {'name': 'operation-123', 'targetId': c.INSTANCE_ID, 'targetLink': LINK + c.INSTANCE_PATH,
            'zone': LINK + '/zones/' + c.ZONE, 'operationType': 'start', 'status': status}


class ControlTests(unittest.TestCase):
    def test_ref_repo_and_workflow_are_bound(self):
        for field in ('GITHUB_REPOSITORY', 'GITHUB_REPOSITORY_ID', 'GITHUB_REPOSITORY_OWNER_ID',
                      'GITHUB_REF', 'GITHUB_EVENT_NAME', 'GITHUB_WORKFLOW_REF', 'REQUEST_ID'):
            with self.subTest(field=field), self.assertRaises(ValueError):
                c.request_context({**ENV, field: 'untrusted'})

    def test_status_is_read_only_and_excludes_metadata(self):
        calls = []
        def api(*args):
            calls.append(args); return {**vm(), 'metadata': {'items': [{'key':'private', 'value':'never export'}]}}
        result = c.control(c.request_context(ENV), api)
        self.assertEqual(calls, [('GET', c.INSTANCE_PATH)])
        self.assertEqual(result['externalIp'], '35.221.149.4')
        self.assertNotIn('metadata', result)

    def test_wrong_vm_rejected_before_mutation(self):
        calls = []
        def api(*args):
            calls.append(args); return {**vm('TERMINATED'), 'id':'7'}
        with self.assertRaises(ValueError):
            c.control(c.request_context({**ENV,'OPERATION':'start'}), api)
        self.assertEqual(len(calls), 1)

    def test_start_polls_only_its_operation_and_posts_once(self):
        calls = []; replies = [vm('TERMINATED'), operation('RUNNING'), operation(), vm()]
        def api(*args):
            calls.append(args); return replies.pop(0)
        result = c.control(c.request_context({**ENV,'OPERATION':'start'}), api, sleep=lambda _:None)
        self.assertEqual(result['status'], 'RUNNING')
        self.assertEqual(calls, [('GET',c.INSTANCE_PATH), ('POST',c.INSTANCE_PATH+'/start',REQUEST),
                                ('GET','/zones/asia-east1-a/operations/operation-123'), ('GET',c.INSTANCE_PATH)])

    def test_wrong_operation_fails_without_further_queries(self):
        for key,value in [('targetId','7'),('targetLink',LINK+'/zones/asia-east1-a/instances/another'),
                          ('zone',LINK+'/zones/asia-east1-b'),('operationType','delete'),('error',{'code':1})]:
            replies = [vm('TERMINATED'), {**operation(),key:value}]; calls=[]
            def api(*args): calls.append(args); return replies.pop(0)
            with self.subTest(key=key), self.assertRaises(ValueError):
                c.control(c.request_context({**ENV,'OPERATION':'start'}),api)
            self.assertEqual(len(calls),2)

    def test_stopped_receipt_does_not_publish_address(self):
        result=c.control(c.request_context({**ENV,'OPERATION':'stop'}),lambda *_:vm('TERMINATED'))
        self.assertIsNone(result['externalIp'])

    def test_transition_and_ambiguous_address_rejected(self):
        with self.assertRaises(ValueError):
            c.control(c.request_context({**ENV,'OPERATION':'start'}),lambda *_:vm('STAGING'))
        value=vm();value['networkInterfaces']*=2
        with self.assertRaises(ValueError): c.control(c.request_context(ENV),lambda *_:value)

    def test_resource_links_cannot_escape(self):
        self.assertTrue(c.resource_link(c.BASE+c.INSTANCE_PATH,c.INSTANCE_PATH))
        for value in ['http://www.googleapis.com'+c.INSTANCE_PATH,
                      LINK+c.INSTANCE_PATH+'?override=1', LINK+c.INSTANCE_PATH+'#x',
                      'https://www.googleapis.com.attacker.invalid/compute/v1/projects/'+c.PROJECT+c.INSTANCE_PATH]:
            self.assertFalse(c.resource_link(value,c.INSTANCE_PATH))


if __name__ == '__main__':unittest.main()
