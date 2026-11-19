<h1 align="center">Taku OS</h1>

<p align="center"><img src="docs/media/taku-os-banner.gif" alt="Taku OS — どの時代にも、その時代の OS がある" width="100%"></p>

<p align="center"><strong>AI時代に向けた、オープンソースのスマートフォン OS。</strong></p>

<p align="center">AI はシステムの一部です。エージェントは対応アプリをまたいでタスクを実行し、新しいアプリを作成し、カメラやセンサーを使い、接続されたデバイスと連携できます。利用できる機能は、デバイスの対応状況と権限によって異なります。</p>

<p align="center"><a href="#はじめる">はじめる</a> · <a href="https://docs.taku.ai">ドキュメント</a> · <a href="https://github.com/Taku-OS/taku-os/releases">リリース</a> · <a href="https://taku.ai">公式サイト</a> · <a href="https://discord.gg/NYK47Ngucm">Discord</a> · <a href="LICENSE">ライセンス</a> · <a href="https://github.com/Taku-OS/taku-os/stargazers">Stars</a></p>

<p align="center"><a href="README.md">English</a> | <a href="README.zh-CN.md">简体中文</a> | 日本語</p>

## Research Preview

Taku OS は現在、開発の初期段階にあり、テストと開発を目的としています。業務での利用は推奨しません。

## Taku OS とは？

Taku OS は Android をベースに構築されており、一般的な Android アプリに対応しています。エージェントには、ご自身の API キー（BYOK）を使うか、ChatGPT のサブスクリプションでログインしてモデルを利用できます。

多くのスマートフォンアシスタントは、ひとつのアプリの中で質問を待っています。Taku OS では、エージェントがスマートフォン上で動作し、システムの機能を直接利用できます。そのため、対応アプリ、カメラ、システム設定をまたいでタスクを進められます。システムのコードを確認し、仕組みを理解し、自分の用途に合わせて変更することもできます。

このリポジトリには、Taku OS のシステム ROM とドキュメントが含まれます。サーバー側のサービスは含まれません。Taku for Mac と Marketplace は別製品であり、オープンソースではありません。

## 自分の使い方に合わせて、自由に形づくる

欲しいものを伝えて、使えるものを残す。

- **あなたのためのホーム画面。** 「ハーフマラソンの練習を管理して」と伝えると、エージェントが計画、入力欄、ボタンを備えたホーム画面を作ります。会話しながら調整して保存し、あとからいつでも使えます。
- **欲しいアプリがない？ 自分で作ろう。** 必要なものを説明すると、エージェントがコードを書き、コンパイルし、スマートフォンにインストールして起動します。
- **設定を探さずに変更。** メニューを探し回る必要はありません。対応しているシステム設定の変更をエージェントに依頼できます。

## 見る、聞く、感じ取る

Taku OS では、エージェントがスマートフォンのハードウェアを使い、周囲の世界とやり取りできます。

- **イメージを伝えて、カメラフィルターに。** 撮りたい雰囲気を伝えると、エージェントが撮影中にカメラフィルターを調整します。
- **会話をリアルタイムで翻訳。** たとえば東京では、相手の日本語を英語に翻訳してイヤホンで聞けます。英語で返答すると、日本語の訳がスマートフォンのスピーカーから流れます。
- **スマートフォンのハードウェアにアクセス。** カメラ、マイク、Wi-Fi、Bluetooth、モーションセンサーは、対応するインターフェースを通じてエージェントから利用できます。利用できる範囲は、デバイス、ドライバー、権限によって異なります。

## いつでも、どこでも、エージェントと

- **タップして話す。** 画面下部の音声入力をタップして、必要なことを伝えます。
- **サイドボタンを長押し。** 音声モードに入り、そのまま会話を続けられます。
- **Live を使う。** エージェントとリアルタイムで会話し、タスクを任せながらスマートフォンを使い続けられます。
- **あなたなら、何を作る？** AI がシステムレベルの機能を使えるようになったら、何ができるでしょうか。私たちもその可能性を探っています。あなたのアイデアを Taku OS で試し、スマートフォンにできることを一緒に広げてください。

## はじめる

環境を準備し、コーディングエージェントの案内に沿ってセットアップしたら、実際に使って開発を始めましょう。

### 試す方法を選ぶ

**スマートフォンで試す**

