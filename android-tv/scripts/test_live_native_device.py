import json
from pathlib import Path
import tempfile
import unittest
import zipfile

from live_native_device import APKS, device_preflight, manifest, verify


class NativeDeviceTest(unittest.TestCase):
    def artifact(self, machine=183, abi='arm64-v8a'):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        directory = Path(temp.name)
        header = bytearray(20)
        header[:6] = b'\x7fELF\x02\x01'
        header[18:20] = machine.to_bytes(2, 'little')
        for name in APKS:
            with zipfile.ZipFile(directory/name, 'w') as archive:
                if name == APKS[0]:
                    archive.writestr(f'lib/{abi}/libfixture.so', header)
        return directory

    def test_exact_commit_and_apk_hash_required(self):
        directory = self.artifact()
        data = manifest(directory, 'a'*40, 'arm64-v8a')
        (directory/'diagnostic-manifest.json').write_text(json.dumps(data))
        self.assertEqual(verify(directory, 'a'*40), data)
        with self.assertRaisesRegex(ValueError, 'SOURCE_SHA'):
            verify(directory, 'b'*40)
        with zipfile.ZipFile(directory/APKS[1], 'a') as archive:
            archive.writestr('tampered', 'changed')
        with self.assertRaisesRegex(ValueError, 'HASH_OR_ELF'):
            verify(directory, 'a'*40)

    def test_abi_folder_does_not_substitute_for_real_elf_machine(self):
        with self.assertRaisesRegex(ValueError, 'MACHINE_MISMATCH'):
            manifest(self.artifact(machine=62), 'a'*40, 'arm64-v8a')
        with self.assertRaisesRegex(ValueError, 'OTHER_ABI'):
            manifest(self.artifact(abi='x86_64'), 'a'*40, 'arm64-v8a')

    def test_read_only_preflight_never_installs_or_clears_data(self):
        values = {'ro.product.cpu.abi': 'arm64-v8a', 'ro.product.cpu.abilist': 'arm64-v8a,armeabi-v7a',
                  'ro.kernel.qemu': '0', 'ro.build.version.sdk': '30', 'sys.boot_completed': '1',
                  'ro.dalvik.vm.native.bridge': '0'}
        calls = []
        def adb(*args):
            calls.append(args)
            self.assertEqual(args[:2], ('shell', 'getprop'))
            return values[args[2]]
        self.assertEqual(device_preflight(adb)['primary_abi'], 'arm64-v8a')
        self.assertEqual(len(calls), 6)
        values['ro.product.cpu.abi'] = 'x86_64'
        with self.assertRaisesRegex(ValueError, 'PHYSICAL_NATIVE_ARM64_REQUIRED'):
            device_preflight(adb)


if __name__ == '__main__':
    unittest.main()
