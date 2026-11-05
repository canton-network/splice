// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { setupServer, SetupServer } from 'msw/node';
import { corsPreflightHandler } from '@canton-network/splice-common-test-handlers';

import { Services } from '../setup/setup';
import { buildTransferOfferMock } from './handlers/transfers-api';
import { buildWalletMock } from './handlers/wallet-api';

export const buildServer = ({ validator }: Services): SetupServer =>
  setupServer(
    corsPreflightHandler,
    ...buildWalletMock(validator.url).concat(buildTransferOfferMock(validator.url))
  );
