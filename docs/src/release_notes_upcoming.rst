..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. NOTE: add your upcoming release notes below this line. They are included in the `release_notes.rst`.

.. release-notes:: Upcoming

    - Scan app

        - The ``app_activity_record_store`` table has been modified to avoid unexpected DB performance issues.
          This required clearing the existing data in this table which has been ingested since the ``0.5.18`` release.
          This impacts the data being served via the experimental field ``app_activity_records`` on the ``/v0/events`` and ``/v0/events/{update_id}`` endpoints.
          Specifically the ``app_activity_records`` field will not contain the
          data which has been provided for the events which happened between the ``0.5.18`` and this release.
          Note that the ``app_activity_records`` data already provided for events during this period is correct
          and the network explorers who have ingested this data should keep a copy of it.

        - Added ``/v1/holdings/summary`` endpoint that drops the ``accumulated_holding_fees_unlocked``,
          ``accumulated_holding_fees_locked``, ``accumulated_holding_fees_total``, and
          ``total_available_coin`` response fields and the ``as_of_round`` request parameter, as those
          values are not meaningful aggregates. The same endpoint is also exposed on the validator
          scan-proxy. The ``/v0/holdings/summary`` endpoint is now deprecated but remains available.

     - SV app

       - Bump the minimum DAR versions to the ones from splice 0.5.7 which introduced the development fund manager as
         downgrades to earlier versions already fail. SV app automation will unvet those on the SV nodes.

         The concrete versions are:

         ================== =======
         name               version
         ================== =======
         amulet             0.1.15
         amuletNameService  0.1.16
         dsoGovernance      0.1.21
         validatorLifecycle 0.1.6
         wallet             0.1.15
         walletPayments     0.1.15
         ================== =======

       - Fix an issue where onboarding a new SV could fail when importing the ACS snapshot due to a vetting issue.

    - SV deployment

        - Updated ``participantAddress``` in `scan-values.yaml` and ``sv-validator-values.yaml`` to use the participant adress with a migration suffix.
          Ensure you override this with the correct helm install name for the participant ore reinstall the participant without a migration suffix ().

        - Cometbft: increased resource requests from 2 CPU and 5Gi to 3 CPUs and 7Gi, and the limit from 8Gi to 10Gi to better fit observed resource usage.
