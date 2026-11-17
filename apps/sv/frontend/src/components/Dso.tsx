// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { SvClientProvider, DsoViewPrettyJSON } from '@canton-network/splice-common-frontend';
import React from 'react';
import 'react-json-pretty/themes/monikai.css';

import { useDsoInfos } from '../contexts/SvContext';
import { useMediatorStatus } from '../hooks/useMediatorStatus';
import { useSequencerStatus } from '../hooks/useSequencerStatus';
import { useSvConfig } from '../utils';

const WithProvider: React.FC = () => {
  const dsoInfoQuery = useDsoInfos();
  const sequencerStatusQuery = useSequencerStatus();
  const mediatorStatusQuery = useMediatorStatus();
  return (
    <DsoViewPrettyJSON
      dsoInfoQuery={dsoInfoQuery}
      svInfoQuery={dsoInfoQuery}
      sequencerStatusQuery={sequencerStatusQuery}
      mediatorStatusQuery={mediatorStatusQuery}
    />
  );
};

const DsoWithContexts: React.FC = () => {
  const config = useSvConfig();
  return (
    <SvClientProvider url={config.services.sv.url}>
      <WithProvider />
    </SvClientProvider>
  );
};

export default DsoWithContexts;
