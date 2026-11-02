# smapass-mobile

## Git 関連命名規則

### コミットメッセージ

```
<type>: <変更の概要>
```

| type | 用途 |
|---|---|---|
| `feat` | 新機能の追加 | 
| `fix` | バグ修正 | 
| `docs` | ドキュメントのみ | 
| `style` | 動作に影響しない整形 |
| `refactor` | 動作を変えないコード整理 | 
| `perf` | パフォーマンス改善 | 
| `test` | テストの追加・修正 | 
| `chore` | 設定・ビルド・依存関係 | 

### ブランチ名

```
<type>/<Issue番号>-<概要>
```
(Typeはコミットメッセージを参照)
| 例 | 内容 |
|---|---|
| `feat/42-login-page` | 新機能 |
| `fix/57-empty-form-crash` | バグ修正 |
| `docs/63-setup-guide` | ドキュメント |
