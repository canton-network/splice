# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

import unittest

from failure_notification_args import FailureArgs
from slack_notification import SlackNotification


class SlackNotificationTest(unittest.TestCase):
    def test_build_msg(self) -> None:
        args = FailureArgs(
            cluster="",
            slack_channel="#c",
            branch_pattern="main",
            dry_run=True,
            branch="main",
            github_token="t",
            job_subname="",
            job_name="job",
            gha_run_id="42",
            gha_workflow_name="wf",
        )
        msg = SlackNotification(args).build_msg("http://issue")
        self.assertIn("wf:job 42 failed", msg)
        self.assertIn("<http://issue|Issue in GitHub>", msg)


if __name__ == "__main__":
    unittest.main()
