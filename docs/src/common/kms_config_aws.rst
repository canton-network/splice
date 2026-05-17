..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

The mock configuration below for AWS KMS is included in ``splice-node/examples/sv-helm/kms-participant-aws-values.yaml``:

.. literalinclude:: ../../../apps/app/src/pack/examples/sv-helm/kms-participant-aws-values.yaml
    :language: yaml

Please refer to the `Canton documentation <https://docs.canton.network/global-synchronizer/production-operations/kms-operations#configure-a-amazon-web-services-aws-kms>`__
for a list of supported configuration options and their meaning,
as well as for instructions on configuring authentication to the KMS.
Note again that Splice participants support the External Key Storage mode of KMS usage,
so that (per the `relevant Canton docs <https://docs.canton.network/global-synchronizer/production-operations/kms-operations#enable-external-key-storage-with-a-kms>`__)
the authentication credentials you supply must correspond to an entity with the following IAM permissions:

* `kms:CreateKey`
* `kms:TagResource`
* `kms:Decrypt`
* `kms:Sign`
* `kms:DescribeKey`
* `kms:GetPublicKey`
