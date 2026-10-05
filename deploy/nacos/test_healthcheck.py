"""Regression checks for the naming Raft readiness gate, Python 2.7/3 compatible."""

import copy
import os
import unittest
import healthcheck


class ReadinessTest(unittest.TestCase):
    def setUp(self):
        self.original_read = healthcheck.read_json
        self.original_hostname = healthcheck.socket.gethostname
        self.environment = {key: os.environ.get(key) for key in (
            "MODE", "NACOS_AUTH_IDENTITY_KEY", "NACOS_AUTH_IDENTITY_VALUE")}
        os.environ["MODE"] = "standalone"
        os.environ["NACOS_AUTH_IDENTITY_KEY"] = "identity"
        os.environ["NACOS_AUTH_IDENTITY_VALUE"] = "local-test"
        healthcheck.socket.gethostname = lambda: "stable-nacos"
        self.groups = {group: {"leader": "stable-nacos:7848",
                               "raftGroupMember": ["stable-nacos:7848"]}
                       for group in ("naming_persistent_service_v2", "naming_service_metadata",
                                     "naming_instance_metadata")}
        self.http_status = "UP"

        def read(path, headers=None):
            if path == "/actuator/health":
                return {"status": self.http_status}
            self.assertEqual({"identity": "local-test"}, headers)
            return {"data": [{"ip": "stable-nacos", "state": "UP",
                              "extendInfo": {"raftMetaData": {"metaDataMap": self.groups}}}]}

        healthcheck.read_json = read

    def tearDown(self):
        healthcheck.read_json = self.original_read
        healthcheck.socket.gethostname = self.original_hostname
        for key, original in self.environment.items():
            if original is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = original

    def test_all_three_local_leaders_are_ready(self):
        healthcheck.check()

    def test_http_up_without_one_leader_is_not_ready(self):
        for group in self.groups:
            saved = copy.deepcopy(self.groups[group])
            self.groups[group].pop("leader")
            with self.assertRaises(ValueError):
                healthcheck.check()
            self.groups[group] = saved

    def test_old_raft_member_cannot_pass_as_the_current_node(self):
        self.groups["naming_service_metadata"]["raftGroupMember"] = ["old-nacos:7848"]
        with self.assertRaises(ValueError):
            healthcheck.check()

    def test_http_down_is_not_ready(self):
        self.http_status = "DOWN"
        with self.assertRaises(ValueError):
            healthcheck.check()


if __name__ == "__main__":
    unittest.main()
