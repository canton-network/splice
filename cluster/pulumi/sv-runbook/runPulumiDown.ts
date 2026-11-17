// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { PulumiAbortController } from '../pulumi';
import { downSvRunbookStacks } from './pulumiDown';

const abortController = new PulumiAbortController();

downSvRunbookStacks(abortController).catch(e => {
  console.error('Failed to run down');
  console.error(e);
  process.exit(1);
});
