# 发布与版本管理

仓库里的所有版本信息只有一个来源：根目录 `VERSION`。发布是一次可重复的流水线：
发布 PR → 合并到 `main` → 在合并提交上打标签 → 工作流构建、校验、发布。
仓库规则（分支保护、标签保护、合并方式）见 [REPOSITORY.md](REPOSITORY.md)。

## 版本号

- 语义化版本 `MAJOR.MINOR.PATCH`，只发布 `X.Y.Z` 稳定版，不使用 `-rc`、`-beta` 等后缀。
- `VERSION` 是一行文本（例如 `1.0.0`）。`app/build.gradle.kts` 读取它：
  `versionName = VERSION`，`versionCode = MAJOR × 10000 + MINOR × 100 + PATCH`
  （`1.0.0` → `10000`，`1.2.3` → `10203`）。要求 `MAJOR ≥ 1`，`MINOR`、`PATCH` 不超过 99。
  这样 `versionCode` 不会被忘记升级，也永远严格递增。
- `scripts/release_check.py` 用同一公式检查仓库，并用 `aapt2` 校验构建出的 APK 里的
  包名、`versionName`、`versionCode`；PR 的 CI 和发布工作流都会运行它。

什么时候升哪一位：

| 升级 | 条件 |
|---|---|
| MAJOR | 用户数据格式不兼容或需要用户手动迁移；`minSdk` 提高；包名或签名变化 |
| MINOR | 新功能、新面板或键盘；词库、语音模型、第三方 runtime 的版本变化（需重新核对许可证） |
| PATCH | 缺陷修复、性能、文案、依赖的安全更新 |

Rime 共享数据以 `versionCode` 作为部署标记：每次升级后首次启动都会重新部署共享词典
（用户词库在独立目录，不受影响）。所以 PATCH 版本也会触发一次重新部署。

只维护最新的一条 MINOR 版本线；安全修复以 PATCH 版本发布。

## CHANGELOG

[CHANGELOG.md](../CHANGELOG.md) 遵循 Keep a Changelog：

- 日常 PR 把用户可见的改动写进 `## [Unreleased]`，**不改 `VERSION`**。
- 最新的 `## [X.Y.Z] - YYYY-MM-DD` 小节必须正好等于 `VERSION`，写法上不允许空小节、
  版本或日期倒序。`release_check.py check` 在 CI 里强制这些规则，所以版本号与
  变更记录只能一起变化。
- 这一小节的正文就是 GitHub Release 的发布说明，请按用户能读懂的方式写。
- 已撤回的版本在标题后加 ` [YANKED]`，并发布更高的 PATCH 版本。

## 发布产物

只生成 `arm64-v8a` 的正式 APK：

```text
openIME-v{VERSION}-arm64-release.apk
```

同一个 GitHub Release 还有 `SHA256SUMS.txt` 和 `THIRD_PARTY_NOTICES.release.md`。
Debug 构建保留 `arm64-v8a + x86_64`，只用于真机和模拟器回归，不发布。

## 签名密钥

密钥是应用的永久身份：Android 只在新 APK 与已安装版本由同一把密钥签名时才允许覆盖安装，
密钥丢了就只能让所有用户卸载重装。keystore 不进入仓库、Issue、PR、Actions artifact 或 Release，
也不要从 Debug keystore 发布。

一次性初始化（在你信任的机器上运行，不要让别人代跑）：

```bash
bash scripts/setup_release_signing.sh
```

脚本会：生成 4096 位 RSA keystore（默认放在 `~/.openime-release/`）、随机口令、
并把 `release.yml` 读取的四个 Actions secrets 写进仓库：

- `OPENIME_KEYSTORE_B64`：keystore 的 Base64。
- `OPENIME_KEYSTORE_PASSWORD`、`OPENIME_KEY_ALIAS`、`OPENIME_KEY_PASSWORD`。

口令不会打印。运行后**立刻备份** `~/.openime-release/`：离线加密副本加密码管理器，
`credentials.txt` 里是明文口令，放进密码管理器后不要留在普通云盘。

