// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import React, { useState } from 'react';
import { Add, Remove } from '@mui/icons-material';
import { Button, TextField, Stack, Typography } from '@mui/material';

export type TextMap = { [key: string]: string };

export const TextMapDisplay: React.FC<{ textMap: { [key: string]: string } }> = ({ textMap }) => {
  return (
    <Stack spacing={2}>
      {Object.keys(textMap)
        .toSorted()
        .map(key => {
          const value = textMap[key];
          return (
            <Stack key={`meta-${key}`} overflow="hidden" textOverflow="ellipsis" maxWidth="150px">
              <Typography variant="body2" noWrap>
                {key}:
              </Typography>
              <Typography variant="body2" noWrap>
                {value}
              </Typography>
            </Stack>
          );
        })}
    </Stack>
  );
};

export const TextMapEditor: React.FC<{
  meta: TextMap;
  setTextMap: (textMap: TextMap) => void;
  idPrefix: string;
}> = ({ meta, setTextMap, idPrefix }) => {
  const keys = Object.keys(meta);
  // keep explicit empty rows so users can add key/value pairs incrementally
  const [nEntries, setNEntries] = useState(keys.length);
  const emptyEntries: string[] = Array.from({ length: nEntries - keys.length }).map(() => '');
  const allEntries = keys.concat(emptyEntries);

  const deleteEntry = (key: string) => {
    const newMeta = { ...meta };
    delete newMeta[key];
    setTextMap(newMeta);
    setNEntries(current => Math.max(0, current - 1));
  };

  return (
    <Stack direction="column">
      {allEntries.map((k, idx) => {
        const key = k || '';
        const value = meta[key] || '';

        const updateKey = (newKey: string) => {
          const newMeta = { ...meta };
          delete newMeta[key];
          newMeta[newKey] = value;
          setTextMap(newMeta);
        };
        const updateValue = (newValue: string) => setTextMap({ ...meta, [key]: newValue });

        return (
          <Stack direction="row" key={idx} spacing={1}>
            <TextField
              id={`${idPrefix}-meta-key-${idx}`}
              placeholder="key"
              value={key}
              onChange={event => updateKey(event.target.value)}
            />
            <TextField
              id={`${idPrefix}-meta-value-${idx}`}
              placeholder="value"
              value={value}
              onChange={event => updateValue(event.target.value)}
            />
            <Button startIcon={<Remove />} onClick={() => deleteEntry(key)} />
          </Stack>
        );
      })}
      <Button
        id={`${idPrefix}-add-meta`}
        startIcon={<Add />}
        onClick={() => setNEntries(nEntries + 1)}
      >
        Add Entry
      </Button>
    </Stack>
  );
};
