# 私有开发包发布步骤

此流程需要 `Taku-OS/taku-os` 的 Write 权限，保持私有和 prerelease，不自动 merge PR。无写权限时可先交付 ZIP 和 Git bundle，由有权限的同事导入分支后提交 PR。

1. 确认 PR 的源码、离线回归和本机安装验收。`go2-devkit` GitHub Actions 会上传 `taku-go2-devkit-preview`，评审者可直接从运行页面下载，保留 30 天。
2. 固定要发布的 commit（可先发布 PR 分支的内部预览，无须强行合并主分支）。在干净 checkout 上运行 `python3 tooling/go2-lab/package-devkit.py`，输出 ZIP、APK、SHA256SUMS。
3. 审查本次提交／发布文件，确认不存在个人 Key、账号、.env、录音或日志；版本取 `tooling/go2-lab/distribution/manifest.json`。
4. 有权限的成员从仓库根目录运行以下命令。`--target` 必须是包内 manifest.sourceCommit 对应、已推送的提交；不要用不断变化的 main 或上传别的版本的 APK。

```sh
gh release create go2-lab-v0.1.0-preview.1 \
  out/go2-releases/taku-go2-devkit-0.1.0-preview.1.zip \
  out/go2-releases/TakuGo2-0.1.0-preview.1.apk \
  out/go2-releases/SHA256SUMS \
  --repo Taku-OS/taku-os \
  --target VERIFIED_SOURCE_COMMIT \
  --title 'Taku Go2 开发包 0.1.0-preview.1' \
  --notes-file tooling/go2-lab/distribution/RELEASE-NOTES.md \
  --prerelease
```

5. 用 `gh release view` 确认版本、target 和资产名称；下载发布后的 ZIP 到新目录，执行 `shasum -a 256 -c SHA256SUMS` 和 `package-devkit.py --verify`。只安装应用，不在发布后验证中自动操作真实狗。

## 无分支写权限时转交

交付的 `taku-go2-review.bundle` 包含这个 PR 分支以及未在基础主分支出现的提交，不含任何其他开发分支历史。由同事在已克隆的仓库中执行：

```sh
git fetch /path/to/taku-go2-review.bundle codex/go2-developer-bundle:codex/go2-developer-bundle
git switch codex/go2-developer-bundle
git push -u origin codex/go2-developer-bundle
gh pr create --repo Taku-OS/taku-os --base main --head codex/go2-developer-bundle \
  --title 'feat(go2): deliver complete robot app developer kit' \
  --body-file tooling/go2-lab/distribution/PR.md
```

需要基础提交存在于 clone 中；先 `git fetch origin main`。PR 创建后再选择是否内部发布，不把导入 bundle 当作评审通过。

## 撤回

发现打包错误时，将对应 release 设回 draft 或停止分发该版本，保留证据并发布更高 preview 版本修复；不覆写已发布 ZIP 或 tag。停止模拟任务、退出当前 Mac 服务，再切换整套旧版本。首次版本无更早可用 release 时，保留现有开发分支环境，不卸载同事的其他应用。
