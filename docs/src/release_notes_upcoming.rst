..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. NOTE: add your upcoming release notes below this line. They are included in the `release_notes.rst`.

.. release-notes:: Upcoming

  .. note::

    Next-release notes

  - Validator

    - Unsupported package versions are now automatically unvetted by the validator package vetting trigger,
      aligning validator behavior with SVs.

      You can disable validator unvetting by setting:

      .. code-block:: yaml

        - name: ADDITIONAL_CONFIG_UNSUPPORTED_DARS_UNVETTING
          value: |
            canton.validator-apps.validator_backend.parameters.enabled-features.enable-validator-dars-unvetting = false
