import json
import unittest
from unittest.mock import patch
from live_native_crash import classify, collect


class NativeCrashTest(unittest.TestCase):
    def test_physical_device_never_reads_global_or_other_app_logs(self):
        with patch('live_native_crash.subprocess.run') as run:
            data = collect(True, {'reason_code': 5, 'exit_status': 4, 'url': 'private-secret'})
        run.assert_not_called()
        self.assertEqual(data['own_process_exit_metadata'], {'reason_code': 5, 'exit_status': 4})
        self.assertNotIn('private-secret', json.dumps(data))
        self.assertEqual(data['native_crashes'], [])

    def test_exact_module_relative_pc_registers_without_private_paths_or_messages(self):
        text = '''*** *** ***
ABI: 'arm64'
pid: 123, tid: 456, name: Thread-14  >>> tv.herrin.app:spider <<<
signal 4 (SIGILL), code 1 (ILL_ILLOPC), fault addr 0x000000123456abcd
Abort message: 'token=private-secret https://private.example/a'
    x0 0000000000000012  x1 0000000000000034
    lr 0000001234560000  sp 0000007777770000
11-13 12:00:00.000 123 456 F DEBUG :     pc 000000123456abcd  pstate 0000000060000000
backtrace:
    #00 pc 00000000000a995c  /apex/private-secret/lib64/bionic/libc.so (syscall+28) (BuildId: 1234567890abcdef1234567890abcdef)
    #01 pc 0000000000012340  /data/user/0/private-secret/libnativeplugin.so (BuildId: abcdef1234567890abcdef1234567890)
*** *** ***
pid: 999, tid: 999, name: main  >>> another.app <<<
signal 11 (SIGSEGV), code 1, fault addr 0x1234
    #00 pc 0000000012345678 /data/libanother.so
'''
        data = classify(text)
        row = data['native_crashes'][0]
        self.assertEqual((row['tid'], row['thread_name'], row['abi']), (456, 'Thread-14', 'arm64'))
        self.assertEqual(row['frames'][1]['module'], 'libnativeplugin.so')
        self.assertEqual(row['frames'][1]['relative_pc'], '0x0000000000012340')
        self.assertEqual(row['frames'][0]['system_symbol'], 'syscall+28')
        self.assertEqual(row['registers']['x1'], '0x0000000000000034')
        self.assertEqual(row['registers']['pc'], '0x000000123456abcd')
        self.assertEqual(row['fault_address'], '0x000000123456abcd')
        self.assertEqual(len(data['native_crashes']), 1)
        for private in ('private-secret', 'https://', '/data/', '/apex/', 'libanother'):
            self.assertNotIn(private, json.dumps(data))

    def test_missing_old_evidence_is_not_invented(self):
        row = classify('*** *** *** >>> tv.herrin.app:spider <<<\nsignal 4 (SIGILL)')['native_crashes'][0]
        self.assertEqual(row['frames'], [])
        self.assertEqual(row['registers'], {})
        self.assertNotIn('fault_address', row)

    def test_other_packages_rejected_and_reference_scoped(self):
        with self.assertRaises(ValueError):
            classify('', 'another.app')
        self.assertEqual(classify('>>> tv.herrin.app:spider <<<', 'com.fongmi.android.tv'), {'native_crashes': []})


if __name__ == '__main__':
    unittest.main()
