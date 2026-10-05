"""Check standalone HTTP and all naming Raft leaders (Python 2.7/3 compatible)."""

from __future__ import print_function
import json
import os
import socket
import sys

try:
    from urllib.request import Request, urlopen
except ImportError:
    from urllib2 import Request, urlopen


def read_json(path, headers=None):
    response = urlopen(Request("http://127.0.0.1:8848/nacos" + path,
                               headers=headers or {}), timeout=3)
    try:
        return json.loads(response.read().decode("utf-8"))
    finally:
        response.close()


def check():
    if os.environ.get("MODE") != "standalone":
        raise ValueError("CloudMall health check requires standalone mode")
    if read_json("/actuator/health").get("status") != "UP":
        raise ValueError("Nacos HTTP health is not UP")
    identity_key = os.environ.get("NACOS_AUTH_IDENTITY_KEY")
    identity_value = os.environ.get("NACOS_AUTH_IDENTITY_VALUE")
    if not identity_key or not identity_value:
        raise ValueError("Nacos server identity is not configured")
    # Internal identity authentication avoids a root-user/bootstrap readiness cycle.
    nodes = read_json("/v1/core/cluster/nodes", {identity_key: identity_value}).get("data", [])
    hostname = socket.gethostname()
    expected_peer = hostname + ":7848"
    if len(nodes) != 1 or nodes[0].get("ip") != hostname or nodes[0].get("state") != "UP":
        raise ValueError("Standalone member identity does not match hostname")
    groups = nodes[0].get("extendInfo", {}).get("raftMetaData", {}).get("metaDataMap", {})
    for group_name in ("naming_persistent_service_v2", "naming_service_metadata",
                       "naming_instance_metadata"):
        group = groups.get(group_name, {})
        if group.get("leader") != expected_peer or group.get("raftGroupMember") != [expected_peer]:
            raise ValueError("Naming Raft group is not ready: " + group_name)


if __name__ == "__main__":
    try:
        check()
    except Exception as failure:
        print("Nacos readiness failed: " + str(failure))
        sys.exit(1)
