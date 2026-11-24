#!/system/bin/sh
set -eu

fail() {
    printf '%s\n' "$1" >&2
    exit 1
}

[ -x /system/bin/getprop ] && [ -n "$(/system/bin/getprop ro.build.version.sdk)" ] ||
    fail 'Run the installer on Android.'
[ "$(/system/bin/id -u)" = 0 ] || fail 'Run the installer directly as UID 0.'
api=$(/system/bin/getprop ro.build.version.sdk)
case "$api" in ''|*[!0-9]*) fail 'Invalid Android SDK version.' ;; esac
[ "$api" -ge 36 ] || fail 'Android API 36 or newer is required for this DEX bundle.'

pack=/data/local/agentos/rea-pack
[ -x "$pack/bin/node" ] && [ -d "$pack/lib" ] || fail 'The Taku REA Node runtime is required.'
unset NODE_OPTIONS NODE_PATH LD_PRELOAD
export LD_LIBRARY_PATH="$pack/lib:/system/lib64:/vendor/lib64"
exec "$pack/bin/node" - "$0" "$@" <<'NODE'
'use strict';

const fs = require('node:fs');
const crypto = require('node:crypto');
const path = require('node:path');

const runtimeFiles = [
    'capabilities.json',
    'provenance.json',
    'scripts/command-adapter.jar',
    'scripts/control.sh',
    'scripts/request.cjs',
    'scripts/status.cjs',
    'SKILL.md',
];
const skillName = 'tesla-model-y';
const runtimePath = 'runtime/' + skillName + '/';
const packageFiles = [
    'README.md', 'install.sh', 'vehicle-binding.example.json',
    ...runtimeFiles.map((file) => runtimePath + file),
];
const packageDirs = new Set(['runtime', 'runtime/' + skillName, runtimePath + 'scripts']);
const home = '/data/local/agentos/home';
const codex = path.join(home, '.codex');
const skills = path.join(codex, 'skills');
const skill = path.join(skills, skillName);
const pending = skill + '.pending';
const binding = path.join(skill, 'vehicle-binding.json');

function sha256(body) {
    return crypto.createHash('sha256').update(body).digest('hex');
}

function lstat(file) {
    return fs.lstatSync(file, {throwIfNoEntry: false});
}

function assertDirectory(dir, required = false) {
    const stat = lstat(dir);
    if ((stat && (!stat.isDirectory() || fs.realpathSync(dir) !== dir)) || (required && !stat)) {
        throw Error('Expected a real directory: ' + dir);
    }
}

function within(root, file) {
    return file === root || file.startsWith(root + '/');
}

function readRegular(file, {bytes, mode} = {}) {
    if (fs.realpathSync(file) !== file) {
        throw Error('File path contains a symbolic link.');
    }
    const fd = fs.openSync(file, fs.constants.O_RDONLY | fs.constants.O_NOFOLLOW | fs.constants.O_NONBLOCK);
    try {
        const stat = fs.fstatSync(fd);
        if (!stat.isFile() || (bytes !== undefined && stat.size !== bytes)
                || (mode !== undefined && (stat.mode & 0o7777) !== mode)) {
            throw Error('File type, size or mode differs from the expected value.');
        }
        return fs.readFileSync(fd);
    } finally {
        fs.closeSync(fd);
    }
}

function verifyBundle(root, expectedDigest) {
    assertDirectory(root, true);
    const encoded = readRegular(path.join(root, 'manifest.json'));
    if (sha256(encoded) !== expectedDigest) {
        throw Error('Manifest SHA-256 differs from the supplied digest.');
    }
    const manifest = JSON.parse(encoded);
    if (manifest.schema !== 'taku.tesla-control.bundle/1' || manifest.noDeviceCalls !== true
            || manifest.noVehicleCommands !== true || !Array.isArray(manifest.files)
            || manifest.files.length !== packageFiles.length) {
        throw Error('Invalid bundle manifest.');
    }
    const entries = new Map(manifest.files.map((entry) => [entry.path, entry]));
    if (entries.size !== packageFiles.length || packageFiles.some((file) => !entries.has(file))) {
        throw Error('Manifest file paths differ from the Tesla Control package.');
    }

    const expectedFiles = new Set(['manifest.json', ...packageFiles]);
    function verifyTree(dir, relative = '') {
        for (const entry of fs.readdirSync(dir, {withFileTypes: true})) {
            const name = relative + entry.name;
            if (entry.isDirectory() && packageDirs.has(name)) {
                verifyTree(path.join(dir, entry.name), name + '/');
            } else if (!entry.isFile() || !expectedFiles.has(name)) {
                throw Error('Bundle contains an unexpected path or symbolic link.');
            }
        }
    }
    verifyTree(root);
    const verified = new Map();
    for (const file of packageFiles) {
        const entry = entries.get(file);
        if (!Number.isSafeInteger(entry.bytes) || entry.bytes < 0 || !/^[0-9a-f]{64}$/.test(entry.sha256)) {
            throw Error('Invalid file metadata for ' + file);
        }
        const body = readRegular(path.join(root, file), {bytes: entry.bytes});
        if (body.length !== entry.bytes || sha256(body) !== entry.sha256) {
            throw Error('File size or SHA-256 differs from the manifest: ' + file);
        }
        verified.set(file, body);
    }
    return verified;
}

