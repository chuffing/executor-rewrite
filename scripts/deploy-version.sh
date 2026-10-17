#!/bin/bash
set -euo pipefail
umask 077

# 三个固定参数：安装包完整路径、目标版本、正式安装目录。
# 只解压预编译运行包并执行--version检查，不编译、不启停、不切换current。
exec python3 - "$@" <<'PY'
import os
import re
import shutil
import signal
import stat
import subprocess
import sys
import tarfile
import tempfile
from pathlib import Path

BINARIES = ("redis-server", "redis-cli", "redis-sentinel")
MAX_EXTRACTED_BYTES = 1024 * 1024 * 1024
MAX_ENTRIES = 100
PROGRAM_CHECK_TIMEOUT_SECONDS = 3


def extract_safely(package, destination, version):
    """在独立目录内流式解压，只允许固定运行文件，拒绝所有归档链接。"""
    prefix = "redis-" + version
    allowed_files = {prefix + "/bin/" + binary for binary in BINARIES}
    allowed_directories = {prefix, prefix + "/bin"}
    names = set()
    total_size = 0
    with tarfile.open(str(package), mode="r|gz") as archive:
        for count, member in enumerate(archive, 1):
            if count > MAX_ENTRIES:
                raise ValueError("安装包条目数超限")
            name = member.name.rstrip("/") if member.isdir() else member.name
            if not name or name.startswith("/") or "\\" in name or ":" in name:
                raise ValueError("运行包包含不安全路径")
            if any(part in ("", ".", "..") for part in name.split("/")):
                raise ValueError("运行包包含路径穿越")
            if name in names:
                raise ValueError("运行包包含重复路径")
            names.add(name)
            if member.isdir():
                if name not in allowed_directories:
                    raise ValueError("运行包包含非约定目录")
                continue
            if not member.isreg():
                raise ValueError("运行包不能包含链接或特殊文件")
            if name not in allowed_files:
                raise ValueError("运行包只能包含约定程序，不能包含生产配置")
            if member.size <= 0 or not member.mode & 0o111:
                raise ValueError("运行程序为空或缺少执行权限")
            total_size += member.size
            if total_size > MAX_EXTRACTED_BYTES:
                raise ValueError("运行包解压大小超限")
            target = destination / "bin" / name.rsplit("/", 1)[1]
            target.parent.mkdir(exist_ok=True)
            # 固定文件名、排他创建，不使用tar.extractall及原归档属主权限。
            with archive.extractfile(member) as source, target.open("xb") as output:
                shutil.copyfileobj(source, output, length=64 * 1024)
            target.chmod(0o700)
    if not allowed_files.issubset(names):
        raise ValueError("运行包缺少redis-server、redis-cli或redis-sentinel")


def verify_programs(directory, version):
    """检查Linux运行文件及实际版本，--version不会启动Redis或Sentinel。"""
    for binary in BINARIES:
        program = directory / "bin" / binary
        if not program.is_file() or program.is_symlink():
            raise ValueError("运行程序不存在或不是普通文件")
        if not os.access(str(program), os.X_OK):
            raise ValueError("运行程序不可执行")
        with program.open("rb") as stream:
            if stream.read(4) != b"\x7fELF":
                raise ValueError("运行程序不是Linux ELF文件")
        # argv[0]使用固定文件路径，不经Shell拼接，限制检查耗时。
        result = subprocess.run([str(program), "--version"], check=True,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                universal_newlines=True,
                                timeout=PROGRAM_CHECK_TIMEOUT_SECONDS)
        if binary == "redis-cli":
            match = re.search(r"redis-cli\s+(\S+)", result.stdout)
        else:
            match = re.search(r"\bv=([^\s]+)", result.stdout)
        if match is None or match.group(1) != version:
            raise ValueError(binary + "实际版本与目标版本不一致")


def deploy(package_name, version, install_dir):
    """验证通过后才发布新版本目录，失败只清理本次工作产物。"""
    if not re.fullmatch(r"[0-9]+(?:\.[0-9]+){1,3}(?:-[0-9A-Za-z.-]+)?", version):
        raise ValueError("目标版本不合法")
    installed = Path(install_dir).resolve(strict=True)
    if not installed.is_dir():
        raise ValueError("正式安装路径不是目录")

    # 不读取packageDir，只接受本机执行器账户控制的标准上传文件。
    supplied = Path(package_name)
    if not supplied.is_absolute():
        raise ValueError("安装包必须使用绝对路径")
    package = supplied.resolve(strict=True)
    if package != supplied:
        raise ValueError("安装包路径不能包含符号链接或跳转")
    request = package.parent
    staging = request.parent
    if package.name != "package.tar.gz" or staging.name != "staging":
        raise ValueError("安装包不符合固定暂存路径约定")
    if not re.fullmatch(r"TASK[0-9]{12}-[0-9A-Za-z_-]+", request.name):
        raise ValueError("安装包不在执行器创建的上传目录内")
    if not package.is_file():
        raise ValueError("安装包不是普通文件")
    # 上传目录与文件必须属于执行器账户，且不能让其他账户改写。
    for controlled in (staging, request, package):
        metadata = controlled.stat()
        if metadata.st_uid != os.geteuid():
            raise ValueError("暂存文件或目录不属于执行器运行账户")
        if metadata.st_mode & (stat.S_IWGRP | stat.S_IWOTH):
            raise ValueError("暂存文件或目录允许其他账户写入")
    if package.stat().st_nlink != 1:
        raise ValueError("安装包不能是硬链接")
    target = installed / version
    if os.path.lexists(str(target)):
        raise FileExistsError("目标版本已存在，禁止重复部署或覆盖")

    lock = installed / (".deploy-" + version + ".lock")
    lock.mkdir()
    work = None
    published = False
    completed = False
    try:
        work = Path(tempfile.mkdtemp(prefix=".deploy-", dir=str(installed)))
        extract_safely(package, work, version)
        verify_programs(work, version)
        # mkdir排他占用最终目录，已有目录（包括空目录）也不覆盖。
        target.mkdir()
        published = True
        shutil.move(str(work / "bin"), str(target / "bin"))
        completed = True
    finally:
        try:
            if published and not completed:
                shutil.rmtree(str(target))
        finally:
            try:
                if work is not None:
                    shutil.rmtree(str(work))
            finally:
                lock.rmdir()


def interrupted(signum, frame):
    """收到Java超时终止信号后通过finally清理本次临时目录。"""
    raise InterruptedError("版本部署已终止")


if __name__ == "__main__":
    if len(sys.argv) != 4:
        sys.exit("需要安装包完整路径、目标版本和正式安装目录三个参数")
    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGINT, interrupted)
    deploy(*sys.argv[1:])
PY
