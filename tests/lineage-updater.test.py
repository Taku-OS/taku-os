#!/usr/bin/env python3
"""Apply the real fixed-source diff and execute its Java byte checks locally.

These tests are source/transport checks, never APK, ROM or phone acceptance.
"""
import importlib.util
import hashlib
import json
import pathlib
import shutil
import subprocess
import tempfile
import unittest
from unittest import mock

ROOT = pathlib.Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("updater_patch", ROOT / "image/apply-updater-patches.py")
PATCH = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PATCH)
MANIFEST = ROOT / "image/updater/manifest.json"


class FixedPatch(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name).resolve()
        self.repo = self.root / PATCH.REPOSITORY
        shutil.copytree(ROOT / "tests/fixtures/lineage-updater", self.repo)
        self.head = mock.patch.object(PATCH, "git_revision", return_value=PATCH.UPSTREAM)
        self.head.start(); self.addCleanup(self.head.stop)
        self.results = self.root / "audit"

    def test_patch_actual_fixed_files_and_idempotence(self):
        manifest, _ = PATCH.load_contract(MANIFEST)
        for item in manifest["files"]:
            path = self.repo / item["path"]
            self.assertEqual(PATCH.digest(path) if path.exists() else None, item["beforeSha256"])
        proof = PATCH.apply(self.root, self.results, MANIFEST)
        original_proof = proof.read_bytes()
        self.assertFalse(json.loads(original_proof)["physicalDeviceVerified"])
        for item in manifest["files"]:
            self.assertEqual(PATCH.digest(self.repo / item["path"]), item["afterSha256"])
        PATCH.apply(self.root, self.results, MANIFEST)
        self.assertEqual(proof.read_bytes(), original_proof)

    def test_old_and_current_aapt_sdk_fields_remain_strictly_api_37(self):
        for minimum in ('sdkVersion', 'minSdkVersion'):
            PATCH.verify_sdk_badging(minimum+":'37'\ntargetSdkVersion:'37'\n")
        for badging in ["minSdkVersion:'36'\ntargetSdkVersion:'37'\n",
                        "minSdkVersion:'37'\ntargetSdkVersion:'36'\n",
                        "minSdkVersion:'10000'\ntargetSdkVersion:'10000'\n",
                        "minSdkVersion:'CinnamonBun'\ntargetSdkVersion:'37'\n",
                        "targetSdkVersion:'37'\n", "minSdkVersion:'37'\n",
                        "minSdkVersion:'37'\nsdkVersion:'37'\ntargetSdkVersion:'37'\n",
                        "minSdkVersion:'37'\nsdkVersion:'36'\ntargetSdkVersion:'37'\n",
                        "minSdkVersion:'37'\nminSdkVersion:'37'\ntargetSdkVersion:'37'\n",
                        "minSdkVersion:'37'\ntargetSdkVersion:'37'\ntargetSdkVersion:'37'\n",
                        "minSdkVersion:'37'\nsdkVersion:\ntargetSdkVersion:'37'\n"]:
            with self.subTest(badging=badging), self.assertRaises(ValueError):
                PATCH.verify_sdk_badging(badging)

    def test_shared_byte_verifier_accepts_supported_versions_and_still_binds_apk_and_signer(self):
        # Host tool outputs/DER/APK are explicit stand-ins. Exercise the real
        # shared validator that the branded ROM gate calls; no crypto claim.
        host=self.root/'out/host/linux-x86/bin';host.mkdir(parents=True)
        for name in ('aapt2','apksigner'):
            tool=host/name;tool.write_text('#!/bin/sh\n');tool.chmod(0o755)
        key=self.root/'build/make/target/product/security/platform.x509.pem'
        key.parent.mkdir(parents=True);key.write_text('fixture platform PEM')
        apk=self.root/'Updater.apk';apk.write_bytes(b'fixture APK, not Android acceptance')
        certificate=hashlib.sha256(b'fixture DER, not crypto acceptance').hexdigest()
        signer='Verifies\nNumber of signers: 1\nSigner #1 certificate SHA-256 digest: '+certificate+'\n'
        def badging(version):return "package: name='org.lineageos.updater' versionCode='"+str(version)+"'\nminSdkVersion:'37'\ntargetSdkVersion:'37'\n"
        with mock.patch.object(PATCH.ssl,'PEM_cert_to_DER_cert',return_value=b'fixture DER, not crypto acceptance'):
            for version in (2,3,4,5,6,7,8,9,10,11):
                with self.subTest(version=version),mock.patch.object(PATCH.subprocess,'check_output',side_effect=[badging(version),signer]):
                    record=PATCH.verify_apk_bytes(self.root,apk,self.results/str(version),'org.lineageos.updater',version,'a'*64)
                    self.assertEqual(record['versionCode'],version)
                    self.assertEqual(record['apkSha256'],hashlib.sha256(apk.read_bytes()).hexdigest())
                    self.assertEqual(record['platformCertificateSha256'],certificate)
            with mock.patch.object(PATCH.subprocess,'check_output',side_effect=[badging(3),signer]),self.assertRaisesRegex(ValueError,'package/version differs'):
                PATCH.verify_apk_bytes(self.root,apk,self.results/'wrong-version','org.lineageos.updater',4,'a'*64)
            with mock.patch.object(PATCH.subprocess,'check_output',side_effect=[badging(4),signer.replace(certificate,'b'*64)]),self.assertRaises(ValueError):
                PATCH.verify_apk_bytes(self.root,apk,self.results/'wrong-signer','org.lineageos.updater',4,'a'*64)
        for version in (0,1,12,True,4.0,'4'):
            with self.subTest(invalid=version),mock.patch.object(PATCH.subprocess,'check_output') as tool,self.assertRaisesRegex(ValueError,'Invalid APK verification contract'):
                PATCH.verify_apk_bytes(self.root,apk,self.results/'invalid','org.lineageos.updater',version,'a'*64)
            tool.assert_not_called()

    def test_wrong_upstream_and_partial_drift_rejected(self):
        with mock.patch.object(PATCH, "git_revision", return_value="0" * 40):
            with self.assertRaisesRegex(ValueError, "HEAD differs"):
                PATCH.apply(self.root, self.results, MANIFEST)
        target = self.repo / "app/src/main/AndroidManifest.xml"
        target.write_text(target.read_text().replace('versionCode="1"', 'versionCode="9"'))
        with self.assertRaisesRegex(ValueError, "source drift"):
            PATCH.apply(self.root, self.results, MANIFEST)
        self.assertFalse((self.results / "updater-integration.json").exists())

    def test_old_and_current_apksigner_formats_bind_one_platform_certificate(self):
        certificate = "a" * 64
        for label in ("Signer #1", "V3.0 Signer:"):
            valid = "Verifies\nNumber of signers: 1\n" + label + " certificate SHA-256 digest: " + certificate + "\n"
            PATCH.verify_platform_signer(valid, certificate)
            for invalid in [valid.replace("signers: 1", "signers: 2"),
                            valid.replace("Number of signers: 1\n", ""),
                            valid + "Number of signers: 1\n",
                            valid + label + " certificate SHA-256 digest: " + certificate + "\n",
                            valid + "Signer #2 certificate SHA-256 digest: " + "b" * 64 + "\n",
                            valid.replace(certificate, "b" * 64),
                            valid.replace(certificate, "a" * 63),
                            valid.replace(label, "V3.1 Signer"),
                            valid.replace(label, "Signer #0"),
                            valid.replace(label, " " + label),
                            valid.replace("signers: 1", "signers: 1 trailing"),
                            valid.replace("certificate SHA-256 digest:", "certificate SHA-256 digest: extra")]:
                with self.subTest(signatures=invalid), self.assertRaises(ValueError):
                    PATCH.verify_platform_signer(invalid, certificate)

    def test_patch_tamper_and_unknown_target_rejected(self):
        directory = self.root / "contract"
        shutil.copytree(MANIFEST.parent, directory)
        path = directory / "download-integrity.patch"
        path.write_text(path.read_text() + "\n")
        with self.assertRaisesRegex(ValueError, "patch bytes differ"):
            PATCH.load_contract(directory / "manifest.json")
        data = json.loads((directory / "manifest.json").read_text())
        data["files"][0]["path"] = "../../unexpected"
        (directory / "manifest.json").write_text(json.dumps(data))
        with self.assertRaisesRegex(ValueError, "target set differs"):
            PATCH.load_contract(directory / "manifest.json")

    def test_actual_java_integrity_and_range_checks(self):
        PATCH.apply(self.root, self.results, MANIFEST)
        helper = self.repo / PATCH.ADDITION
        harness = self.root / "IntegrityHarness.java"
        harness.write_text(r'''
import java.io.*;
import java.nio.file.*;
import org.lineageos.updater.download.DownloadIntegrity;
public class IntegrityHarness {
    interface Check { void run() throws Exception; }
    static void rejects(Check check) throws Exception {
        try { check.run(); } catch (IOException expected) { return; }
        throw new AssertionError("Expected rejected bytes/range");
    }
    static void equal(long expected, long actual) {
        if (expected != actual) throw new AssertionError(actual + " != " + expected);
    }
    public static void main(String[] args) throws Exception {
        File file = new File(args[0]);
        if (DownloadIntegrity.hasResumeData(file)) throw new AssertionError("Missing first-attempt file");
        Files.write(file.toPath(), new byte[0]);
        if (DownloadIntegrity.hasResumeData(file)) throw new AssertionError("Empty first-attempt file");
        Files.write(file.toPath(), "abc".getBytes("UTF-8"));
        if (!DownloadIntegrity.hasResumeData(file)) throw new AssertionError("Lost resume basis");
        String sha = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
        DownloadIntegrity.verifyFile(file, sha.toUpperCase(), 3);
        rejects(() -> DownloadIntegrity.verifyFile(file, sha, 2));
        rejects(() -> DownloadIntegrity.verifyFile(file, "0".repeat(64), 3));
        rejects(() -> DownloadIntegrity.verifyFile(file, "abc", 3));
        rejects(() -> DownloadIntegrity.verifyFile(file, sha, 0));
        equal(10, DownloadIntegrity.validateResponse(false, 0, 200, null, 10));
        equal(-1, DownloadIntegrity.validateResponse(false, 0, 200, null, -1));
        equal(10, DownloadIntegrity.validateResponse(true, 3, 206, "bytes 3-9/10", 7));
        equal(10, DownloadIntegrity.validateResponse(true, 3, 206, "bytes 3-9/10", -1));
        rejects(() -> DownloadIntegrity.validateResponse(true, 3, 200, null, 10));
        rejects(() -> DownloadIntegrity.validateResponse(true, 3, 206, "bytes 4-9/10", 6));
        rejects(() -> DownloadIntegrity.validateResponse(true, 3, 206, "bytes 3-8/10", 6));
        rejects(() -> DownloadIntegrity.validateResponse(true, 3, 206, "bytes 3-9/10", 8));
        rejects(() -> DownloadIntegrity.validateResponse(false, 0, 206, "bytes 0-9/10", 10));
        rejects(() -> DownloadIntegrity.validateResponse(false, 0, 200, "bytes 0-9/10", 10));
        rejects(() -> DownloadIntegrity.parseContentRange("bytes 0-10/10"));
        rejects(() -> DownloadIntegrity.parseContentRange("bytes 9-3/10"));
        rejects(() -> DownloadIntegrity.parseContentRange("bytes 3-9/*"));
        rejects(() -> DownloadIntegrity.parseContentRange("bytes 0-1/9223372036854775808"));
        DownloadIntegrity.verifyLength(10, 10);
        rejects(() -> DownloadIntegrity.verifyLength(9, 10));
        rejects(() -> DownloadIntegrity.verifyLength(11, 10));
        Files.write(file.toPath(), "abd".getBytes("UTF-8"));
        rejects(() -> DownloadIntegrity.verifyFile(file, sha, 3));
    }
}
''')
        subprocess.run(["javac", "-d", str(self.root / "classes"), str(helper), str(harness)], check=True)
        subprocess.run(["java", "-cp", str(self.root / "classes"), "IntegrityHarness", str(self.root / "download.zip")], check=True)

    def test_actual_controller_missing_file_resume_reaches_fresh_download(self):
        PATCH.apply(self.root, self.results, MANIFEST)
        source=(self.repo/'app/src/main/java/org/lineageos/updater/controller/UpdaterController.java').read_text()
        def method(name):
            start=source.index('    public void '+name+'(')
            opening=source.index('{',start);depth=1;end=opening+1
            while depth:
                depth+=(source[end]=='{')-(source[end]=='}');end+=1
            return source[start:end]
        # Compile and execute the actual patched controller methods. Only their
        # Android/network collaborators are stand-ins; no copy of the branch is
        # reimplemented in the test.
        harness=self.root/'ControllerHarness.java'
        harness.write_text(r'''
import java.io.*;
import java.nio.file.*;
import java.util.*;
public class ControllerHarness {
    static class Log { static void d(String t,String m) {} static void e(String t,String m) {} }
    static class UpdateStatus { static final String PAUSED_ERROR="error",STARTING="starting",VERIFYING="verifying"; }
    static class Update {
        File file;String status="paused";Update(File f){file=f;}
        File getFile(){return file;} String getName(){return "fixture.zip";}
        String getDownloadUrl(){return "https://fixture.invalid/package.zip";}
        Update withFile(File f){file=f;return this;} Update withStatus(String s){status=s;return this;}
    }
    static class DownloadEntry { Update mUpdate;DownloadEntry(Update u){mUpdate=u;} }
    static class DownloadClient {
        static int starts=0,resumes=0;
        void start(){starts++;}void resume(){resumes++;}
        static class Builder {
            Builder setUrl(String s){return this;}Builder setDestination(File f){return this;}
            Builder setDownloadCallback(Object o){return this;}Builder setProgressListener(Object o){return this;}
            Builder setUseDuplicateLinks(boolean value){return this;}
            DownloadClient build() throws IOException{return new DownloadClient();}
        }
    }
    static class Utils {static File appendSequentialNumber(File f){return new File(f+".1");}}
    static class WakeLock {void acquire(){}}
    String TAG="fixture";Map<String,DownloadEntry> mDownloads=new HashMap<>();
    File mDownloadRoot;WakeLock mWakeLock=new WakeLock();
    boolean isDownloading(String id){return false;}void pauseActiveDownloads(){}
    void notifyUpdateChange(String id){} boolean isFullyDownloaded(Update u){return false;}
    void verifyUpdateAsync(String id){} Object getDownloadCallback(String id){return null;}
    Object getProgressListener(String id){return null;}void addDownloadClient(DownloadEntry e,DownloadClient d){}
''' + method('startDownload')+'\n'+method('resumeDownload')+r'''
    public static void main(String[] args)throws Exception{
        ControllerHarness controller=new ControllerHarness();controller.mDownloadRoot=new File(args[0]);
        Update absent=new Update(new File(args[0],"fixture.zip"));
        controller.mDownloads.put("missing",new DownloadEntry(absent));
        controller.resumeDownload("missing");
        if(DownloadClient.starts!=1||DownloadClient.resumes!=0||!absent.status.equals("starting"))
            throw new AssertionError("Missing-file controller Resume never reached fresh download");
        Update noDestination=new Update(null);controller.mDownloads.put("null",new DownloadEntry(noDestination));
        controller.resumeDownload("null");
        if(DownloadClient.starts!=1||!noDestination.status.equals("error"))throw new AssertionError("Null destination must fail");
        File partial=new File(args[0],"partial.zip");Files.write(partial.toPath(),new byte[]{1,2,3});
        controller.mDownloads.put("partial",new DownloadEntry(new Update(partial)));
        controller.resumeDownload("partial");
        if(DownloadClient.starts!=1||DownloadClient.resumes!=1)throw new AssertionError("Partial file must resume");
    }
}
''')
        subprocess.run(['javac','-d',str(self.root/'controller-classes'),str(harness)],check=True)
        subprocess.run(['java','-cp',str(self.root/'controller-classes'),'ControllerHarness',str(self.root)],check=True)


if __name__ == "__main__":
    unittest.main()
