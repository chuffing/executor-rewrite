#!/bin/bash
set -euo pipefail

# current路径由Java传入，来自执行器已有的currentDir配置。
if [[ $# -ne 1 ]]; then
    echo "Sentinel启动脚本需要一个current软链接路径参数" >&2
    exit 1
fi
: "${SENTINEL_CONFIG_FILE:?请配置SENTINEL_CONFIG_FILE}"
config_file="${SENTINEL_CONFIG_FILE}"
if [[ "$config_file" != /* ]]; then
    echo "Sentinel配置文件必须使用绝对路径" >&2
    exit 1
fi
if [[ ! -f "$config_file" || ! -r "$config_file" ]]; then
    echo "Sentinel配置文件不存在或不可读" >&2
    exit 1
fi
# Sentinel运行时会重写配置，配置文件必须可写。
if [[ ! -w "$config_file" ]]; then
    echo "Sentinel配置文件不可写" >&2
    exit 1
fi

# 校验current软链接，解析本次启动使用的版本目录。
current_dir="$1"
if [[ "$current_dir" != /* ]]; then
    echo "current软链接必须使用绝对路径：$current_dir" >&2
    exit 1
fi
if [[ ! -L "$current_dir" ]]; then
    echo "current软链接不存在或不是软链接：$current_dir" >&2
    exit 1
fi
version_dir="$(readlink -f -- "$current_dir")" || {
    echo "current软链接无法解析：$current_dir" >&2
    exit 1
}
if [[ ! -d "$version_dir" ]]; then
    echo "current软链接未指向有效的版本目录：$current_dir" >&2
    exit 1
fi

# 从current指向的版本中获取启动程序，不再查找旧程序或PATH。
server_bin="$version_dir/bin/redis-sentinel"
cli_bin="$version_dir/bin/redis-cli"
if [[ ! -f "$server_bin" ]]; then
    echo "Sentinel启动程序不存在或不是普通文件：$server_bin" >&2
    exit 1
fi
if [[ ! -x "$server_bin" ]]; then
    echo "Sentinel启动程序没有执行权限：$server_bin" >&2
    exit 1
fi
if [[ ! -f "$cli_bin" || ! -x "$cli_bin" ]]; then
    echo "Redis客户端程序不存在或不可执行：$cli_bin" >&2
    exit 1
fi

# Sentinel已经正常运行时不再启动第二个进程，重复任务直接成功。
host="${SENTINEL_HOST:-127.0.0.1}"
port="${SENTINEL_PORT:-26379}"
: "${SENTINEL_PASSWORD:?请配置SENTINEL_PASSWORD}"
export REDISCLI_AUTH="${SENTINEL_PASSWORD}"
if server_info="$("$cli_bin" --raw -h "$host" -p "$port" INFO server 2>/dev/null)"; then
    server_info="${server_info//$'\r'/}"
    if grep -Eq '^redis_mode:sentinel$' <<< "$server_info"; then
        echo "Sentinel已经处于运行状态"
        exit 0
    fi
    echo "Sentinel端口运行的不是预期Sentinel服务" >&2
    exit 1
fi

# 后台运行后返回，由Java检查实际启动状态；不修改配置文件内容。
exec "$server_bin" "$config_file" --sentinel --daemonize yes --supervised no
