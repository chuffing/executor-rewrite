#!/bin/bash
set -euo pipefail

fail() {
    echo "$1" >&2
    exit 1
}

# 不接收平台参数，连接信息应与执行器的健康检查配置一致。
if [[ $# -ne 0 ]]; then
    echo "Sentinel停止脚本不接收参数" >&2
    exit 1
fi
host="${SENTINEL_HOST:-127.0.0.1}"
port="${SENTINEL_PORT:-26379}"
: "${SENTINEL_PASSWORD:?请配置SENTINEL_PASSWORD}"
if [[ ! "$port" =~ ^[0-9]{1,5}$ ]]; then
    echo "Sentinel端口格式不正确" >&2
    exit 1
fi
if (( 10#$port < 1 || 10#$port > 65535 )); then
    echo "Sentinel端口必须在1到65535之间" >&2
    exit 1
fi
# redis-cli路径由固定配置注入；Java在每次停止操作前动态检查文件和执行权限。
cli_bin="$REDIS_CLI_BIN"
sentinel_unit="sentinel-managed.service"
if ! command -v systemctl >/dev/null 2>&1; then
    fail "未找到systemctl，无法管理Sentinel独立服务"
fi
if ! systemctl cat "$sentinel_unit" >/dev/null 2>&1; then
    fail "Sentinel systemd服务不存在：$sentinel_unit"
fi

# 用环境变量传递密码，避免密码出现在进程命令行中。
export REDISCLI_AUTH="${SENTINEL_PASSWORD}"

# 先确认目标服务类型，防止端口配置错误时关闭另一个服务。
server_info="$("$cli_bin" --raw -h "$host" -p "$port" INFO server)" || {
    echo "无法读取Sentinel服务信息，请检查连接和认证" >&2
    exit 1
}
server_info="${server_info//$'\r'/}"
if ! grep -Eq '^redis_mode:sentinel$' <<< "$server_info"; then
    echo "目标端口不是预期的Sentinel服务，拒绝停止" >&2
    exit 1
fi

# 正常SHUTDOWN保留Redis自身的持久化行为，不使用NOSAVE或强制kill。
# redis-cli某些错误响应仍可能返回0，因此同时检查返回内容。
reply="$("$cli_bin" --raw -h "$host" -p "$port" SHUTDOWN)" || {
    echo "Sentinel停止命令执行失败" >&2
    exit 1
}
if [[ -n "$reply" ]]; then
    echo "Sentinel停止命令返回异常，请检查服务日志" >&2
    exit 1
fi

# SHUTDOWN成功后同步收敛systemd状态；不会影响独立的执行器服务。
systemctl stop "$sentinel_unit" \
    || fail "停止Sentinel systemd服务失败：$sentinel_unit"
if systemctl is-active --quiet "$sentinel_unit"; then
    fail "Sentinel systemd服务停止后仍处于active状态：$sentinel_unit"
fi
echo "Sentinel systemd服务已停止"
