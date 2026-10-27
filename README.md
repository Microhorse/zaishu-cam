# 📷 材集カメラ (ZaishuCamera)

フィールドワーク・資材収集（材集）用の**超高速メモ付きカメラ Androidアプリ**です。
「撮影 → ゼロタップでメモ入力 → 下部白余白へ日時・テキスト合成保存 → 即座に次の撮影へ復帰」を単一画面（Single Screen）で完結させます。

---

## ✨ 主な特徴
1. **起動0秒でカメラプレビュー**（スプラッシュ画面・メニュー画面なし）
2. **ゼロタップ文字入力**：シャッターを切った瞬間にメモ欄へ自動フォーカス＆ソフトキーボードが自動起動
3. **待ち時間ゼロの連続撮影**：「保存して次へ」を押した瞬間にカメラ画面へ即復帰し、裏側（非同期）で画像合成＆保存を実行
4. **下部のみ白余白を動的追加**：
   - 元画像の左右・上部には余白を作らず、真下にのみ白余白（最小22%〜長文時は自動拡張）を追加
   - 1行目に撮影日時（`yyyy/MM/dd HH:mm`）、その下にメモ本文を折り返し描画
   - 端末の標準ギャラリー内 **`Pictures/ZaishuMemo`** フォルダに高品質JPEG（95%）として保存

---

## 🚀 APKのビルド＆スマホへのインストール手順（PCへのAndroid Studio導入不要）

このフォルダには **GitHub Actions 自動ビルド設定（`.github/workflows/build-apk.yml`）** が組み込まれています。GitHubへプッシュするだけで、クラウド上で自動的に `.apk` ファイルが生成されます。

### 手順1：GitHubで新規リポジトリを作成してプッシュする
1. [GitHubの新規リポジトリ作成ページ (https://github.com/new)](https://github.com/new) を開きます。
2. リポジトリ名に `zaishu-camera` （任意）と入力し、**Create repository** をクリックします（Private / Public どちらでも可）。
3. ターミナルでこのフォルダに移動し、以下のコマンドを実行してプッシュします（すでに初期コミット済みです）：

```bash
cd "/Users/solso47/Library/CloudStorage/GoogleDrive-shinozaki.y.1015@gmail.com/マイドライブ/0_obsidian/篠崎g-drive_obsidian/00_Master/02_業務補助ツール/04_材集カメラapp"
git remote add origin https://github.com/<あなたのGitHubユーザー名>/zaishu-camera.git
git push -u origin main
```

### 手順2：ビルドされた `ZaishuCamera.apk` をAndroidスマホに入れる
1. プッシュ後、GitHubリポジトリの **Actions** タブを開くと、自動的に `Build ZaishuCamera APK` が実行されます（約2〜3分で完了します）。
2. ビルドが完了すると、リポジトリ右側の **Releases**（`材集カメラ 最新ビルド (ZaishuCamera.apk)`）に **`ZaishuCamera.apk`** が自動公開されます。
   - または **Actions** タブの実行結果画面下部 **Artifacts** から `ZaishuCamera-apk` をダウンロードすることも可能です。
3. Androidスマホで **`ZaishuCamera.apk`** をダウンロードして開くと、「材集カメラ」がインストールされます。
   - ※初回インストール時に「不明なアプリのインストール」の許可を求められた場合は「許可」をオンにしてください。
