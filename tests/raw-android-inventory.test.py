"""Inventory integrity checks, not API execution or device acceptance."""
import collections, gzip, json, pathlib, unittest
ROOT=pathlib.Path(__file__).resolve().parents[1]

class InventoryContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls): cls.data=json.load(gzip.open(ROOT/'spec/raw-android-interfaces.json.gz','rt'))
    def test_counts_and_source_references_are_complete(self):
        d=self.data
        self.assertEqual(dict(collections.Counter(r['kind'] for r in d['records'])),d['counts'])
        for r in d['records']:
            self.assertTrue(r['declaration'])
            self.assertIn(r['status'],('declaration_only','artifact_present_not_executed'))
            if r['source'] is not None:self.assertIn(r['source'],range(len(d['sources'])))
        for s in d['sources']:
            self.assertRegex(s['sha256'],r'^[a-f0-9]{64}$')
            if 'revision' in s:self.assertRegex(s['revision'],r'^[a-f0-9]{40}$')
    def test_required_surfaces_and_no_false_device_claim(self):
        for kind in ('public','system','module-lib','test','system-server','binder','hal','permissions','native','native-header','kernel-uapi','syscalls','sysprop','shell-source','toybox-help','non-sdk','image-binary','sdk-annotations'):
            self.assertGreater(self.data['counts'][kind],0,kind)
        self.assertEqual(self.data['diagnostics'],[])
        self.assertFalse(self.data['physicalDeviceVerified'])
        self.assertFalse(self.data['apiCallVerified'])
    def test_each_overload_is_retained(self):
        methods=[r for r in self.data['records'] if r['kind']=='public' and r['owner']=='android.app.WallpaperManager' and ' setBitmap(' in r['declaration']]
        self.assertGreater(len(methods),1)
        self.assertEqual(len({(r['source'],r['line']) for r in methods}),len(methods))
    def test_generated_evidence_and_sdk_supplement_are_labelled(self):
        for r in self.data['records']:
            if r['kind']=='non-sdk':self.assertEqual(self.data['sources'][r['source']]['provenance'],'candidate_build_generated')
            if r['kind']=='sdk-annotations':self.assertEqual(self.data['sources'][r['source']]['provenance'],'installed_android_sdk_supplement')
        self.assertFalse(self.data['sdkSupplement']['sameUpstreamRevision'])

if __name__=='__main__':unittest.main()
