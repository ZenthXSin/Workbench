#!/usr/bin/env bash
# Run only in Workbench's managed Alpine rootfs (bash is installed by the rootfs bootstrap).
# Also used by the chroot smoke test.
set -o pipefail

LOG=/var/log/rustwright/setup.log
MARKER=/etc/rustwright/setup-alpine-v7
CODEX_VERSION=0.166.0
FILESYSTEM_VERSION=2.0.1
MIRROR=@@MIRROR@@
LEGACY_MIRROR=@@LEGACY_MIRROR@@

mkdir -p /etc/rustwright /var/log/rustwright || exit 1
# A second launcher must not truncate the active run's log or compete with apk.
exec 9>/etc/rustwright/setup.lock
if ! flock -n 9; then
    echo "环境初始化正在运行，请等待当前安装完成。"
    exit 75
fi
if [ -f "$LOG" ]; then mv -f "$LOG" "$LOG.previous" || exit 1; fi

(
set -eE
export GIT_TERMINAL_PROMPT=0
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
# pip / npm caches are pure waste in a phone-sized environment
export PIP_NO_CACHE_DIR=1
STAGE=开始
step() {
    STAGE=$1
    printf '\n== %s ==\n' "$STAGE"
    printf '%s\n' "$STAGE" > /tmp/rw-setup-step
}
trap 'code=$?; if [ "$code" -ne 0 ]; then printf "\n安装失败：%s（退出码 %s）\n日志：%s\n" "$STAGE" "$code" "$LOG"; fi' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
printf 'Workbench Alpine setup v7 · %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')"
ARCH=$(apk --print-arch)
RELEASE=$(cut -d. -f1,2 /etc/alpine-release)
printf '系统：Alpine Linux %s，架构：%s\n' "$(cat /etc/alpine-release)" "$ARCH"
rm -f "$MARKER"

step "软件源与基础软件"
# Indexes and packages stay signature-verified by apk whichever mirror serves them.
write_repositories() {
    printf '%s/v%s/main\n%s/v%s/community\n' "$1" "$RELEASE" "$1" "$RELEASE" > /etc/apk/repositories
    printf '尝试软件源：%s\n' "$1"
}
packages=(bash ca-certificates curl git python3 py3-pip nodejs npm ripgrep zstd zip unzip xz less nano)
installed=0
for repo in "$MIRROR/alpine" "$LEGACY_MIRROR/alpine" https://mirrors.tuna.tsinghua.edu.cn/alpine https://mirrors.ustc.edu.cn/alpine \
    https://mirrors.aliyun.com/alpine https://dl-cdn.alpinelinux.org/alpine; do
    write_repositories "$repo"
    if ! timeout 120 apk update; then echo "索引下载失败，切换下一个软件源。"; continue; fi
    if apk add --no-cache "${packages[@]}"; then installed=1; break; fi
    echo "软件包下载失败，切换下一个软件源。"
done
if [ "$installed" != 1 ]; then
    echo "所有软件源均未能完成下载；请检查网络或剩余空间后重试。"
    exit 1
fi
node --version
npm --version

step "安装 filesystem MCP"
mkdir -p /opt/rw-filesystem
cat > /opt/rw-filesystem/package.json <<'RW_PACKAGE_JSON'
@@FILESYSTEM_PACKAGE@@
RW_PACKAGE_JSON
cat > /opt/rw-filesystem/package-lock.json <<'RW_PACKAGE_LOCK'
@@FILESYSTEM_LOCK@@
RW_PACKAGE_LOCK
cp /opt/rw-filesystem/package-lock.json /etc/rustwright/filesystem-package-lock.json
filesystem_ready=0
for registry in "$MIRROR/npm/" "$LEGACY_MIRROR/npm/" https://registry.npmjs.org/; do
    echo "npm 下载源：$registry"
    # Lockfiles contain absolute tarball URLs; registry alone does not reliably
    # redirect every npm version. Only rewrite the official host, retaining SRI.
    python3 - "$registry" <<'PY'
