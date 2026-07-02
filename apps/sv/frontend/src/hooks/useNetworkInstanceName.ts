// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { useDsoInfos } from '../contexts/SvContext';
import { useSvConfig } from '../utils';

export const useNetworkInstanceName: () => string = () => {
  const config = useSvConfig();
  const dsoInfosQuery = useDsoInfos();

  const configured = config.spliceInstanceNames.networkName;
  if (configured && configured.length > 0) {
    return configured;
  }

  const scanUrls = dsoInfosQuery.data?.nodeStates.flatMap(nsContract => {
    return nsContract.payload.state.synchronizerNodes
      .entriesArray()
      .map(entry => entry[1].scan?.publicUrl)
      .filter(url => url !== undefined);
  }) as string[];

  if (scanUrls === undefined) {
    return 'Unknown Network';
  }

  const instances = scanUrls
    .map(url => {
      const regex = /(?<=\/\/(?:scan\.)sv-\d+\.)([a-zA-Z0-9-]+)/;

      return url.match(regex)?.[1];
    })
    .filter(i => i !== undefined) as string[];

  if (instances.length > 0 && instances.every(i => i === instances[0])) {
    return getNetworkName(instances[0]);
  }

  return 'Unknown Network';
};

const getNetworkName = (network: string) => {
  // NOTE: mainnet does not have the network/cluster name in the url.
  if (network === 'global') {
    return 'MainNet';
  } else if (network === 'test') {
    return 'TestNet';
  } else if (network === 'dev') {
    return 'DevNet';
  } else if (network.startsWith('scratch')) {
    return 'ScratchNet';
  }
  return network.charAt(0).toUpperCase() + network.slice(1);
};
