"""Exact frozen-controller hooks for the scoped tokay carrier candidate."""
import re

PARENT = '8b77b5c8829ae9d968de8d95c689828869ca6b95bcbc0ec37643e95b062a7565'
PARENT_MANIFEST = '8493aa5bdb8b85b57f1b8bb17c9a5227db25bf666372c28dd13207091ce2cf2d'
VERSIONS = {'ime': 10062, 'assistant': 74, 'memory': 8}
ADDITIONS = ('image/carrier_defaults.py', 'image/carrier-defaults/manifest.json',
             'image/carrier-defaults/telecom-volte.patch')
OVERLAYS = (*ADDITIONS, 'image/build.sh', 'image/verify-artifacts.py',
            'image/product-branding.json', 'image/product-branding.mk', 'image/product_branding.py',
            'image/apply-product-branding.py', 'image/apply-updater-patches.py',
            'image/branding/manifest.json', 'image/branding/updater-v9.patch',
            'image/branding/previous-v8-manifest.json')


def once(data, before, after):
    if data.count(before) != 1:
        raise ValueError('Frozen carrier hook is missing or ambiguous: ' + before.decode().strip())
    return data.replace(before, after)


def build_hook(data):
    if b'carrier_defaults.py' in data:
        raise ValueError('Frozen build already contains a carrier hook')
    apply = (b'    if [ -f /taku/image/carrier-defaults/manifest.json ]; then\n'
             b'        python3 /taku/image/carrier_defaults.py apply --source-root /src --results /results\n'
             b'    fi\n')
    data = once(data, b'    phase integrate\n', b'    phase integrate\n' + apply)
    data = once(data, b'|| "${1:-}" = "--google-apps-only" ]]; then',
                b'|| "${1:-}" = "--google-apps-only" || "${1:-}" = "--telephony-only" ]]; then')
    verify = (b'    if [ -f /taku/image/carrier-defaults/manifest.json ]; then\n'
              b'        python3 /taku/image/carrier_defaults.py verify-apk --source-root /src '
              b'--product out/target/product/tokay --results /results\n'
              b'    fi\n')
    data = once(data, b'verify_artifacts() {\n', b'verify_artifacts() {\n' + verify)
    preview = (b'if [[ "${1:-}" = "--telephony-only" ]]; then\n'
               b'    m -j"$jobs" TeleService Updater\n'
               b'    python3 /taku/image/apply-product-branding.py --source-root /src --results-dir /results '
               b'--apk /src/out/target/product/tokay/system_ext/priv-app/Updater/Updater.apk\n'
               b'    python3 /taku/image/carrier_defaults.py verify-apk --source-root /src '
               b'--product out/target/product/tokay --results /results\n'
               b'    phase telephony-preview-verified\n'
               b'    exit 0\n'
               b'fi\n')
    return once(data, b'm -j"$jobs" target-files-package bacon\n',
                preview + b'm -j"$jobs" target-files-package bacon\n')


def artifact_hook(data):
    if b'carrier_defaults_integration' in data:
        raise ValueError('Frozen artifact verifier already contains a carrier hook')
    data = once(data, b'google_apps_integration=None\n',
                b'google_apps_integration=None\ncarrier_defaults_integration=None\n')
    marker = re.findall(rb'^    prohibited=.*\n', data, re.M)
    if len(marker) != 1:
        raise ValueError('Frozen carrier artifact integration anchor differs')
    gate = (b"    if 'carrierDefaults' in run_input:\n"
            b'        import carrier_defaults\n'
            b'        carrier_defaults_integration=carrier_defaults.verify_archive(archive,results,run_input)\n')
    data = once(data, marker[0], gate + marker[0])
    return once(data, b"receipt_path=results/'baseline-receipt.json'\n",
                b"if carrier_defaults_integration:receipt['carrierDefaultsIntegration']=carrier_defaults_integration\n"
                b"receipt_path=results/'baseline-receipt.json'\n")
