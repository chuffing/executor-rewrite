#!/bin/bash
set -euo pipefail

# 不接收平台参数，只使用执行器进程的本机环境配置。
if [[ $# -ne 0 ]]; then
    echo "Redis启动脚本不接收参数" >&2
    exit 1
fi
: "${REDIS_CONFIG_FILE:?请配置REDIS_CONFIG_FILE}"
config_file="${REDIS_CONFIG_FILE}"
if [[ "$config_file" != /* ]]; then
    echo "Redis配置文件必须使用绝对路径" >&2
    exit 1
fi
if [[ ! -f "$config_file" || ! -r "$config_file" ]]; then
    echo "Redis配置文件不存在或不可读" >&2
    exit 1
fi

# 支持在PATH中查找，也支持配置固定的程序绝对路径。
server_bin="$(command -v -- "${REDIS_SERVER_BIN:-redis-server}")" || {
    echo "未找到Redis启动程序" >&2
    exit 1
}
if [[ ! -f "$server_bin" || ! -x "$server_bin" ]]; then
    echo "Redis启动程序不是可执行文件" >&2
    exit 1
fi

# 后台运行后返回，由Java检查实际启动状态；不修改配置文件内容。
exec "$server_bin" "$config_file" --daemonize yes --supervised no
