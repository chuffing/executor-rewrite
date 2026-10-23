#!/bin/bash
set -euo pipefail
umask 077

# 只在临时目录测试；源码编译和版本执行分别由make、timeout替身拦截。
scripts="$(cd -- "${BASH_SOURCE[0]%/*}" && pwd -P)"
native_kernel="$(uname -s)"
root="$(mktemp -d)"
root="$(readlink -f -- "$root")"
passed=0
skipped=0

# 校验清理范围后只删除本测试创建的工作目录。
cleanup() {
    local result=$?
    trap - EXIT
    if [[ -n "$root" && -d "$root" && ! -L "$root" && "${root##*/}" == tmp.* ]]; then
        rm -rf -- "$root"
    fi
    exit "$result"
}
trap cleanup EXIT
# Windows文件系统不能表示Linux执行位；仅测试副本跳过该判断，生产脚本保持原样。
# 原生执行权限和软链接原子替换仍需要在Linux验证。
if [[ "$native_kernel" != Linux ]]; then
    mkdir -- "$root/scripts"
    for source_script in "$scripts"/*.sh; do
        "$BASH" -n "$source_script"
    done
    sed -e 's/\[\[ -f "\$compiled_program" && -x "\$compiled_program" && ! -L "\$compiled_program" \]\]/[[ -f "$compiled_program" \&\& ! -L "$compiled_program" ]]/' \
        -e 's/\[\[ -f "\$program" && -x "\$program" && ! -L "\$program" \]\]/[[ -f "$program" \&\& ! -L "$program" ]]/' \
        -e 's/\[\[ -x "\$program" \]\]/true/' \
        "$scripts/deploy-version.sh" > "$root/scripts/deploy-version.sh"
    sed 's/\[\[ -x "\$target\/bin\/\$program" \]\]/true/' "$scripts/switch-version.sh" > "$root/scripts/switch-version.sh"
    cp -- "$scripts/precheck-host.sh" "$root/scripts/precheck-host.sh"
    scripts="$root/scripts"
    skipped=$((skipped + 1))
    echo "SKIP: Windows测试副本不验证Linux文件执行位"
fi
mkdir -- "$root/fake"
cat > "$root/fake/timeout" <<'MOCK'
#!/bin/bash
[[ $# -eq 3 && "$1" == 3 && "$3" == --version ]] || exit 1
[[ "${FAKE_VERSION_FAIL:-}" != yes ]] || exit 1
if [[ "${2##*/}" == redis-cli ]]; then
    echo "redis-cli ${FAKE_VERSION:-6.2.19}"
else
    echo "Redis server v=${FAKE_VERSION:-6.2.19} bits=64"
