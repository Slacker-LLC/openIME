#!/usr/bin/env bash
# Apply the GitHub repository settings described in docs/REPOSITORY.md, so they
# are reviewable in git and can be re-applied if someone changes them by hand.
# Idempotent. Needs admin rights on the repository and an authenticated `gh`.
#
#   bash scripts/apply_repo_settings.sh [--repo OWNER/NAME] [--dry-run]
#
# --dry-run prints every request body without sending anything.
set -euo pipefail

REPO=""
DRY=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --repo) REPO="$2"; shift 2 ;;
    --dry-run) DRY=1; shift ;;
    -h|--help) sed -n '2,9p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

command -v gh >/dev/null || { echo "gh (GitHub CLI) not found" >&2; exit 1; }
[[ -n "$REPO" ]] || REPO="$(gh repo view --json nameWithOwner --jq .nameWithOwner)"
echo "Repository: $REPO$([[ "$DRY" == "1" ]] && echo '  (dry run)')"

send() { # send METHOD PATH [JSON]   (PATH is relative to repos/OWNER/NAME; empty for the repo itself)
  local method="$1" path="$2" body="${3:-}"
  local url="repos/$REPO${path:+/$path}"
  echo "-> $method $url"
  if [[ "$DRY" == "1" ]]; then
    [[ -z "$body" ]] || printf '%s\n' "$body"
    return 0
  fi
  if [[ -n "$body" ]]; then
    printf '%s' "$body" | gh api -X "$method" "$url" --input - >/dev/null
  else
    gh api -X "$method" "$url" >/dev/null
  fi
}

# 1. Merge policy: squash only, PR title and body become the commit message
#    (the default concatenates every commit message), branches clean up after merge.
send PATCH "" '{
  "description": "本地优先的 Android 中文拼音输入法：librime 拼音 + 内置离线语音识别，不声明 INTERNET 权限",
  "has_wiki": false,
  "allow_squash_merge": true,
  "allow_merge_commit": false,
  "allow_rebase_merge": false,
  "allow_auto_merge": false,
  "allow_update_branch": true,
  "delete_branch_on_merge": true,
  "squash_merge_commit_title": "PR_TITLE",
  "squash_merge_commit_message": "PR_BODY",
  "security_and_analysis": {
    "secret_scanning": {"status": "enabled"},
    "secret_scanning_push_protection": {"status": "enabled"}
  }
}'

send PUT "topics" '{"names": ["android", "ime", "input-method", "pinyin", "rime", "librime", "chinese", "offline", "speech-recognition", "kotlin"]}'

# 2. main: changes arrive through a pull request whose "Build and verify" check
#    passed; no force pushes, no deletion, linear history. Admins are not
#    locked out (enforce_admins=false) so the owner can still fix a broken main.
#    The API-29/31 compatibility jobs report on every PR but do not block: an
#    emulator hiccup should not stop a merge. Add them to "contexts" to enforce.
send PUT "branches/main/protection" '{
  "required_status_checks": {"strict": false, "contexts": ["Build and verify"]},
  "enforce_admins": false,
  "required_pull_request_reviews": {
    "required_approving_review_count": 0,
    "dismiss_stale_reviews": true,
    "require_code_owner_reviews": false
  },
  "restrictions": null,
  "required_linear_history": true,
  "allow_force_pushes": false,
  "allow_deletions": false,
  "required_conversation_resolution": true,
  "allow_fork_syncing": true
}'

# 3. Release tags are created by an admin only and cannot be moved or deleted
#    by anyone else. (Repository admins can bypass, so a mistaken tag can still be fixed.)
RULESET='{
  "name": "release-tags",
  "target": "tag",
  "enforcement": "active",
  "conditions": {"ref_name": {"include": ["refs/tags/v*"], "exclude": []}},
  "rules": [{"type": "creation"}, {"type": "update"}, {"type": "deletion"}, {"type": "non_fast_forward"}],
  "bypass_actors": [{"actor_id": 5, "actor_type": "RepositoryRole", "bypass_mode": "always"}]
}'
RULESET_ID="$(gh api "repos/$REPO/rulesets" --jq '.[] | select(.name == "release-tags") | .id' 2>/dev/null | head -n 1 || true)"
if [[ -n "$RULESET_ID" ]]; then
  send PUT "rulesets/$RULESET_ID" "$RULESET"
else
  send POST "rulesets" "$RULESET"
fi

# 4. Security features that cost nothing on a public repository.
send PUT "vulnerability-alerts"
send PUT "automated-security-fixes"
send PUT "private-vulnerability-reporting"
send PUT "actions/permissions/workflow" '{"default_workflow_permissions": "read", "can_approve_pull_request_reviews": false}'

# 5. Labels the Dependabot configuration refers to.
if [[ "$DRY" == "1" ]]; then
  echo "-> gh label create dependencies / ci (--force)"
else
  gh label create dependencies --repo "$REPO" --color 0366d6 --description "Dependency updates" --force >/dev/null
  gh label create ci --repo "$REPO" --color ededed --description "CI and release pipeline" --force >/dev/null
fi

echo "Done."
