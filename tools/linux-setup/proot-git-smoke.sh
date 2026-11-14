#!/usr/bin/env bash
# Run inside the managed Alpine environment with proot --link2symlink.
set -e
setup=${1:?Usage: bash proot-git-smoke.sh /path/to/setup.sh}
functions=$(mktemp /tmp/rw-git-functions.XXXXXX)
sed -n '/^repair_git_objects() {/,/^}/p' "$setup" > "$functions"
source "$functions"
seed=$(mktemp -d /tmp/rw-git-seed.XXXXXX)
git init -q "$seed"
git -C "$seed" config core.createObject rename
git -C "$seed" config user.name 'Regression'
git -C "$seed" config user.email 'regression@localhost'
printf 'original\n' > "$seed/file.txt"
git -C "$seed" add file.txt
git -C "$seed" commit -qm seed
old=$(mktemp -d /opt/.rustwright-clone.test.XXXXXX)
git clone -q --no-local --config core.createObject=link "file://$seed" "$old"
old_moved="$old.moved"
mv "$old" "$old_moved"
printf 'user edit\n' > "$old_moved/file.txt"
if git -C "$old_moved" cat-file -e 'HEAD^{commit}'; then
  echo 'FAIL: expected old proot clone to break after move'
  exit 1
fi
echo 'Old clone relocation failure reproduced'
repair_git_objects "$old_moved"
git -C "$old_moved" cat-file -e 'HEAD^{commit}'
test "$(cat "$old_moved/file.txt")" = 'user edit'
git -C "$old_moved" status --short
echo 'Existing object repair preserves user edits: OK'
new=$(mktemp -d /opt/.rustwright-clone.test.XXXXXX)
git clone -q --no-local --config core.createObject=rename "file://$seed" "$new"
new_moved="$new.moved"
mv "$new" "$new_moved"
git -C "$new_moved" cat-file -e 'HEAD^{commit}'
git -C "$new_moved" fsck --connectivity-only
printf 'New clone remains valid after move; core.createObject=%s\n' "$(git -C "$new_moved" config core.createObject)"
rm -rf "$seed" "$old_moved" "$new_moved"
echo 'Git regression checks passed'
rm -f "$functions"