import pathlib, sys
source = pathlib.Path('/etc/rustwright/filesystem-package-lock.json').read_text()
pathlib.Path('/opt/rw-filesystem/package-lock.json').write_text(
    source.replace('https://registry.npmjs.org/', sys.argv[1]))
PY
    if npm ci --prefix /opt/rw-filesystem --ignore-scripts --no-audit --no-fund \
        --fetch-retries=1 --fetch-timeout=30000 --registry="$registry"; then filesystem_ready=1; break; fi
    echo "npm 依赖下载失败，尝试下一个来源。"
done
test "$filesystem_ready" = 1
ln -sfn /opt/rw-filesystem/node_modules/.bin/mcp-server-filesystem /usr/local/bin/mcp-server-filesystem
npm cache clean --force >/dev/null 2>&1 || true

# v1 moved staged clones whose proot-emulated hardlinks still pointed at the old path.
# Recover the existing object bytes in place, preserving worktrees and local changes.
repair_git_objects() {
    python3 - "$1" <<'PY'
import os, pathlib, shutil, sys, tempfile
objects = pathlib.Path(sys.argv[1]) / ".git/objects"
def relocated(target):
    prefix, separator, relative = target.partition("/.git/objects/")
    if not separator or not prefix.startswith("/opt/.rustwright-clone."):
        return None
    if ".." in pathlib.PurePosixPath(relative).parts or relative.startswith("/"):
        raise RuntimeError("Git 对象链接路径无效：" + target)
    return objects / relative
for path in objects.rglob("*"):
    if not path.is_symlink():
        continue
    target = relocated(os.readlink(path))
    if target is None:
        continue
    seen = {path}
    while target.is_symlink():
        if target in seen:
            raise RuntimeError("Git 对象链接成环：" + str(path))
        seen.add(target)
        link = os.readlink(target)
        target = relocated(link) or target.parent / link
    if not target.is_file():
        raise RuntimeError("Git 对象缺失，保留仓库供恢复：" + str(path))
    fd, temporary = tempfile.mkstemp(prefix="rw-object-", dir=path.parent)
    os.close(fd)
    try:
        shutil.copyfile(target, temporary)
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)
    print("已修复 Git 对象链接：" + str(path))
PY
}

# Scope settings to the managed repositories; keep canonical origins and user Git settings intact.
update_repo() {
    local directory=$1 seconds=$2 changes
    repair_git_objects "$directory"
    git -C "$directory" config core.createObject rename
    git -C "$directory" rev-parse --verify 'HEAD^{commit}' >/dev/null
    changes=$(git -C "$directory" status --porcelain) || return 1
    if [ -n "$changes" ]; then
        echo "$directory 有本地修改，保留现有源码。"
        return 0
    fi
    for source in "$MIRROR" "$LEGACY_MIRROR" github; do
        local options=()
        if [ "$source" != github ]; then options=(-c "url.$source/github/.insteadOf=https://github.com/"); fi
        if timeout "$seconds" git "${options[@]}" -c http.version=HTTP/1.1 \
            -c http.lowSpeedLimit=1024 -c http.lowSpeedTime=45 -C "$directory" pull --ff-only; then return 0; fi
        echo "仓库更新失败，尝试下一个来源。"
    done
    echo "仓库更新失败，继续检查已有版本。"
}
clone_repo() {
    local directory=$1 repository=$2 seconds=$3 source stage backup
    for source in "$MIRROR" "$LEGACY_MIRROR" github; do
        stage=$(mktemp -d /opt/.rustwright-clone.XXXXXX)
        echo "下载 $repository：$source"
        local options=()
        if [ "$source" != github ]; then options=(-c "url.$source/github/.insteadOf=https://github.com/"); fi
        if timeout "$seconds" git "${options[@]}" -c http.version=HTTP/1.1 \
            -c http.lowSpeedLimit=1024 -c http.lowSpeedTime=45 clone --depth 1 --single-branch \
            --config core.createObject=rename "https://github.com/$repository" "$stage"; then
            if [ -e "$directory" ]; then
                backup=$(mktemp -d "$directory.backup.XXXXXX")
                rmdir "$backup"
                mv "$directory" "$backup"
                echo "保留已有目录：$backup"
            fi
            mv "$stage" "$directory"
            git -C "$directory" rev-parse --verify 'HEAD^{commit}' >/dev/null
            return 0
        fi
        rm -rf "$stage"
        echo "仓库下载失败，尝试下一个来源。"
    done
    return 1
}

