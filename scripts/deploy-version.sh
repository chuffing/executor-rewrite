#!/bin/bash
set -euo pipefail
umask 077
export LC_ALL=C
unset TAR_OPTIONS GZIP

# 部署只准备运行文件，不编译、不启停、不修改current及生产配置。
if [[ $# -ne 3 ]]; then
    echo "需要安装包完整路径、目标版本和正式安装目录三个参数" >&2
    exit 1
fi
package="$1"
version="$2"
install_dir="$3"
work=""
lock=""
locked=false
published=false
completed=false

# 输出错误并终止本次部署。
fail() {
    echo "$1" >&2
    exit 1
}

# 只清理本次创建的目录，已经完成的版本及其他版本均不删除。
cleanup() {
    local result=$?
    trap - EXIT INT TERM
    if [[ "$published" == true && "$completed" != true && "$target" == "$install_dir/$version" ]]; then
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
[[ "$package" == /* && "$install_dir" == /* ]] || fail "安装包和安装目录必须使用绝对路径"
[[ -f "$package" && -r "$package" ]] || fail "安装包不是可读的普通文件"
[[ -d "$install_dir" && -w "$install_dir" && -x "$install_dir" ]] || fail "正式安装目录不存在或不可写"
install_dir="$(readlink -f -- "$install_dir")"
[[ "$install_dir" != / ]] || fail "不能使用根目录作为版本安装目录"

# 只处理执行器创建的固定暂存文件，保持原来的受控路径及权限约束。
request="${package%/*}"
staging="${request%/*}"
[[ "${package##*/}" == package.tar.gz && "${staging##*/}" == staging ]] || fail "安装包不符合固定暂存路径约定"
[[ "${request##*/}" =~ ^TASK[0-9]{12}-[0-9A-Za-z_-]+$ ]] || fail "安装包不在执行器创建的上传目录内"
owner="$(id -u)"
for controlled in "$staging" "$request" "$package"; do
    [[ "$(stat -c %u -- "$controlled")" == "$owner" ]] || fail "暂存文件或目录不属于执行器账户"
    mode="$(stat -c %a -- "$controlled")"
    (( (8#$mode & 8#022) == 0 )) || fail "暂存文件或目录允许其他账户写入"
done
[[ "$(stat -c %h -- "$package")" == 1 ]] || fail "安装包不能是硬链接"
target="$install_dir/$version"
[[ ! -e "$target" && ! -L "$target" ]] || fail "目标版本已存在，禁止覆盖"
lock="$install_dir/.deploy-$version.lock"
mkdir -- "$lock" || fail "已有版本部署任务或遗留部署锁"
locked=true
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
work="$(mktemp -d -- "$install_dir/.deploy-XXXXXXXXXX")"

# 固定副本避免清单检查与读取文件内容使用不同的归档。
cp -- "$package" "$work/package.tar.gz"
archive="$work/package.tar.gz"

# GNU tar使用转义格式列清单，只允许约定的目录和三个普通可执行文件。
# 清单流式校验，最多100个条目、总文件大小1 GiB；拒绝链接、特殊文件和重复路径。
if ! tar --list --absolute-names --gzip --verbose --numeric-owner --full-time --quoting-style=escape \
        --file="$archive" | awk -v prefix="redis-$version" '
function reject(message) { print message > "/dev/stderr"; failed=1; exit 1 }
{
    if (NR > 100) reject("安装包条目数超过100")
    if (NF != 6) reject("归档条目格式或名称不符合约定")
    type=substr($1,1,1)
    name=$6
    sub(/\/$/, "", name)
    if (seen[name]++) reject("安装包包含重复路径")
    if (type == "d") {
        if (name != prefix && name != prefix "/bin") reject("安装包包含非约定目录")
        next
    }
    if (type != "-") reject("安装包不能包含链接或特殊文件")
    if (name != prefix "/bin/redis-server" && name != prefix "/bin/redis-cli" && name != prefix "/bin/redis-sentinel")
        reject("安装包包含非约定文件或不安全路径")
    if ($3 !~ /^[0-9]+$/ || $3+0 <= 0 || $1 !~ /[xst]/) reject("程序为空或缺少执行权限")
    total += $3
    if (total > 1073741824) reject("安装包解压总大小超过1 GiB")
    count++
}
END { if (!failed && count != 3) { print "安装包缺少运行程序" > "/dev/stderr"; exit 1 } }
'; then
    fail "Redis运行包安全检查失败"
fi

# 不按归档路径落盘，只把指定成员的内容写入本次私有目录的固定文件。
mkdir -- "$work/bin"
for name in redis-server redis-cli redis-sentinel; do
    program="$work/bin/$name"
    (set -o noclobber; tar --extract --gzip --to-stdout --occurrence=1 \
        --file="$archive" -- "redis-$version/bin/$name" > "$program") || fail "读取运行程序失败：$name"
    [[ -s "$program" ]] || fail "运行程序内容为空：$name"
    magic="$(od -An -tx1 -N4 -- "$program")"
    [[ "${magic//[[:space:]]/}" == 7f454c46 ]] || fail "运行程序不是Linux ELF文件：$name"
    chmod 700 -- "$program"
    [[ -x "$program" ]] || fail "运行程序不可执行：$name"
    output="$(timeout 3 "$program" --version | head -c 65536)" || fail "运行程序版本检查失败：$name"
    if [[ "$name" == redis-cli ]]; then
        [[ "$output" =~ redis-cli[[:space:]]+([^[:space:]]+) ]] || fail "无法读取redis-cli版本"
    else
        [[ "$output" =~ (^|[[:space:]])v=([^[:space:]]+) ]] || fail "无法读取$name版本"
    fi
    if [[ "$name" == redis-cli ]]; then
        actual_version="${BASH_REMATCH[1]}"
    else
        actual_version="${BASH_REMATCH[2]}"
    fi
    [[ "$actual_version" == "$version" ]] || fail "程序实际版本与目标版本不一致：$name"
done

# 排他创建正式目录，已有目录即使为空也不能覆盖。
mkdir -- "$target" || fail "目标版本已存在或无法创建"
published=true
mv -T -- "$work/bin" "$target/bin"
completed=true
echo "Redis运行版本部署完成：$version"