fi
MOCK
cat > "$root/fake/uname" <<'MOCK'
#!/bin/bash
echo Linux
MOCK
cat > "$root/fake/make" <<'MOCK'
#!/bin/bash
[[ $# -eq 3 && "$1" == -C && "$3" == -j2 ]] || exit 1
[[ "${FAKE_MAKE_FAIL:-}" != yes ]] || exit 1
source_directory="$2"
mkdir -p -- "$source_directory/src"
rm -f -- "$source_directory/src/redis-server" "$source_directory/src/redis-cli"
for binary in redis-server redis-cli; do
    [[ "${FAKE_MAKE_MISSING:-}" != "$binary" ]] || continue
    if [[ "${FAKE_MAKE_BAD_ELF:-}" == "$binary" ]]; then
        printf not-elf > "$source_directory/src/$binary"
    else
        printf '\177ELFsynthetic-test-data' > "$source_directory/src/$binary"
    fi
    chmod 700 -- "$source_directory/src/$binary"
done
MOCK
cat > "$root/fake/cc" <<'MOCK'
#!/bin/bash
exit 0
MOCK
cat > "$root/fake/mv" <<'MOCK'
#!/bin/bash
[[ "${FAKE_MOVE_FAIL:-}" != yes ]] || exit 1
exec /bin/mv "$@"
MOCK
cat > "$root/fake/ln" <<'MOCK'
#!/bin/bash
destination="${@: -1}"
if [[ "${FAKE_LINK_FAIL:-}" == yes ]]; then
    exit 1
fi
# 模拟初始检查之后有其他程序占用current，随后仍执行真实ln验证防覆盖。
if [[ "${FAKE_LINK_RACE:-}" == file ]]; then
    echo keep-race > "$destination"
elif [[ "${FAKE_LINK_RACE:-}" == directory ]]; then
    mkdir -- "$destination"
    echo keep-race > "$destination/keep"
fi
exec /bin/ln "$@"
MOCK
chmod 700 -- "$root/fake/timeout" "$root/fake/uname" "$root/fake/make" \
    "$root/fake/cc" "$root/fake/mv" "$root/fake/ln"
export PATH="$root/fake:$PATH"

# 为每个用例准备独立、符合执行器上传约定的暂存目录。
prepare() {
    case_dir="$root/case-$((passed + skipped))"
    mkdir -p -- "$case_dir/packages/staging/TASK202610170001-test" \
        "$case_dir/versions" "$case_dir/source/redis-6.2.19/src"
    package="$case_dir/packages/staging/TASK202610170001-test/package.tar.gz"
    installed="$case_dir/versions"
    echo 'all:' > "$case_dir/source/redis-6.2.19/Makefile"
    for binary in redis-server redis-cli; do
        printf '\177ELFsynthetic-test-data' > "$case_dir/source/redis-6.2.19/src/$binary"
        chmod 700 -- "$case_dir/source/redis-6.2.19/src/$binary"
    done
    echo keep > "$case_dir/redis.conf"
    tar --format=ustar --mode=0755 -czf "$package" -C "$case_dir/source" redis-6.2.19
}

# 用合成数据准备有效版本，不执行任何Redis程序。
prepare_switch() {
    prepare
    mkdir -p -- "$installed/6.2.19/bin"
    cp -- "$case_dir/source/redis-6.2.19/src/redis-server" "$installed/6.2.19/bin/redis-server"
    cp -- "$case_dir/source/redis-6.2.19/src/redis-cli" "$installed/6.2.19/bin/redis-cli"
    ln -s -- redis-server "$installed/6.2.19/bin/redis-sentinel"
    chmod 700 -- "$installed/6.2.19/bin/redis-server" "$installed/6.2.19/bin/redis-cli"
}

# 切换失败只应清理本次临时目录和锁，不应残留中间链接。
assert_failed_switch() {
    if "$BASH" "$scripts/switch-version.sh" 6.2.19 "$installed" "$case_dir/current" > "$case_dir/output" 2>&1; then
        echo "预期切换失败，实际成功" >&2
        exit 1
    fi
    [[ ! -e "$case_dir/.current.switch-lock" ]]
    if compgen -G "$case_dir/.current-switch-*" >/dev/null; then exit 1; fi
    passed=$((passed + 1))
}

# 部署失败不得留下新版本、临时目录或锁，不影响配置及上传文件。
assert_failed_deploy() {
    if "$BASH" "$scripts/deploy-version.sh" "$package" 6.2.19 "$installed" > "$case_dir/output" 2>&1; then
        echo "预期部署失败，实际成功" >&2
        exit 1
    fi
    [[ ! -e "$installed/6.2.19" && -f "$package" && "$(cat "$case_dir/redis.conf")" == keep ]]
    if compgen -G "$installed/.deploy-*" >/dev/null; then
        echo "部署失败后残留临时文件或锁" >&2
        exit 1
    fi
    passed=$((passed + 1))
}

# 所有脚本只做语法检查，不执行任何实际启停。
for script in "$scripts"/*.sh; do
    "$BASH" -n "$script"
done
passed=$((passed + 1))

# 合成源码包成功编译部署；重复部署校验版本完整后幂等成功。
prepare
"$BASH" "$scripts/deploy-version.sh" "$package" 6.2.19 "$installed"
[[ -f "$installed/6.2.19/bin/redis-server" && -f "$package" ]]
if compgen -G "$installed/.deploy-*" >/dev/null; then exit 1; fi
passed=$((passed + 1))
echo keep-version > "$installed/6.2.19/keep"
if [[ "$native_kernel" == Linux ]]; then
    "$BASH" "$scripts/deploy-version.sh" "$package" 6.2.19 "$installed" >/dev/null
    [[ "$(cat "$installed/6.2.19/keep")" == keep-version ]]
    passed=$((passed + 1))
else
    skipped=$((skipped + 1))
    echo "SKIP: Windows不验证依赖Linux软链接的重复部署幂等"
fi

# 已存在版本目录不完整时拒绝，不覆盖也不删除原目录。
prepare
mkdir -p -- "$installed/6.2.19/bin"
echo keep-incomplete > "$installed/6.2.19/keep"
if "$BASH" "$scripts/deploy-version.sh" "$package" 6.2.19 "$installed" >/dev/null 2>&1; then exit 1; fi
[[ "$(cat "$installed/6.2.19/keep")" == keep-incomplete ]]
passed=$((passed + 1))

# 编译失败、缺少编译产物、伪造ELF、版本不符、版本命令失败。
prepare
export FAKE_MAKE_FAIL=yes
assert_failed_deploy
unset FAKE_MAKE_FAIL
prepare
export FAKE_MAKE_MISSING=redis-cli
assert_failed_deploy
unset FAKE_MAKE_MISSING
prepare
export FAKE_MAKE_BAD_ELF=redis-server
assert_failed_deploy
unset FAKE_MAKE_BAD_ELF
prepare
export FAKE_VERSION=5.0.0
assert_failed_deploy
unset FAKE_VERSION
prepare
export FAKE_VERSION_FAIL=yes
assert_failed_deploy
unset FAKE_VERSION_FAIL

# 缺少源码结构、顶层目录不匹配、路径穿越及重复成员都必须拒绝。
prepare
rm -- "$case_dir/source/redis-6.2.19/Makefile"
tar --format=ustar --mode=0755 -czf "$package" -C "$case_dir/source" redis-6.2.19
assert_failed_deploy
prepare
tar --format=ustar --mode=0755 --transform='s,redis-6.2.19/src/redis-cli,../outside,' \
    -czf "$package" -C "$case_dir/source" redis-6.2.19
assert_failed_deploy
[[ ! -e "$case_dir/outside" ]]
prepare
tar --format=ustar --mode=0755 --transform='s,^,/,g' \
    -czf "$package" -C "$case_dir/source" redis-6.2.19
assert_failed_deploy
prepare
tar --format=ustar --mode=0755 -czf "$package" -C "$case_dir/source" \
    redis-6.2.19 redis-6.2.19/Makefile
assert_failed_deploy
prepare
mv -- "$case_dir/source/redis-6.2.19" "$case_dir/source/redis-6.2.18"
tar --format=ustar --mode=0755 -czf "$package" -C "$case_dir/source" redis-6.2.18
assert_failed_deploy

# 发布中断时清理本次创建的正式目录。
prepare
export FAKE_MOVE_FAIL=yes
assert_failed_deploy
unset FAKE_MOVE_FAIL

# current为真实目录时拒绝，既有内容保持不变。
prepare
mkdir -p -- "$installed/6.2.19/bin" "$case_dir/current"
cp -- "$case_dir/source/redis-6.2.19/src/redis-server" "$installed/6.2.19/bin/redis-server"
cp -- "$case_dir/source/redis-6.2.19/src/redis-cli" "$installed/6.2.19/bin/redis-cli"
ln -s -- redis-server "$installed/6.2.19/bin/redis-sentinel"
chmod 700 -- "$installed/6.2.19/bin/redis-server" "$installed/6.2.19/bin/redis-cli"
echo keep > "$case_dir/current/keep"
if "$BASH" "$scripts/switch-version.sh" 6.2.19 "$installed" "$case_dir/current" >/dev/null 2>&1; then exit 1; fi
[[ "$(cat "$case_dir/current/keep")" == keep && ! -e "$case_dir/.current.switch-lock" ]]
passed=$((passed + 1))

# current为普通文件时拒绝，原文件必须保持不变。
prepare_switch
echo keep-file > "$case_dir/current"
assert_failed_switch
[[ "$(cat "$case_dir/current")" == keep-file ]]

# 目标版本未部署时，不能创建current。
prepare
assert_failed_switch
[[ ! -e "$case_dir/current" && ! -L "$case_dir/current" ]]

# 首次创建命令失败时，返回失败并释放本次锁。
prepare_switch
export FAKE_LINK_FAIL=yes
assert_failed_switch
unset FAKE_LINK_FAIL
[[ ! -e "$case_dir/current" && ! -L "$case_dir/current" ]]

# 初始检查后的竞争文件和目录都不能被覆盖，也不能在目录内创建链接。
prepare_switch
export FAKE_LINK_RACE=file
assert_failed_switch
unset FAKE_LINK_RACE
[[ "$(cat "$case_dir/current")" == keep-race ]]
prepare_switch
export FAKE_LINK_RACE=directory
assert_failed_switch
unset FAKE_LINK_RACE
[[ "$(cat "$case_dir/current/keep")" == keep-race && ! -e "$case_dir/current/6.2.19" ]]

# 运行依赖检查不创建文件；参数错误与依赖缺失均须拒绝。
"$BASH" "$scripts/precheck-host.sh"
passed=$((passed + 1))
if "$BASH" "$scripts/precheck-host.sh" unexpected >/dev/null 2>&1; then exit 1; fi
passed=$((passed + 1))
if PATH="$root/fake" "$BASH" "$scripts/precheck-host.sh" >/dev/null 2>&1; then exit 1; fi
passed=$((passed + 1))

# 原生软链接及mv原子替换必须在Linux验证，Windows不模拟其原子性。
if [[ "$native_kernel" == Linux ]]; then
    # 首次创建及重复请求均成功，且不残留互斥锁。
    prepare_switch
    "$BASH" "$scripts/switch-version.sh" 6.2.19 "$installed" "$case_dir/current"
    [[ -L "$case_dir/current" && "$(readlink -f -- "$case_dir/current")" == "$installed/6.2.19" ]]
    [[ ! -e "$case_dir/.current.switch-lock" ]]
    passed=$((passed + 1))
    previous_link="$(stat -c %i -- "$case_dir/current")"
    "$BASH" "$scripts/switch-version.sh" 6.2.19 "$installed" "$case_dir/current"
    [[ "$(stat -c %i -- "$case_dir/current")" == "$previous_link" ]]
    [[ ! -e "$case_dir/.current.switch-lock" ]]
    passed=$((passed + 1))

    # 失效链接与指向普通文件的链接不能被当作首次初始化。
    prepare_switch
    ln -s -- "$installed/missing" "$case_dir/current"
    assert_failed_switch
    [[ -L "$case_dir/current" && "$(readlink -- "$case_dir/current")" == "$installed/missing" ]]
    prepare_switch
    ln -s -- "$case_dir/redis.conf" "$case_dir/current"
    assert_failed_switch
    [[ "$(readlink -- "$case_dir/current")" == "$case_dir/redis.conf" ]]

    # 目标程序缺失或无执行权限时，不能创建链接。
    prepare_switch
    rm -- "$installed/6.2.19/bin/redis-cli"
    assert_failed_switch
    [[ ! -e "$case_dir/current" && ! -L "$case_dir/current" ]]
    prepare_switch
    chmod 600 -- "$installed/6.2.19/bin/redis-server"
    assert_failed_switch
    [[ ! -e "$case_dir/current" && ! -L "$case_dir/current" ]]

    # 已有互斥锁不能被本次任务移除。
    prepare_switch
    mkdir -- "$case_dir/.current.switch-lock"
    if "$BASH" "$scripts/switch-version.sh" 6.2.19 "$installed" "$case_dir/current" >/dev/null 2>&1; then exit 1; fi
    [[ -d "$case_dir/.current.switch-lock" && ! -e "$case_dir/current" && ! -L "$case_dir/current" ]]
    passed=$((passed + 1))

    # 已有有效链接继续支持切换、重复执行及回切。
    prepare
    "$BASH" "$scripts/deploy-version.sh" "$package" 6.2.19 "$installed"
    cp -R -- "$installed/6.2.19" "$installed/6.2.18"
    ln -s -- "$installed/6.2.18" "$case_dir/current"
    "$BASH" "$scripts/switch-version.sh" 6.2.19 "$installed" "$case_dir/current"
    [[ "$(readlink -f -- "$case_dir/current")" == "$installed/6.2.19" ]]
    "$BASH" "$scripts/switch-version.sh" 6.2.19 "$installed" "$case_dir/current"
    "$BASH" "$scripts/switch-version.sh" 6.2.18 "$installed" "$case_dir/current"
    [[ "$(readlink -f -- "$case_dir/current")" == "$installed/6.2.18" ]]
    passed=$((passed + 1))
else
    echo "SKIP: Windows不验证Linux原生符号链接及原子替换"
    skipped=$((skipped + 8))
fi
echo "RUNTIME_SCRIPT_TESTS: passed=$passed skipped=$skipped"