# 大文件下载：断了从断点接着下（同一次和下次重试都接着下），每 10 秒报一次进度；
# 连续 3 次没有任何进展才换下一个来源。[sha256] 不为空时校验，不对就删掉。
fetch() {
    local url=$1 out=$2 sha=${3:-} part="$2.part" stalls=0 attempts=0 before after rc watcher
    mkdir -p "$(dirname "$out")"
    while [ "$stalls" -lt 3 ] && [ "$attempts" -lt 40 ]; do
        attempts=$((attempts + 1))
        before=$(stat -c %s "$part" 2>/dev/null || echo 0)
        ( while sleep 10; do printf '  已下载 %s MB\n' "$(( $(stat -c %s "$part" 2>/dev/null || echo 0) / 1048576 ))"; done ) &
        watcher=$!
        rc=0
        curl --fail --location --silent --show-error --connect-timeout 15 --speed-time 30 --speed-limit 1024 \
            --continue-at - "$url" --output "$part" || rc=$?
        kill "$watcher" 2>/dev/null || true
        wait "$watcher" 2>/dev/null || true
        [ "$rc" -eq 0 ] && break
        after=$(stat -c %s "$part" 2>/dev/null || echo 0)
        # 33：服务器不支持断点续传；416：断点超出文件（来源换过版本）。都从头下。
        if [ "$rc" -eq 33 ] || [ "$rc" -eq 416 ]; then rm -f "$part"; fi
        if [ "$after" -gt "$before" ]; then stalls=0; echo "  连接断了，从 $((after / 1048576)) MB 处接着下"; else stalls=$((stalls + 1)); fi
    done
    [ "$rc" -eq 0 ] || return 1
    if [ -n "$sha" ] && ! printf '%s  %s\n' "$sha" "$part" | sha256sum -c - >/dev/null; then
        echo "  校验失败，删掉重下"
        rm -f "$part"
        return 1
    fi
    mv -f "$part" "$out"
}

step "安装 Codex $CODEX_VERSION"
# 官方 GitHub Release 里的单个执行程序（zstd 压缩，arm64 约 82 MB），不下载语音等非必需组件。
# sha256 固定在这里；镜像只负责传输，所有来源失败时停止安装。
case "$ARCH" in
    aarch64) CODEX_PLATFORM=linux-arm64; CODEX_TRIPLE=aarch64-unknown-linux-musl
        CODEX_SHA=cae38c9a99d13ed0515d294e1eb094592118b43461cb907402bdf9fda94aa76a
        HOST_SHA=db0fb63d7c8481fcc7761823e5b9d26eabc6080ce4bc2882667ea459b2acd48a ;;
    x86_64) CODEX_PLATFORM=linux-x64; CODEX_TRIPLE=x86_64-unknown-linux-musl
        CODEX_SHA=5a2832a53f9366c5f57a1fee7639822d2a7b4527bc26cfb2ea67b00ef2bca87d
        HOST_SHA=8f4604569aa13848c6fa63bec309564e24c7d5f619a5745170306f4e3abb815d ;;
    *) echo "当前架构 $ARCH 不支持助手执行核心，请使用 64 位手机。git、Python、Node 仍可在终端使用。"; exit 1 ;;
