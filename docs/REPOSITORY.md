# 仓库管理

这里记录 GitHub 仓库的治理规则。它们由 [`scripts/apply_repo_settings.sh`](../scripts/apply_repo_settings.sh)
应用，所以改规则就是改这个脚本和本文档，再由管理员重新运行；不要只在网页上点选。

## 分支模型

- 主干开发：`main` 始终可发布。从最新的 `main` 开短生命周期分支，命名 `类型/主题`，
  类型取 `feat`、`fix`、`docs`、`chore`、`ci`、`refactor`、`test`，例如 `fix/pinyin-candidate`。
- 需要维护旧版本线时才从旧标签建 `release/X.Y` 分支；日常热修复直接在 `main` 上发布 PATCH。
- 合并后分支自动删除。超过 30 天没有提交也没有 PR 的分支，维护者每月清理一次。

## 合并

- 只允许 **squash 合并**；不允许 merge commit 和 rebase merge，历史保持线性。
- squash 提交信息取 **PR 标题 + PR 描述**，不再拼接分支里的每个提交说明。所以 PR 标题要独立说清结果
  （推荐 Conventional Commits 前缀：`feat:`、`fix:`、`docs:`、`ci:`…），描述写目的、影响、验证和已知限制。
- 提交说明、PR 和讨论可以用中文或英文，同一个 PR 内保持一致。

## `main` 的保护规则

- 必须通过 PR 合并；必须通过检查 **Build and verify**（单元测试、Lint、构建、版本与变更记录检查）。
- 禁止 force push、禁止删除、要求线性历史、要求解决所有评审对话。
- 不强制管理员遵守（`enforce_admins=false`）：所有者在 `main` 出问题时仍能直接修复。
  这是应急通道，不是日常做法。
- 不要求他人批准（单人维护）；有第二位维护者后，把 `required_approving_review_count` 调到 1，
  并在 `.github/CODEOWNERS` 里加人。
- API 29 / 31 兼容测试在每个 PR 上运行并真实失败（测试不通过就红），但不阻止合并，
  避免模拟器偶发问题卡住发布。想强制时把 `Compatibility API 29`、`Compatibility API 31` 加进
  `apply_repo_settings.sh` 的 `contexts`。发布工作流本身要求这三项检查都通过。
- 如果重命名了 `android.yml` 里的 job，同步修改 `contexts`，否则 PR 会一直等待一个不存在的检查。

## 标签

规则集 `release-tags` 作用于 `v*`：只有仓库管理员能创建，创建后任何人（管理员除外）都不能移动或删除。
发布标签格式固定为 `vX.Y.Z` 或 `vX.Y.Z-beta.N`，必须与 `VERSION` 一致，详见 [RELEASE.md](RELEASE.md)。

## 安全

- Secret scanning 与 push protection 开启；Dependabot 告警与安全更新开启。
- 私密漏洞报告开启（Security → Report a vulnerability），说明见 [SECURITY.md](../SECURITY.md)。
- Actions 默认令牌只读（`default_workflow_permissions=read`），Actions 不能批准 PR。
  需要写权限的 job 在工作流里单独声明；签名密钥只在 `release.yml` 的构建 job 里读取，
  该 job 没有写权限，发布 job 有写权限但接触不到密钥。
- 签名 secrets 的建立和备份见 RELEASE.md。keystore 不进入仓库，`.gitignore` 也会拦截 `*.jks`、`*.keystore`、`*.p12`。

## 依赖

`.github/dependabot.yml` 每周一检查 GitHub Actions 和 Gradle 依赖，次要和补丁更新合并成一个 PR；
Gradle 的主版本（AGP、Kotlin 等）与 NDK、SDK 的固定版本绑定，由人工升级。
固定提交的资源不会被自动更新：Rime Ice 词典（见 `THIRD_PARTY_NOTICES.md`）、librime 依赖
（`scripts/fetch_rime_deps.sh`）、sherpa-onnx AAR 和语音模型（Git LFS）。

## 大文件与仓库卫生

- 大二进制（`*.onnx`、`*.aar`）走 Git LFS；不要提交 APK（`.gitignore` 已拦截）、截图、UI dump、设备日志。
- `output/` 是设计参考脚本的本地产物，已忽略。
- Debug APK 约 380 MB，超过 GitHub 单文件 100 MB 限制；需要测试包时从 PR 的 CI artifact
  `openIME-test-apks` 下载（保留 1 天）。

## 重新应用设置

```bash
bash scripts/apply_repo_settings.sh --dry-run   # 只打印请求
bash scripts/apply_repo_settings.sh             # 应用，需要仓库管理员权限和已登录的 gh
```

仓库没有 Wiki（文档都在 `docs/`），已关闭。Social preview 图片只能在网页上传，见 RELEASE.md。
