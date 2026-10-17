"""使用假Redis程序验证启停脚本，不连接或修改真实服务。"""
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

BASH = os.environ.get('BASH_EXE', '/bin/bash')
SCRIPTS = Path(__file__).resolve().parent


def shell_path(path):
    """将Windows测试路径转换为Git Bash可识别的路径。"""
    if os.name == 'nt':
        return subprocess.check_output(
            [BASH, '-c', 'cygpath -u "$1"', '--', str(path)], text=True, encoding='utf-8').strip()
    return str(path)


class StartStopScriptTest(unittest.TestCase):
    """验证参数、配置、认证及服务类型保护。"""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.config = self.root / 'redis test.conf'
        self.config.write_text('port 6379\n', encoding='utf-8')
        self.log = self.root / 'calls.log'
        self.fake = self.root / 'fake-redis.sh'
        self.fake.write_text('''#!/bin/bash
printf 'call\\n' >> "$CALL_LOG"
printf '%s\\n' "$@" >> "$CALL_LOG"
if [[ "${REDISCLI_AUTH:-}" == "test-secret" ]]; then
    printf 'auth-present\\n' >> "$CALL_LOG"
fi
case "${@: -1}" in
    server)
        if [[ "${INFO_ERROR:-}" == yes ]]; then
            echo 'NOAUTH Authentication required.'
        else
            printf 'redis_mode:%s\\r\\n' "$TEST_MODE"
        fi
        ;;
    SHUTDOWN)
        if [[ "${SHUTDOWN_ERROR:-}" == yes ]]; then
            echo 'ERR shutdown failed'
        fi
        ;;
esac
exit "${FAKE_EXIT_CODE:-0}"
''', encoding='utf-8', newline='\n')
        self.fake.chmod(0o700)
        self.env = dict(os.environ)
        self.env.update({
            'CALL_LOG': shell_path(self.log),
            'REDIS_SERVER_BIN': shell_path(self.fake),
            'SENTINEL_SERVER_BIN': shell_path(self.fake),
            'REDIS_CLI_BIN': shell_path(self.fake),
            'REDIS_CONFIG_FILE': shell_path(self.config),
            'SENTINEL_CONFIG_FILE': shell_path(self.config),
            'REDIS_PASSWORD': 'test-secret',
            'SENTINEL_PASSWORD': 'test-secret',
            'REDIS_HOST': '127.0.0.1', 'REDIS_PORT': '6379',
            'SENTINEL_HOST': '127.0.0.1', 'SENTINEL_PORT': '26379',
        })

    def tearDown(self):
        self.temp.cleanup()

    def run_script(self, action, service, **overrides):
        """执行指定脚本，所有程序调用都指向测试替身。"""
        if self.log.exists():
            self.log.unlink()
        env = dict(self.env)
        env.update(overrides)
        return subprocess.run([BASH, str(SCRIPTS / (action + '-' + service + '.sh'))],
                              env=env, capture_output=True, text=True, encoding='utf-8')

    def calls(self):
        """读取假程序收到的调用参数。"""
        return self.log.read_text(encoding='utf-8') if self.log.exists() else ''

    def test_bash_syntax(self):
        for service in ('redis', 'sentinel'):
            for action in ('start', 'stop'):
                subprocess.run([BASH, '-n', str(SCRIPTS / (action + '-' + service + '.sh'))],
                               check=True)

    def test_start_arguments_and_config(self):
        for service in ('redis', 'sentinel'):
            with self.subTest(service=service):
                result = self.run_script('start', service)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertIn(shell_path(self.config), self.calls())
                self.assertIn('--daemonize\nyes\n--supervised\nno', self.calls())
                self.assertEqual('--sentinel' in self.calls(), service == 'sentinel')

    def test_missing_config(self):
        for service in ('redis', 'sentinel'):
            result = self.run_script('start', service,
                                     **{service.upper() + '_CONFIG_FILE': '/missing/config'})
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(self.calls(), '')

    def test_relative_config(self):
        for service in ('redis', 'sentinel'):
            result = self.run_script('start', service,
                                     **{service.upper() + '_CONFIG_FILE': '../redis.conf'})
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(self.calls(), '')

    def test_graceful_authenticated_shutdown(self):
        for service, mode in (('redis', 'standalone'), ('sentinel', 'sentinel')):
            result = self.run_script('stop', service, TEST_MODE=mode)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn('SHUTDOWN', self.calls())
            self.assertIn('auth-present', self.calls())
            self.assertNotIn('test-secret', self.calls())
            self.assertNotIn('NOSAVE', self.calls())

    def test_wrong_service_type_does_not_shutdown(self):
        for service, mode in (('redis', 'sentinel'), ('sentinel', 'standalone')):
            result = self.run_script('stop', service, TEST_MODE=mode)
            self.assertNotEqual(result.returncode, 0)
            self.assertNotIn('SHUTDOWN', self.calls())

    def test_authentication_error_does_not_shutdown(self):
        for service in ('redis', 'sentinel'):
            result = self.run_script('stop', service, INFO_ERROR='yes')
            self.assertNotEqual(result.returncode, 0)
            self.assertNotIn('SHUTDOWN', self.calls())

    def test_shutdown_error_even_with_zero_exit(self):
        for service, mode in (('redis', 'standalone'), ('sentinel', 'sentinel')):
            result = self.run_script('stop', service, TEST_MODE=mode, SHUTDOWN_ERROR='yes')
            self.assertNotEqual(result.returncode, 0)

    def test_invalid_port(self):
        for service in ('redis', 'sentinel'):
            for port in ('0', '65536', 'port', '1;shutdown'):
                result = self.run_script('stop', service,
                                         **{service.upper() + '_PORT': port})
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(self.calls(), '')

    def test_unexpected_arguments(self):
        for service in ('redis', 'sentinel'):
            for action in ('start', 'stop'):
                result = subprocess.run(
                    [BASH, str(SCRIPTS / (action + '-' + service + '.sh')), 'unexpected'],
                    env=self.env, capture_output=True, text=True, encoding='utf-8')
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(self.calls(), '')

    def test_missing_password(self):
        for service in ('redis', 'sentinel'):
            result = self.run_script('stop', service,
                                     **{service.upper() + '_PASSWORD': ''})
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(self.calls(), '')


if __name__ == '__main__':
    unittest.main()
