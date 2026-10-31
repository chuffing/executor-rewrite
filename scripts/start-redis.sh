#!/bin/bash
set -euo pipefail
umask 077

fail() {
    echo "$1" >&2
    exit 1
}

# 读取配置中最后一个生效的简单路径指令。
# 平台生成的路径不包含空白；拒绝复杂写法，避免脚本错误解析后创建意外目录。
read_config_path() {
    local directive="$1"
    local file="$2"
    awk -v key="$directive" '
        /^[[:space:]]*#/ { next }
        $1 == key {
            $1 = ""
            sub(/^[[:space:]]+/, "")
            value = $0
        }
        END { if (value != "") print value }
    ' "$file"
}

# 在受控根目录下幂等创建运行目录，禁止配置指向任意系统路径。
ensure_controlled_directory() {
    local purpose="$1"
    local requested_path="$2"
    local allowed_root="$3"

    [[ -n "$requested_path" ]] || fail "${purpose}未配置"
    [[ "$requested_path" == /* ]] || fail "${purpose}必须使用绝对路径：$requested_path"
    [[ "$requested_path" != *[[:space:]]* ]] || fail "${purpose}不能包含空白字符：$requested_path"
    [[ "$allowed_root" == /* ]] || fail "${purpose}受控根目录必须使用绝对路径：$allowed_root"

    local normalized_root
    local normalized_path
    normalized_root="$(readlink -m -- "$allowed_root")" || fail "${purpose}受控根目录无法解析：$allowed_root"
    normalized_path="$(readlink -m -- "$requested_path")" || fail "${purpose}无法解析：$requested_path"

    [[ "$normalized_root" != / ]] || fail "${purpose}受控根目录不能是根目录"
    [[ "$normalized_path" == "$normalized_root" || "$normalized_path" == "$normalized_root"/* ]] \
        || fail "${purpose}必须位于受控根目录 $normalized_root 下：$normalized_path"

    mkdir -p -- "$normalized_path" || fail "无法创建${purpose}：$normalized_path"
    [[ -d "$normalized_path" && -w "$normalized_path" && -x "$normalized_path" ]] \
        || fail "${purpose}不可写：$normalized_path"
}

# 日志输出为空字符串时表示写标准输出；配置文件路径则确保其父目录存在。
ensure_log_directory() {
    local logfile="$1"
    local log_root="$2"
    [[ -n "$logfile" ]] || return 0
    ensure_controlled_directory "Redis日志目录" "$(dirname -- "$logfile")" "$log_root"
}

# PID目录属于系统运行目录，只检查，不由业务脚本创建。
validate_pid_directory() {
    local pidfile="$1"
    [[ -n "$pidfile" ]] || return 0
    [[ "$pidfile" == /* ]] || fail "Redis pidfile必须使用绝对路径：$pidfile"
    local pid_directory
    pid_directory="$(dirname -- "$pidfile")"
    [[ -d "$pid_directory" && -w "$pid_directory" && -x "$pid_directory" ]] \
        || fail "Redis PID目录不存在或不可写：$pid_directory"
}

# current路径由Java传入，来自执行器已有的currentDir配置。
if [[ $# -ne 1 ]]; then
    echo "Redis启动脚本需要一个current软链接路径参数" >&2
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

# Redis会在启动时切换到dir目录；运行目录不存在会直接启动失败。
redis_data_dir="$(read_config_path dir "$config_file")"
redis_logfile="$(read_config_path logfile "$config_file")"
redis_pidfile="$(read_config_path pidfile "$config_file")"
ensure_controlled_directory \
    "Redis运行目录" \
    "$redis_data_dir" \
    "${REDIS_DATA_ROOT:-/opt/redis/data}"
ensure_log_directory "$redis_logfile" "${REDIS_LOG_ROOT:-/opt/redis/logs}"
validate_pid_directory "$redis_pidfile"

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
server_bin="$version_dir/bin/redis-server"
cli_bin="$version_dir/bin/redis-cli"
if [[ ! -f "$server_bin" ]]; then
    echo "Redis启动程序不存在或不是普通文件：$server_bin" >&2
    exit 1
fi
if [[ ! -x "$server_bin" ]]; then
    echo "Redis启动程序没有执行权限：$server_bin" >&2
    exit 1
fi
if [[ ! -f "$cli_bin" || ! -x "$cli_bin" ]]; then
    echo "Redis客户端程序不存在或不可执行：$cli_bin" >&2
    exit 1
fi

# Redis已经正常运行时不再启动第二个进程，重复任务直接成功。
host="${REDIS_HOST:-127.0.0.1}"
port="${REDIS_PORT:-6379}"
: "${REDIS_PASSWORD:?请配置REDIS_PASSWORD}"
export REDISCLI_AUTH="${REDIS_PASSWORD}"
if server_info="$("$cli_bin" --raw -h "$host" -p "$port" INFO server 2>/dev/null)"; then
    server_info="${server_info//$'\r'/}"
    if grep -Eq '^redis_mode:(standalone|cluster)$' <<< "$server_info"; then
        echo "Redis已经处于运行状态"
        exit 0
    fi
    echo "Redis端口运行的不是预期Redis服务" >&2
    exit 1
fi

# 后台运行后返回，由Java检查实际启动状态；不修改配置文件内容。
exec "$server_bin" "$config_file" --daemonize yes --supervised no
