# 发布流程

## 发布产物

正式发布只生成 `arm64-v8a` APK，文件名为：

```text
openIME-v{versionName}-arm64-release.apk
```

同一 GitHub Release 同时上传：

- `SHA256SUMS.txt`
- `THIRD_PARTY_NOTICES.release.md`

Debug 构建继续保留 `arm64-v8a + x86_64`，用于真机与模拟器回归。

## 固定发布签名

GitHub Actions 需要以下四个 repository secrets：

- `OPENIME_KEYSTORE_B64`：发布 keystore 的 Base64 内容。
- `OPENIME_KEYSTORE_PASSWORD`
- `OPENIME_KEY_ALIAS`
- `OPENIME_KEY_PASSWORD`

本地 release 构建也可使用同名环境变量；其中 `OPENIME_KEYSTORE_PATH` 指向本机 keystore 文件，不使用 Base64。

keystore 不进入仓库，不提交到 Issue、PR、Actions artifact 或 Release。

## 发布步骤

1. 更新 `app/build.gradle.kts` 的 `versionCode` 和 `versionName`。
2. 在目标提交上完成 JVM 单测、Lint、Debug 构建和设备回归。
3. 创建与 `versionName` 完全一致的标签，例如 `versionName = "1.0.3"` 对应 `v1.0.3`。
4. 推送标签。
5. `.github/workflows/release.yml` 自动执行单测、`lintRelease`、`assembleRelease`、APK 签名校验和 ABI 校验。
6. 工作流生成 SHA-256，并创建 GitHub Release。

标签与 `versionName` 不一致、签名 secrets 缺失、APK 未签名或包含非 `arm64-v8a` native ABI 时，发布任务会失败。

## 社交预览

仓库内提供 `docs/images/social-preview.png`（1280×640），由 `scripts/generate_brand_assets.py` 生成。GitHub 的仓库 Social preview 不是源码文件配置项，因此需要仓库管理员在 **Settings → General → Social preview** 上传该 PNG；这一步不能通过当前仓库代码提交自动完成。

## 发布前检查

- `AndroidManifest.xml` 不含 `INTERNET` 权限。
- `android:allowBackup="false"` 保持不变。
- `THIRD_PARTY_NOTICES.md` 与 `app/src/main/assets/licenses/` 同步。
- 语音模型、词库或第三方 runtime 版本变化时重新核对对应许可证。
- 不从 Debug keystore 发布。
