#!/bin/bash
set -euo pipefail
umask 077
export LC_ALL=C
unset TAR_OPTIONS GZIP

# 从Redis官方源码包编译并部署新版本，不启停服务、不修改current及生产配置。
if [[ $# -ne 3 ]]; then
    echo "需要源码包完整路径、目标版本和正式安装目录三个参数" >&2
    exit 1
fi
package="$1"
version="$2"
install_dir="$3"
work=""
lock=""
locked=false
published=false

# 输出错误并终止本次部署。
fail() {
    echo "$1" >&2
    exit 1
}

# 只清理本次创建的目录，已经存在的版本及其他版本均不删除。
cleanup() {
    local result=$?
    trap - EXIT INT TERM
    if [[ "$published" == true && "$target" == "$install_dir/$version" ]]; then
        rm -rf -- "$target" || result=1
    fi
    if [[ -n "$work" && "$work" == "$install_dir"/.deploy-* && ! -L "$work" ]]; then
        rm -rf -- "$work" || result=1
    fi
    if [[ "$locked" == true ]]; then
        rmdir -- "$lock" || result=1
    fi
    exit "$result"
}

[[ "$version" =~ ^[0-9]+(\.[0-9]+){1,3}(-[0-9A-Za-z.-]+)?$ ]] || fail "目标版本号格式不正确"
[[ "$package" == /* && "$install_dir" == /* ]] || fail "源码包和安装目录必须使用绝对路径"
[[ -f "$package" && -r "$package" ]] || fail "源码包不是可读的普通文件"
[[ -d "$install_dir" && -w "$install_dir" && -x "$install_dir" ]] || fail "正式安装目录不存在或不可写"
for command_name in tar make cc timeout od readlink stat mktemp; do
    command -v "$command_name" >/dev/null 2>&1 || fail "缺少Redis源码编译依赖：$command_name"
done
install_dir="$(readlink -f -- "$install_dir")"
[[ "$install_dir" != / ]] || fail "不能使用根目录作为版本安装目录"

# 只处理执行器创建的固定暂存文件，保持原来的受控路径及权限约束。
request="${package%/*}"
staging="${request%/*}"
[[ "${package##*/}" == package.tar.gz && "${staging##*/}" == staging ]] || fail "源码包不符合固定暂存路径约定"
[[ "${request##*/}" =~ ^TASK[0-9]{12}-[0-9A-Za-z_-]+$ ]] || fail "源码包不在执行器创建的上传目录内"
owner="$(id -u)"
for controlled in "$staging" "$request" "$package"; do
    [[ "$(stat -c %u -- "$controlled")" == "$owner" ]] || fail "暂存文件或目录不属于执行器账户"
    mode="$(stat -c %a -- "$controlled")"
    (( (8#$mode & 8#022) == 0 )) || fail "暂存文件或目录允许其他账户写入"
done
[[ "$(stat -c %h -- "$package")" == 1 ]] || fail "源码包不能是硬链接"
target="$install_dir/$version"
lock="$install_dir/.deploy-$version.lock"
mkdir -- "$lock" || fail "已有版本部署任务或遗留部署锁"
locked=true
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# 重试任务发现目标版本已经完整部署时直接成功；异常或不完整目录禁止覆盖。
if [[ -e "$target" || -L "$target" ]]; then
    [[ -d "$target" && ! -L "$target" ]] || fail "目标版本路径已存在但不是普通目录"
    [[ "$(readlink -f -- "$target")" == "$target" ]] || fail "目标版本路径不符合安装目录约定"
    [[ -d "$target/bin" && ! -L "$target/bin" ]] || fail "目标版本bin目录不存在或不是普通目录"
    for name in redis-server redis-cli; do
        program="$target/bin/$name"
        [[ -f "$program" && -x "$program" && ! -L "$program" ]] || fail "目标版本程序不存在或不安全：$name"
        magic="$(od -An -tx1 -N4 -- "$program")"
        [[ "${magic//[[:space:]]/}" == 7f454c46 ]] || fail "目标版本程序不是Linux ELF文件：$name"
        output="$(timeout 3 "$program" --version | head -c 65536)" || fail "目标版本程序版本检查失败：$name"
        if [[ "$name" == redis-cli ]]; then
            [[ "$output" =~ redis-cli[[:space:]]+([^[:space:]]+) ]] || fail "无法读取目标redis-cli版本"
            actual_version="${BASH_REMATCH[1]}"
        else
            [[ "$output" =~ (^|[[:space:]])v=([^[:space:]]+) ]] || fail "无法读取目标redis-server版本"
            actual_version="${BASH_REMATCH[2]}"
        fi
        [[ "$actual_version" == "$version" ]] || fail "已存在程序的实际版本与目标版本不一致：$name"
    done
    sentinel="$target/bin/redis-sentinel"
    [[ -L "$sentinel" && "$(readlink -- "$sentinel")" == redis-server ]] \
        || fail "已存在版本的redis-sentinel链接不正确"
    [[ "$(readlink -f -- "$sentinel")" == "$target/bin/redis-server" && -x "$sentinel" ]] \
        || fail "已存在版本的redis-sentinel链接无效"
    echo "目标Redis版本已经完整部署：$version"
    exit 0
fi

work="$(mktemp -d -- "$install_dir/.deploy-XXXXXXXXXX")"

# 固定副本避免清单检查、解压和编译读取不同的归档内容。
cp -- "$package" "$work/package.tar.gz"
archive="$work/package.tar.gz"
archive_list="$work/archive.list"
source_root="redis-$version"

# 源码包只能包含redis-{version}顶层目录，拒绝绝对路径、父目录跳转和重复条目。
tar --list --gzip --file="$archive" > "$archive_list" || fail "Redis源码包无法读取"
[[ -s "$archive_list" ]] || fail "Redis源码包内容为空"
entry_count=0
while IFS= read -r entry; do
    normalized="${entry%/}"
    [[ -n "$normalized" ]] || fail "Redis源码包包含空路径"
    [[ "$normalized" != /* ]] || fail "Redis源码包包含绝对路径"
    [[ "/$normalized/" != *"/../"* ]] || fail "Redis源码包包含父目录跳转"
    if [[ "$normalized" != "$source_root" && "$normalized" != "$source_root"/* ]]; then
        fail "Redis源码包顶层目录与目标版本不一致"
    fi
    entry_count=$((entry_count + 1))
    (( entry_count <= 50000 )) || fail "Redis源码包条目数超过50000"
done < "$archive_list"
if LC_ALL=C sort "$archive_list" | uniq -d | grep -q .; then
    fail "Redis源码包包含重复路径"
fi

# 解压到本次任务的私有目录，不保留归档中的属主和宽松权限。
source_parent="$work/source"
mkdir -- "$source_parent"
tar --extract --gzip --file="$archive" --directory="$source_parent" \
    --no-same-owner --no-same-permissions || fail "Redis源码包解压失败"
source_directory="$source_parent/$source_root"
[[ -d "$source_directory" && ! -L "$source_directory" ]] || fail "Redis源码目录不存在或不安全"
[[ -f "$source_directory/Makefile" && ! -L "$source_directory/Makefile" ]] || fail "Redis源码包缺少Makefile"
[[ -d "$source_directory/src" && ! -L "$source_directory/src" ]] || fail "Redis源码包缺少src目录"

# 使用固定并行度编译，避免用户参数进入Shell命令。
make -C "$source_directory" -j2 || fail "Redis源码编译失败"

# 只发布平台实际使用的程序，并核对ELF格式及版本号。
publish="$work/publish"
mkdir -p -- "$publish/bin"
for name in redis-server redis-cli; do
    compiled_program="$source_directory/src/$name"
    [[ -f "$compiled_program" && -x "$compiled_program" && ! -L "$compiled_program" ]] || fail "编译后未生成可执行程序：$name"
    magic="$(od -An -tx1 -N4 -- "$compiled_program")"
    [[ "${magic//[[:space:]]/}" == 7f454c46 ]] || fail "编译产物不是Linux ELF文件：$name"
    program="$publish/bin/$name"
    cp -- "$compiled_program" "$program"
    chmod 700 -- "$program"
    output="$(timeout 3 "$program" --version | head -c 65536)" || fail "程序版本检查失败：$name"
    if [[ "$name" == redis-cli ]]; then
        [[ "$output" =~ redis-cli[[:space:]]+([^[:space:]]+) ]] || fail "无法读取redis-cli版本"
        actual_version="${BASH_REMATCH[1]}"
    else
        [[ "$output" =~ (^|[[:space:]])v=([^[:space:]]+) ]] || fail "无法读取redis-server版本"
        actual_version="${BASH_REMATCH[2]}"
    fi
    [[ "$actual_version" == "$version" ]] || fail "程序实际版本与目标版本不一致：$name"
done
ln -s -- redis-server "$publish/bin/redis-sentinel"

# 临时目录与正式目录位于同一文件系统，完成校验后一次性发布。
mv -T -- "$publish" "$target" || fail "Redis版本目录发布失败"
published=true
echo "Redis源码编译及版本部署完成：$version"
published=false
