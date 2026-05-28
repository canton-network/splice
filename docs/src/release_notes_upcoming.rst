..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. NOTE: add your upcoming release notes below this line. They are included in the `release_notes.rst`.

.. release-notes:: Upcoming

- Validator app

  - ``TransferPreapprovalProposal``s are now only accepted for parties hosted on your node.
    To recover the prior behavior you can enable ``canton.validator-apps.validator_backend.transfer-preapproval.accept-non-hosted-preapproval-proposals = true``.

    Thanks to `Rhaydden <https://github.com/Rhaydden>`_ for reporting.