function readBinding(file, label, mode) {
    try {
        const body = readRegular(file, {mode});
        const {schema, vin, appWidgetId, widgetHostPackage = ''} = JSON.parse(body);
        if (schema !== 'tesla-case-vehicle-binding/1' || typeof vin !== 'string'
                || !/^[A-HJ-NPR-Z0-9]{17}$/.test(vin)
                || (appWidgetId !== null && (!Number.isSafeInteger(appWidgetId) || appWidgetId < 0))
                || typeof widgetHostPackage !== 'string'
                || (widgetHostPackage !== ''
                    && !/^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$/.test(widgetHostPackage))) {
            throw Error('Invalid binding fields.');
        }
        return {body, vin, appWidgetId, widgetHostPackage};
    } catch {
        throw Error(label + ' must be a regular, valid vehicle binding' + (mode ? ' with mode 0600.' : '.'));
    }
}

function stageFiles(verified, newBinding) {
    fs.mkdirSync(path.join(pending, 'scripts'), {mode: 0o700});
    for (const file of runtimeFiles) {
        fs.writeFileSync(path.join(pending, file + '.pending'), verified.get(runtimePath + file), {
            flag: 'wx', mode: file.endsWith('.sh') ? 0o700 : 0o600,
        });
    }
    if (newBinding) {
        fs.writeFileSync(path.join(pending, 'vehicle-binding.json.pending'), newBinding.body, {
            flag: 'wx', mode: 0o600,
        });
    }
}

function bindingForInstall(supplied, scripts) {
    for (const dir of [skill, scripts, path.join(skill, 'state')]) {
        assertDirectory(dir);
    }
    const targets = [...runtimeFiles.map((file) => path.join(skill, file)), binding];
    for (const target of [scripts, ...targets]) {
        if (lstat(target + '.pending')) {
            throw Error('Resolve the existing pending install before rerunning this installer.');
        }
    }
    for (const target of targets) {
        const stat = lstat(target);
        if (stat && !stat.isFile()) {
            throw Error('An installed file is a symbolic link or is not a regular file.');
        }
    }

    const existing = lstat(binding) ? readBinding(binding, 'The installed binding') : null;
    if (!existing && !supplied) {
        throw Error('First install requires --binding pointing to a private mode-0600 binding outside the bundle.');
    }
    if (existing && supplied && (existing.vin !== supplied.vin || existing.appWidgetId !== supplied.appWidgetId
            || existing.widgetHostPackage !== supplied.widgetHostPackage)) {
        throw Error('The supplied binding changes the installed VIN or widget identity.');
    }
    return existing ? null : supplied;
}

function install(root, expectedDigest, bindingSource) {
    if (within(root, skill) || within(skill, root)) {
        throw Error('Keep the bundle outside the installed Skill directory.');
    }
    const verified = verifyBundle(root, expectedDigest);
    let supplied = null;
    if (bindingSource !== undefined) {
        if (!path.isAbsolute(bindingSource) || path.resolve(bindingSource) !== bindingSource
                || within(root, bindingSource)) {
            throw Error('Use an absolute, private binding path outside the bundle.');
        }
        supplied = readBinding(bindingSource, 'The supplied binding', 0o600);
    }

    assertDirectory(home, true);
    assertDirectory(codex);
    assertDirectory(skills);
    fs.mkdirSync(codex, {recursive: true, mode: 0o700});
    fs.mkdirSync(skills, {recursive: true, mode: 0o700});
    if (lstat(pending)) {
        throw Error('Resolve the existing pending install before rerunning this installer.');
    }
    // Keep the lock and staged files on the same filesystem as the installed Skill.
    fs.mkdirSync(pending, {mode: 0o700});
    const scripts = path.join(skill, 'scripts');
    let newBinding;
    try {
        newBinding = bindingForInstall(supplied, scripts);
    } catch (error) {
        fs.rmdirSync(pending);
        throw error;
    }
    stageFiles(verified, newBinding);
    fs.mkdirSync(scripts, {recursive: true, mode: 0o700});
    if (newBinding) {
        const stagedBinding = path.join(pending, 'vehicle-binding.json.pending');
        fs.linkSync(stagedBinding, binding);
        fs.unlinkSync(stagedBinding);
    }
    for (const file of runtimeFiles) {
        fs.renameSync(path.join(pending, file + '.pending'), path.join(skill, file));
    }
    fs.rmdirSync(path.join(pending, 'scripts'));
    fs.rmdirSync(pending);
    console.log('Installed Tesla Control Skill.');
}

try {
    const [installer, hashFlag, digest, bindingFlag, bindingSource, ...extra] = process.argv.slice(2);
    if (hashFlag !== '--expected-manifest-sha256' || !/^[0-9a-fA-F]{64}$/.test(digest || '')
            || (bindingFlag !== undefined && (bindingFlag !== '--binding' || !bindingSource))
            || extra.length) {
        throw Error('Usage: install.sh --expected-manifest-sha256 HEX [--binding /outside/private/file.json]');
    }
    process.umask(0o077);
    const script = path.resolve(installer);
    if (!lstat(script)?.isFile() || fs.realpathSync(script) !== script) {
        throw Error('Run the verified bundle installer directly, using a real path.');
    }
    install(path.dirname(script), digest.toLowerCase(), bindingSource);
} catch (error) {
    console.error('Install failed: ' + error.message);
    process.exitCode = 1;
}
NODE
