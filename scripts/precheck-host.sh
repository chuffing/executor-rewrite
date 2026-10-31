#!/bin/bash
set -euo pipefail

# 只检查运行环境，不创建文件，不调用启停、部署或版本切换脚本。
if [[ $# -ne 0 ]]; then
    echo "主机预检查脚本不接收参数" >&2
    exit 1
fi
if (( BASH_VERSINFO[0] < 4 )); then
    echo "主机预检查需要Bash 4或以上版本" >&2
    exit 1
fi

# 固定脚本使用的系统命令，不要求安装Python或systemd。
commands=(uname bash tar gzip awk dirname readlink stat id head od chmod cp mv ln mkdir mktemp rm rmdir timeout grep)
for command_name in "${commands[@]}"; do
    if ! command -v -- "$command_name" >/dev/null 2>&1; then
        echo "缺少脚本运行所需命令：$command_name" >&2
        exit 1
    fi
done
if [[ "$(uname -s)" != Linux ]]; then
    echo "执行器运维脚本需要Linux环境" >&2
    exit 1
fi
tar_version="$(tar --version)"
if [[ "$tar_version" != *"GNU tar"* ]]; then
    echo "安全部署脚本需要GNU tar" >&2
    exit 1
fi
echo "Bash及系统命令检查通过"
