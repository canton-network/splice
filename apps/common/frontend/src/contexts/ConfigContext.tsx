// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import React, { Component, Context, useContext } from 'react';
import { z } from 'zod';

import { ConfigReader } from '../config';

export class ConfigProvider<T extends z.ZodType> extends Component<{
  children: React.ReactNode;
  configScheme: T;
  configContext: Context<z.infer<T> | undefined>;
}> {
  // Parsed once, so consumers can use the config object as a hook dependency.
  private config: z.infer<T> = new ConfigReader(this.props.configScheme).loadConfig();

  render(): React.JSX.Element {
    const config = this.config;
    const ConfigContext = this.props.configContext;
    return <ConfigContext.Provider value={config}>{this.props.children}</ConfigContext.Provider>;
  }
}

export const useConfig: <T>(configContext: Context<T | undefined>) => T = configContext => {
  const config = useContext(configContext);
  if (!config) {
    throw new Error('config is not initialized');
  }
  return config;
};
