import hashlib
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import verify_dependencies as verification


class UpstreamLicenseTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        (self.root / 'LICENSE.md').write_text('Controlled license text for path/hash validation')
        self.entry = {'origin': 'upstream', 'license_origin': 'upstream', 'license_file': 'LICENSE.md',
                      'license_sha256': hashlib.sha256((self.root / 'LICENSE.md').read_bytes()).hexdigest()}

    def tearDown(self):
        self.directory.cleanup()

    def test_exact_upstream_license_is_required(self):
        with patch.object(verification, 'UPSTREAM', self.root):
            self.assertEqual(self.root / 'LICENSE.md', verification.verified_license(self.root, self.entry))
            (self.root / 'LICENSE.md').write_text('changed')
            with self.assertRaisesRegex(ValueError, 'checksum mismatch'):
                verification.verified_license(self.root, self.entry)

    def test_external_archive_cannot_borrow_the_upstream_license(self):
        with self.assertRaisesRegex(ValueError, 'Only original upstream'):
            verification.verified_license(self.root, dict(self.entry, origin='external'))

    def test_license_path_must_remain_confined(self):
        with patch.object(verification, 'UPSTREAM', self.root):
            with self.assertRaises(ValueError):
                verification.verified_license(self.root, dict(self.entry, license_file='../outside'))

    def test_unknown_license_origin_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'Unknown license origin'):
            verification.verified_license(self.root, dict(self.entry, license_origin='unverified'))


if __name__ == '__main__':
    unittest.main()
