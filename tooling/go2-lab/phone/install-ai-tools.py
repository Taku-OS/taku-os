#!/usr/bin/env python3
"""Install Go2 CLI/skill on an explicitly selected Taku userdebug phone.

Requires already enabled adb root; does not change bootloader or root settings.
"""
import argparse
from pathlib import Path
import shlex
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[3]
parser = argparse.ArgumentParser()
parser.add_argument('--adb', required=True)
parser.add_argument('--serial', required=True)
args = parser.parse_args()
adb = [args.adb, '-s', args.serial]


def shell(*command):
    return subprocess.check_output(adb + ['shell', shlex.join(command)]).decode().strip()


if shell('id', '-u') != '0' or shell('getprop', 'ro.build.type') != 'userdebug' or shell('getenforce') != 'Enforcing':
    raise SystemExit('An explicitly authorized Taku userdebug phone with adb root and SELinux Enforcing is required')
remote = '/data/local/agentos/go2-lab'
skill = '/data/local/agentos/home/.codex/skills/taku-go2'
if shell('sh', '-c', f'test -e {remote} -o -e {skill} && echo exists || true'):
    raise SystemExit('Existing Go2 tools found: back them up and review the update before replacing them')
source = (ROOT / 'tooling/go2-lab/skill/taku-go2/SKILL.md').read_text()
source = source.replace('访问 Mac 本地服务', '访问这台 Pixel 的本机服务（不依赖 Mac、USB 或 ADB 转发）')
source = source.replace('不能用 Mac 或模拟器定位', '不能用手机或模拟器定位')
source = source.replace('## 连接与停止', '''## 手机运行环境

服务未启动时，打开「机器狗 → 手机直连 · 连接设置」。让用户选择离线模拟或真实 Go2。真实连接需要手机和狗在同一热点或 Wi-Fi，以及用户确认的当前局域网 IP；点击「启动 / 连接」后会等待新的电量和姿态数据，不会自动运动。离线模拟需再点状态页「连接模拟器」。根据当前状态决定是否需要连接；不得把历史 IP 当成当前地址，也不得自行把真实任务切换为模拟。Termux 是手机本机运行环境；请勿结束它的服务通知。返回后台会请求停止运动。

如果出现 AesKeyRequiredError，让用户在连接设置填写本人宇树账号所绑定机器狗的设备专属 Key；AesKeyRejectedError 表示密钥不匹配。密钥仅保存在 Termux 私有目录，禁止输出到聊天、日志或命令参数。LocalSignalingPortError 需核对 IP、热点客户端互通和 VPN 局域网权限；RobotBusyError 需先断开其他控制端。任何失败都不能当成已连接；以 inspect 返回 connected=true 且实时数据 fresh=true 为准。

手机迁移版的模拟自主导航尚未通过完整验收，会因 POSE_STALE、TIME_SKEW 或 CAMERA_STALE 保护停止。不得宣称它已可稳定自主执行；如用户要求试验，说明限制，失败后停止并报告，不循环重试或放宽阈值。

## 连接与停止''')
with tempfile.TemporaryDirectory() as tmp:
    file = Path(tmp) / 'SKILL.md'; file.write_text(source)
    shell('mkdir', '-p', remote, skill)
    for local, destination in [(ROOT / 'out/go2-lab/client.jar', remote + '/client.jar'),
                               (ROOT / 'tooling/go2-lab/go2', remote + '/go2'), (file, skill + '/SKILL.md')]:
        subprocess.run(adb + ['push', str(local), destination], check=True)
    shell('chmod', '755', remote + '/go2')
    shell('chmod', '644', remote + '/client.jar', skill + '/SKILL.md')
    shell('restorecon', '-R', remote, skill)
print('Installed local Go2 CLI and skill. Verify skills/list in the selected Taku runtime separately.')