首次发布后，把证书 SHA-256 写入 [`release-cert.sha256`](release-cert.sha256)（发布说明里就有）。
之后每次发布都会比对它：签名证书对不上就直接失败，避免换了密钥却没发现。

本地也可以用自己的 keystore 构建 release：设置同名环境变量（`OPENIME_KEYSTORE_PATH` 指向文件，
不用 Base64），运行 `scripts/release_build.sh`。

## 发布步骤

1. `main` 上最近一次 CI 全绿（包括 API 29 / 31 兼容测试）。
2. 发布 PR：把 `[Unreleased]` 整理成 `## [X.Y.Z] - YYYY-MM-DD`，同时修改 `VERSION`。
   本地先运行：

   ```bash
   python3 scripts/release_check.py check
   ```

   这个 PR 改到了发布相关文件，CI 会自动用一次性密钥完整演练一遍发布流水线（见下）。
3. 合并后，在 `main` 的合并提交上打带注释的标签并推送：

   ```bash
   git switch main && git pull
   git tag -a vX.Y.Z -m "openIME X.Y.Z"
   git push origin vX.Y.Z
   ```

   标签只有管理员能创建，创建后不能被移动或删除（见 REPOSITORY.md）。
4. `.github/workflows/release.yml` 自动执行：
   - 标签格式、`VERSION`、`CHANGELOG.md` 三者一致；标签在 `main` 上，且该提交的 CI 已通过；
   - 单元测试、`lintRelease`、`assembleRelease`；
   - APK 签名校验（不能是 Debug 证书）、只含 `arm64-v8a`、APK 内版本与 `VERSION` 一致、
     签名证书与 `release-cert.sha256` 一致；
   - 生成 SHA-256 和发布说明；
   - 另一个只有写权限、不接触密钥的 job 先建**草稿** Release，确认三个附件齐全后才公开。
5. 发布后核对：下载 APK，`sha256sum -c SHA256SUMS.txt`，`apksigner verify --print-certs`，
   在真机上安装、启用、试打。

### 演练

`Android Release` 工作流在两种情况下用一把只存在于该次运行的一次性密钥完整执行同一套构建和校验，
但不发布任何东西：手动触发（Actions → Android Release → Run workflow），以及 PR 改动了
`release.yml`、`release_build.sh`、`release_check.py`、`VERSION`、`CHANGELOG.md` 或 `app/build.gradle.kts`。
所以发布流水线在真正发布之前就已经跑过。本地同样可以演练：

```bash
OPENIME_REHEARSAL=1 OPENIME_SKIP_TESTS=1 scripts/release_build.sh   # 需要上面的四个环境变量
```

### 失败与回滚

- 发布工作流在发布前失败：修复后通过 PR 合并，管理员删除远端标签再重新打在新的提交上
  （`git push origin :refs/tags/vX.Y.Z`）；如果留下了草稿 Release，先把它删掉。
- 已公开的版本发现问题：Android 不允许降级，不要删除或改写标签。在 CHANGELOG 里给该版本标
  `[YANKED]`，把 Release 改成 pre-release 并写明原因，然后发布更高的 PATCH 版本。
- 密钥泄露：立即停止发布，在 Settings 里删除 secrets，更换密钥会让所有现有用户必须卸载重装，
  需要先在发布说明里写清楚迁移步骤（导出用户数据 → 卸载 → 安装 → 导入）。

## 发布前检查

- `AndroidManifest.xml` 不含 `INTERNET` 权限。
- `android:allowBackup="false"` 保持不变。
- `THIRD_PARTY_NOTICES.md` 与 `app/src/main/assets/licenses/` 同步。
- 语音模型、词库或第三方 runtime 版本变化时重新核对对应许可证（见 [LICENSING.md](LICENSING.md)）。
- 主项目许可证：`LICENSE`（GPL-3.0-only），说明见 LICENSING.md。

## 社交预览

仓库内提供 `docs/images/social-preview.png`（1280×640），由 `scripts/generate_brand_assets.py` 生成。
GitHub 的 Social preview 不是源码文件配置项，需要仓库管理员在 **Settings → General → Social preview**
上传该 PNG；这一步不能通过提交代码完成。
