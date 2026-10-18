#!/bin/bash
set -euo pipefail
umask 077

# 三个固定参数：目标版本、正式安装目录、current软链接路径。
# 首次创建或切换current，不启动程序，不修改配置文件。
if [[ $# -ne 3 ]]; then
    echo "需要目标版本、正式安装目录、current软链接路径三个参数" >&2
    exit 1
fi
version="$1"
install_dir="$2"
current="$3"
work=""
locked=false

# 输出错误并终止本次切换。
fail() {
    echo "$1" >&2
    exit 1
}

# 只清理本次临时链接和已取得的锁，不删除版本目录。
cleanup() {
    local result=$?
    trap - EXIT INT TERM
    if [[ -n "$work" && "$work" == "$parent/.$name-switch-"* && ! -L "$work" ]]; then
        rm -rf -- "$work" || result=1
    fi
    if [[ "$locked" == true ]]; then
        rmdir -- "$lock" || result=1
    fi
    exit "$result"
}

[[ "$version" =~ ^[0-9]+(\.[0-9]+){1,3}(-[0-9A-Za-z.-]+)?$ ]] || fail "目标版本号格式不正确"
[[ "$install_dir" == /* && "$current" == /* ]] || fail "安装目录和current路径必须使用绝对路径"
[[ -d "$install_dir" ]] || fail "版本安装目录不存在"
install_dir="$(readlink -f -- "$install_dir")"
name="${current##*/}"
[[ -n "$name" && "$name" != . && "$name" != .. ]] || fail "current软链接路径不合法"
parent="${current%/*}"
[[ -n "$parent" ]] || parent=/
[[ -d "$parent" && -w "$parent" && -x "$parent" ]] || fail "current父目录不存在或不可写"
parent="$(readlink -f -- "$parent")"
current="$parent/$name"
lock="$parent/.$name.switch-lock"
mkdir -- "$lock" || fail "已有切换任务或遗留切换锁"
locked=true
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# 目标版本及bin目录不能通过链接跳到其他目录。
target="$install_dir/$version"
[[ -d "$target" && ! -L "$target" ]] || fail "目标版本未部署或版本目录不是普通目录"
[[ "$(readlink -f -- "$target")" == "$target" ]] || fail "目标版本路径不符合安装目录约定"
[[ -d "$target/bin" && ! -L "$target/bin" ]] || fail "目标版本bin目录不存在或不是普通目录"
for program in redis-server redis-cli redis-sentinel; do
    [[ -f "$target/bin/$program" && ! -L "$target/bin/$program" ]] || fail "目标版本程序不存在或不是普通文件：$program"
    [[ -x "$target/bin/$program" ]] || fail "目标版本程序没有执行权限：$program"
done
# 新服务器允许current尚不存在；不强制覆盖检查后被其他程序占用的路径。
# 失效软链接也满足-L，必须交给后面的有效性检查，不能当作缺失路径。
if [[ ! -L "$current" && ! -e "$current" ]]; then
    ln -sT -- "$target" "$current" || fail "current首次创建失败，路径可能已被占用"
    [[ -L "$current" && "$(readlink -f -- "$current")" == "$target" ]] \
        || fail "首次创建后检查失败，请核对current实际指向"
    echo "current软链接首次创建完成：$target"
    exit 0
fi

[[ -L "$current" ]] || fail "current已存在但不是软链接"
previous="$(readlink -f -- "$current")" || fail "current软链接无法解析"
[[ -d "$previous" ]] || fail "current未指向有效版本目录"
if [[ "$previous" == "$target" ]]; then
    echo "current已指向目标版本：$target"
    exit 0
fi

# 同一文件系统创建临时链接，通过GNU mv -T原子替换，不先删除current。
work="$(mktemp -d -- "$parent/.$name-switch-XXXXXXXXXX")"
ln -s -- "$target" "$work/current"
[[ -L "$work/current" ]] || fail "临时版本软链接创建失败"
echo "准备切换，原版本目录=$previous，目标版本目录=$target"
mv -Tf -- "$work/current" "$current"
[[ -L "$current" && "$(readlink -f -- "$current")" == "$target" ]] || fail "切换后检查失败，请核对current实际指向"
echo "版本软链接切换完成"
