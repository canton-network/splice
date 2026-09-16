..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. NOTE: add your upcoming release notes below this line. They are included in the `release_notes.rst`.

release-notes:: Upcoming

 - bump base image to full-1.0.14

 - Validator App

  - Fix a bug where the Scan proxy was missing Token Standard V2 endpoints for allocation-instruction and transfer-instruction.

 - Helm

    - The ``splice-validator`` chart now supports configuring Kubernetes resource names
      via ``appLabels.validatorApp``, ``appLabels.walletWebUi``, ``appLabels.ansWebUi``,
      ``auth.secretName``, ``validatorWebUi.secretName``, and ``ansWebUi.secretName``.
      All values default to the existing names, so this change is fully backward compatible.