パソコン、Taku OS をインストールできるスマートフォン、データ転送に対応した USB ケーブルが必要です。現在のテスト端末である Pixel 9 を推奨します。他の Android 端末でも動作する可能性はありますが、互換性はまだ検証されていません。作業を始める前にデバイスの対応状況を確認し、スマートフォンをバックアップしてください。

[Pixel 9（`tokay`）向け Taku OS をダウンロード](https://github.com/Taku-OS/taku-os/releases/latest) · [Recovery パッケージ](https://github.com/Taku-OS/taku-os/releases/latest/download/TakuOS-tokay-recovery.zip) · [最新メタデータ JSON](https://github.com/Taku-OS/taku-os/releases/latest/download/latest.json)

**エミュレーターで試す**

対応するスマートフォンがなくても、エミュレーターで Taku OS を試せます。お使いのスマートフォンを変更する必要はありません。パソコンで Android ARM64 仮想デバイスを実行できることを確認し、エミュレーターイメージをダウンロードしてください。

[ARM64 エミュレーターイメージをダウンロード](https://github.com/Taku-OS/taku-os/releases/latest/download/TakuOS-emulator-arm64-images.tar.gz)

### コーディングエージェントの案内でセットアップする

準備ができたら、Claude Code、Codex、または他のコーディングエージェントに次の内容を貼り付けます。案内に沿って進め、操作や確認を求められた手順はご自身で行ってください。

```text
https://github.com/Taku-OS/taku-os/blob/main/docs/agent-setup.md を読んで、スマートフォンまたはエミュレーターに Taku OS をセットアップしてください。
```

### モデルに接続して使ってみる

Taku OS が起動したら、ログインしてモデルを選びます。ご自身の API キーを使うか、ChatGPT のサブスクリプションでログインしてください。

Root 化された端末やシステムが変更された端末では、一部のアプリが正常に動作しない場合があります。Google アプリと Play ストアの利用は任意です。

### 自分の使い方に合わせて変える

Taku OS を試したら、システムのコードを直接変更することも、最初のプラグインを作って独自の機能を追加することもできます。

## ドキュメント

| 内容 | リンク |
| --- | --- |
| ガイドと API リファレンス | [docs.taku.ai](https://docs.taku.ai) |
| デバイスごとの現在の状況 | [docs/current-status.md](docs/current-status.md) |
| プラグイン開発 | [docs/plugins/README.md](docs/plugins/README.md) |
| 設計思想 | [PHILOSOPHY.md](PHILOSOPHY.md) |

## アーキテクチャ

```text
Home / Feed、エージェントアプリ、ウィジェット、通知
                 ↓
タスクサービス（1 つのキュー、1 つのタスクライフサイクル）
                 ↓
スマートフォン上のエージェントランタイム → モデルゲートウェイ → オンラインのモデル API
                 ↓
Android のシステムツール、フレームワーク、アプリ、ハードウェア
```

すべての操作入口で、共通のタスクサービスを使用します。デバイス上のエージェントランタイムは、モデルゲートウェイを通じてモデルサービスに接続します。モデルの推論はオンラインで行うため、エージェントにはネットワーク接続が必要です。詳しくは [ARCHITECTURE.md](ARCHITECTURE.md) をご覧ください。

## 開発

システムや各コンポーネントのビルド方法は、[docs/getting-started.md](docs/getting-started.md) をご覧ください。プルリクエストを作成する前に、[CONTRIBUTING.md](CONTRIBUTING.md) をお読みください。

## コミュニティ

不具合は [Issues](https://github.com/Taku-OS/taku-os/issues) で報告し、質問は [Discussions](https://github.com/Taku-OS/taku-os/discussions) に投稿してください。[Discord](https://discord.gg/NYK47Ngucm) での交流も歓迎します。

サポートについては [SUPPORT.md](SUPPORT.md) をご覧ください。セキュリティ上の脆弱性を報告する場合は、[SECURITY.md](SECURITY.md) の手順に従ってください。すべての参加者に [行動規範](CODE_OF_CONDUCT.md) の遵守をお願いしています。

## ライセンス

Taku OS は [Apache-2.0](LICENSE) ライセンスで公開されています。サードパーティのコンポーネントには、それぞれのライセンスが適用されます。詳しくは [NOTICE](NOTICE) をご覧ください。