esac
CACHE=/var/cache/rustwright
place_codex() {
    install -m 755 "$1" /opt/codex/codex.new
    mv -f /opt/codex/codex.new /opt/codex/codex
    ln -sfn /opt/codex/codex /usr/local/bin/codex
}
install_codex_release() {
    local file="codex-$CODEX_TRIPLE.zst" path="openai/codex/releases/download/rust-v$CODEX_VERSION" url
    for url in "$MIRROR/github-release/$path/$file" "$LEGACY_MIRROR/github-release/$path/$file" "https://github.com/$path/$file" \
        "https://ghfast.top/https://github.com/$path/$file" "https://gh-proxy.com/https://github.com/$path/$file"; do
        echo "Codex 下载源：${url%%/openai/*}"
        if fetch "$url" "$CACHE/$file" "$CODEX_SHA"; then
            echo "Codex 校验通过"
            zstd -q -d -f "$CACHE/$file" -o /opt/codex/codex.unpacked
            place_codex /opt/codex/codex.unpacked
            rm -f /opt/codex/codex.unpacked "$CACHE/$file"
            return 0
        fi
        echo "这个来源下不下来，换下一个。"
    done
    return 1
}
if [ "$(codex --version 2>/dev/null | awk '{print $NF}')" != "$CODEX_VERSION" ]; then
    mkdir -p /opt/codex /usr/local/bin "$CACHE"
    install_codex_release
fi

step "安装 Codex code mode host $CODEX_VERSION"
install_code_mode_host() {
    local file="codex-code-mode-host-$CODEX_TRIPLE.zst" path="openai/codex/releases/download/rust-v$CODEX_VERSION" url
    for url in "$MIRROR/github-release/$path/$file" "$LEGACY_MIRROR/github-release/$path/$file" "https://github.com/$path/$file"; do
        echo "Code mode host 下载源：${url%%/openai/*}"
        if fetch "$url" "$CACHE/$file" "$HOST_SHA"; then
            zstd -q -d -f "$CACHE/$file" -o /opt/codex/code-mode-host.unpacked || return 1
            install -m 755 /opt/codex/code-mode-host.unpacked /opt/codex/codex-code-mode-host.new || return 1
            mv -f /opt/codex/codex-code-mode-host.new /opt/codex/codex-code-mode-host || return 1
            printf '%s\n' "$CODEX_VERSION" > /opt/codex/code-mode-host.version.new
            mv -f /opt/codex/code-mode-host.version.new /opt/codex/code-mode-host.version || return 1
            rm -f /opt/codex/code-mode-host.unpacked "$CACHE/$file"
            return 0
        fi
        echo "这个来源下不下来，换下一个。"
    done
    return 1
}
# A matching CLI version alone does not mean its separately shipped host is installed.
if [ ! -x /opt/codex/codex-code-mode-host ] || [ "$(cat /opt/codex/code-mode-host.version 2>/dev/null)" != "$CODEX_VERSION" ]; then
    mkdir -p /opt/codex "$CACHE"
    install_code_mode_host
fi
mkdir -p /root/.codex
# Upgrades must preserve the user's provider, approval, and MCP configuration.
if [ ! -f /root/.codex/config.toml ]; then
    cat > /root/.codex/config.toml <<'TOML'
# Workbench 生成；服务地址与令牌在启动时通过参数传入
analytics.enabled = false
check_for_update_on_startup = false

TOML
fi

@@ADDONS@@

step "清理缓存"
rm -rf /root/.npm /root/.cache/pip /var/cache/apk/* /tmp/rustwright-codex.*
# 下载缓存只在装好后清：失败时留着，重试从断点接着下
rm -rf /var/cache/rustwright

step "检查环境"
git --version
python3 --version
node --version
npm --version
rg --version | head -1
codex --version
command -v mcp-server-filesystem
date '+%Y-%m-%dT%H:%M:%S%z' > "$MARKER"
step "完成"
) 2>&1 | tee "$LOG"
exit "${PIPESTATUS[0]}"
