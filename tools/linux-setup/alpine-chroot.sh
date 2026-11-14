#!/bin/sh
# 在本机 chroot 里完整跑一遍虚拟环境初始化（需要 root 和网络，x86_64 宿主）。
# 和 App 的顺序一致：解压 Alpine minirootfs → 用 busybox sh 装 bash → 跑 setup.sh → 再跑一遍（修复环境）→ Codex app-server 握手。
#
#   sudo tools/linux-setup/alpine-chroot.sh <工作目录> [HTTPS 代理]
#
# 工作目录必须是新的空目录；完成后 <工作目录>/rootfs 是装好的环境，日志在 rootfs/tmp/setup-*.log。
set -eu
WORK=$1
PROXY=${2:-}
SETUP=$(cd "$(dirname "$0")/../.." && pwd)/modules/linux/impl/src/main/resources/rustwright/setup.sh
RELEASE=v3.24
ARCH=x86_64
BASE=https://dl-cdn.alpinelinux.org/alpine/$RELEASE/releases/$ARCH

mkdir "$WORK"
R=$WORK/rootfs
mkdir "$R"
FILE=$(curl -fsS "$BASE/latest-releases.yaml" | awk '/flavor: alpine-minirootfs/{m=1} m&&/file:/{print $2; exit}')
SHA=$(curl -fsS "$BASE/latest-releases.yaml" | awk -v f="$FILE" '$0~"file: "f{m=1} m&&/sha256:/{print $2; exit}')
curl -fsS -o "$WORK/$FILE" "$BASE/$FILE"
echo "$SHA  $WORK/$FILE" | sha256sum -c -
tar xzf "$WORK/$FILE" -C "$R"
cp /etc/resolv.conf "$R/etc/"
# 沙箱代理的 CA（没有就跳过）
if [ -n "${SSL_CERT_FILE:-}" ] && [ -f "$SSL_CERT_FILE" ]; then
    mkdir -p "$R$(dirname "$SSL_CERT_FILE")"
    cp "$SSL_CERT_FILE" "$R$SSL_CERT_FILE"
fi
cp "$SETUP" "$R/tmp/setup.sh"

mount --bind /dev "$R/dev"
mount -t proc proc "$R/proc"
trap 'umount -l "$R/proc" "$R/dev" 2>/dev/null || true' EXIT
run() { chroot "$R" /usr/bin/env HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    https_proxy="$PROXY" HTTPS_PROXY="$PROXY" "$@"; }

run /sbin/apk add -q --no-cache bash
for pass in 1 2; do
    start=$(date +%s)
    run /bin/bash /tmp/setup.sh > "$R/tmp/setup-$pass.log" 2>&1
    echo "setup 第 $pass 次：通过，用时 $(( $(date +%s) - start )) 秒"
done
printf '%s\n' '{"id":1,"method":"initialize","params":{"clientInfo":{"name":"smoke","version":"1"}}}' > "$R/tmp/init.json"
cat > "$R/tmp/handshake.py" <<'PY'
import json, subprocess
p = subprocess.Popen(["codex", "app-server", "--listen", "stdio://"], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                     stderr=subprocess.DEVNULL, text=True)
p.stdin.write(open("/tmp/init.json").read()); p.stdin.flush()
reply = json.loads(p.stdout.readline())
p.kill()
assert "userAgent" in reply["result"], reply
print("Codex app-server：" + reply["result"]["userAgent"])
PY
run timeout 60 python3 /tmp/handshake.py
umount -l "$R/proc" "$R/dev"
trap - EXIT
du -sh "$R"
