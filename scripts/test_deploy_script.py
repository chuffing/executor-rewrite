"""预编译部署脚本隔离测试；所有程序执行均mock，不执行真实部署。"""
import io
import os
import subprocess
import tarfile
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

script = Path(__file__).parent / 'deploy-version.sh'
source = script.read_text(encoding='utf-8').split("<<'PY'\n", 1)[1].rsplit('\nPY', 1)[0]
namespace = {'__name__': 'deployment_test'}
exec(compile(source, str(script), 'exec'), namespace)


class RuntimeDeploymentTest(unittest.TestCase):
    """安全解压及版本验证失败时，不触碰旧版本和current。"""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name).resolve()
        self.packages = self.root / 'packages'
        self.versions = self.root / 'versions'
        self.versions.mkdir()
        self.package = self.packages / 'staging' / 'TASK202610170001-test' / 'package.tar.gz'
        self.package.parent.mkdir(parents=True)
        self.destination = self.root / 'extract'
        self.destination.mkdir()
        self.old = self.versions / '6.2.18'
        self.old.mkdir()
        (self.old / 'keep').write_text('unchanged')
        # 普通文件模拟生产配置和current哨兵，仅检查不被部署修改。
        self.current = self.root / 'current'
        self.current.write_text('6.2.18')
        self.config = self.root / 'redis.conf'
        self.config.write_text('production-config')

        self.package.parent.chmod(0o700)
        if os.name == 'nt':
            original_stat = Path.stat
            controlled = {self.package, self.package.parent, self.package.parent.parent}
            def posix_stat(path, *args, **kwargs):
                """Windows测试时模拟执行器账户的POSIX文件权限。"""
                value = original_stat(path, *args, **kwargs)
                if path in controlled:
                    fields = list(value)
                    fields[0] &= ~0o022
                    return os.stat_result(fields)
                return value
            permission_patch = patch.object(Path, 'stat', autospec=True, side_effect=posix_stat)
            permission_patch.start()
            self.addCleanup(permission_patch.stop)
            uid_patch = patch.object(namespace['os'], 'geteuid', return_value=0, create=True)
            uid_patch.start()
            self.addCleanup(uid_patch.stop)

    def tearDown(self):
        self.temp.cleanup()

    def archive(self, missing=None, extra=None, mode=0o755, magic=b'\x7fELF'):
        """构造不可执行的合成ELF数据，实际执行会被mock拦截。"""
        with tarfile.open(str(self.package), 'w:gz') as archive:
            for binary in namespace['BINARIES']:
                if binary == missing:
                    continue
                data = magic + b'synthetic-test-data'
                entry = tarfile.TarInfo('redis-6.2.19/bin/' + binary)
                entry.size = len(data)
                entry.mode = mode
                archive.addfile(entry, io.BytesIO(data))
            if extra:
                archive.addfile(extra, io.BytesIO(b'x' * extra.size)
                                if extra.isfile() else None)

    def fake_version(self, args, **kwargs):
        """只接受--version调用，禁止make和服务启动命令。"""
        self.assertEqual(args[1:], ['--version'])
        binary = Path(args[0]).name
        text = 'redis-cli 6.2.19\n' if binary == 'redis-cli' else 'Redis server v=6.2.19 bits=64\n'
        return subprocess.CompletedProcess(args, 0, text, '')

    def deploy(self):
        """部署到测试临时目录，所有二进制执行均替换。"""
        with patch.object(namespace['subprocess'], 'run', side_effect=self.fake_version) as run:
            namespace['deploy'](str(self.package), '6.2.19',
                                str(self.versions))
        return run

    def assert_clean(self):
        """工作目录及锁清理，旧版本、current和配置保持原样。"""
        self.assertFalse(any(self.versions.glob('.deploy-*')))
        self.assertEqual((self.old / 'keep').read_text(), 'unchanged')
        self.assertEqual(self.current.read_text(), '6.2.18')
        self.assertEqual(self.config.read_text(), 'production-config')

    def test_successful_runtime_deployment(self):
        self.archive()
        run = self.deploy()
        self.assertEqual(run.call_count, 3)
        for binary in namespace['BINARIES']:
            self.assertTrue((self.versions / '6.2.19' / 'bin' / binary).is_file())
        self.assert_clean()

    def test_wrong_version(self):
        self.archive()
        def wrong(args, **kwargs):
            return subprocess.CompletedProcess(args, 0, 'Redis server v=6.2.18\n', '')
        with patch.object(namespace['subprocess'], 'run', side_effect=wrong):
            with self.assertRaises(ValueError):
                namespace['deploy'](str(self.package), '6.2.19', str(self.versions))
        self.assertFalse((self.versions / '6.2.19').exists())
        self.assert_clean()

    def test_missing_binary(self):
        for binary in namespace['BINARIES']:
            self.archive(missing=binary)
            with self.assertRaises(ValueError):
                self.deploy()
            self.assert_clean()

    def test_traversal_and_configuration(self):
        for path in ('redis-6.2.19/../outside', '/outside', 'redis-6.2.19/redis.conf',
                     'redis-6.2.19/bin/../sentinel.conf', 'redis-6.2.18/bin/extra'):
            self.archive(extra=tarfile.TarInfo(path))
            with self.assertRaises(ValueError):
                self.deploy()
            self.assert_clean()

    def test_links_and_special_files(self):
        for kind in (tarfile.SYMTYPE, tarfile.LNKTYPE, tarfile.FIFOTYPE):
            entry = tarfile.TarInfo('redis-6.2.19/bin/link')
            entry.type = kind
            entry.linkname = '../../outside'
            self.archive(extra=entry)
            with self.assertRaises(ValueError):
                self.deploy()
            self.assert_clean()

    def test_duplicate_file(self):
        self.archive(extra=tarfile.TarInfo('redis-6.2.19/bin/redis-server'))
        with self.assertRaises(ValueError):
            self.deploy()
        self.assert_clean()

    def test_existing_target_not_overwritten(self):
        self.archive()
        target = self.versions / '6.2.19'
        target.mkdir()
        (target / 'keep').write_text('existing')
        with self.assertRaises(FileExistsError):
            self.deploy()
        self.assertEqual((target / 'keep').read_text(), 'existing')
        self.assert_clean()

    def test_not_elf(self):
        self.archive(magic=b'text')
        with self.assertRaises(ValueError):
            self.deploy()
        self.assert_clean()

    def test_non_executable_archive_files(self):
        self.archive(mode=0o644)
        with self.assertRaises(ValueError):
            self.deploy()
        self.assert_clean()

    def test_incompatible_program(self):
        self.archive()
        with patch.object(namespace['subprocess'], 'run', side_effect=OSError('wrong architecture')):
            with self.assertRaises(OSError):
                namespace['deploy'](str(self.package), '6.2.19', str(self.versions))
        self.assert_clean()

    def test_program_timeout(self):
        self.archive()
        with patch.object(namespace['subprocess'], 'run', side_effect=subprocess.TimeoutExpired('fake', 3)):
            with self.assertRaises(subprocess.TimeoutExpired):
                namespace['deploy'](str(self.package), '6.2.19', str(self.versions))
        self.assert_clean()

    def test_publication_failure_cleanup(self):
        self.archive()
        with patch.object(namespace['shutil'], 'move', side_effect=OSError('disk error')):
            with self.assertRaises(OSError):
                self.deploy()
        self.assertFalse((self.versions / '6.2.19').exists())
        self.assert_clean()

    def test_interrupted_cleanup(self):
        self.archive()
        with patch.object(namespace['subprocess'], 'run', side_effect=InterruptedError('terminated')):
            with self.assertRaises(InterruptedError):
                namespace['deploy'](str(self.package), '6.2.19', str(self.versions))
        self.assert_clean()

    def test_arbitrary_input_path_rejected(self):
        external = self.root / 'other-file.tar.gz'
        external.write_bytes(b'keep')
        with patch.object(namespace['subprocess'], 'run') as run:
            with self.assertRaises(ValueError):
                namespace['deploy'](str(external), '6.2.19', str(self.versions))
            run.assert_not_called()
        self.assertEqual(external.read_bytes(), b'keep')
        self.assert_clean()

    def test_wrong_upload_directory_rejected(self):
        self.archive()
        wrong = self.packages / 'staging' / 'not-a-task'
        wrong.mkdir()
        target = wrong / 'package.tar.gz'
        target.write_bytes(self.package.read_bytes())
        with self.assertRaises(ValueError):
            namespace['deploy'](str(target), '6.2.19', str(self.versions))
        self.assertTrue(target.exists())
        self.assert_clean()

    def test_redirected_input_rejected(self):
        self.archive()
        linked = self.package.with_name('link.tar.gz')
        linked.write_bytes(self.package.read_bytes())
        original_resolve = Path.resolve
        def redirected(path, *args, **kwargs):
            """模拟软链接解析后的路径发生变化，无需系统创建链接权限。"""
            if path == linked:
                return self.package
            return original_resolve(path, *args, **kwargs)
        with patch.object(Path, 'resolve', new=redirected):
            with self.assertRaises(ValueError):
                namespace['deploy'](str(linked), '6.2.19', str(self.versions))
        self.assertTrue(self.package.exists())
        self.assert_clean()

    def test_foreign_owned_package_rejected(self):
        self.archive()
        uid = namespace['os'].geteuid()
        with patch.object(namespace['os'], 'geteuid', return_value=uid + 1):
            with self.assertRaises(ValueError):
                self.deploy()
        self.assert_clean()

    def test_group_writable_input_rejected(self):
        self.archive()
        original_stat = Path.stat
        def unsafe_stat(path, *args, **kwargs):
            """模拟暂存文件允许组用户写入。"""
            value = original_stat(path, *args, **kwargs)
            if path == self.package:
                fields = list(value)
                fields[0] |= 0o020
                return os.stat_result(fields)
            return value
        with patch.object(Path, 'stat', new=unsafe_stat):
            with self.assertRaises(ValueError):
                self.deploy()
        self.assert_clean()

    def test_hardlinked_input_rejected(self):
        self.archive()
        original_stat = Path.stat
        def hardlink_stat(path, *args, **kwargs):
            """模拟输入文件存在多个硬链接。"""
            value = original_stat(path, *args, **kwargs)
            if path == self.package:
                fields = list(value)
                fields[3] = 2
                return os.stat_result(fields)
            return value
        with patch.object(Path, 'stat', new=hardlink_stat):
            with self.assertRaises(ValueError):
                self.deploy()
        self.assert_clean()


if __name__ == '__main__':
    unittest.main()
