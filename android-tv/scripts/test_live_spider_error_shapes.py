import json
import unittest
from live_spider_error_shapes import classify


class ErrorShapePrivacyTest(unittest.TestCase):
    def test_only_fixed_error_kind_and_inventory_class_survive(self):
        raw = '\n'.join([
            '11-10 12:00:00.000 1 2 I ActivityManager: Start proc 456:tv.herrin.app:spider/u0a123',
            '11-10 12:00:00.100 456 457 W System.err: java.lang.NoSuchMethodError: com.github.catvod.net.OkHttp.privateName https://private.invalid/?token=PRIVATE Cookie: PRIVATE',
            '11-10 12:00:00.101 789 790 W System.err: java.net.UnknownHostException: unrelated-private-host',
            '11-10 12:00:00.102 456 457 I Provider: arbitrary private body mentioning NoSuchFieldError',
        ])
        result = classify(raw, {'com.github.catvod.net.OkHttp'})
        self.assertEqual([{'type':'NoSuchMethodError','sdk_classes':['com.github.catvod.net.OkHttp'],'count':1}], result['errors'])
        output = json.dumps(result)
        for secret in ('PRIVATE','privateName','private.invalid','Cookie','unrelated-private-host'):
            self.assertNotIn(secret, output)

    def test_missing_process_identity_never_claims_clean_provider_logs(self):
        result = classify('11-10 12:00:00.101 789 790 W System.err: java.net.UnknownHostException: secret', set())
        self.assertEqual('NO_PROCESS_START_RECORD', result['coverage'])
        self.assertEqual([], result['errors'])
