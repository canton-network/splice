// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { ContentCopy } from '@mui/icons-material';
import { Box, Chip, IconButton, Typography } from '@mui/material';
import { useRef } from 'react';

import { useHorizontalScrollMetrics } from '../../hooks/useHorizontalScrollMetrics';
import { scrollContainerSx, scrollTextSx, scrollThumbSx, scrollTrackSx } from './identifierStyles';

export type CopyableIdentifierSize = 'small' | 'large';

interface CopyableIdentifierProps {
  value: string;
  copyValue?: string;
  badge?: string;
  size: CopyableIdentifierSize;
  'data-testid': string;
}

const CopyableIdentifier: React.FC<CopyableIdentifierProps> = ({
  value,
  copyValue,
  badge,
  size,
  'data-testid': testId,
}) => {
  const scrollRef = useRef<HTMLDivElement>(null);
  const metrics = useHorizontalScrollMetrics(scrollRef, [value]);
  const fontSize = size === 'small' ? '14px' : '18px';

  return (
    <Box
      className="identifier-scroll-area"
      sx={{
        display: 'flex',
        alignItems: 'flex-start',
        color: 'text.light',
        minWidth: 0,
        maxWidth: '100%',
        width: '100%',
      }}
      data-testid={testId}
    >
      <Box sx={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column' }}>
        <Box ref={scrollRef} sx={scrollContainerSx} data-testid={`${testId}-scroll`}>
          <Typography
            component="span"
            variant="body1"
            fontWeight="medium"
            fontFamily="Source Code Pro, monospace"
            fontSize={fontSize}
            data-testid={`${testId}-value`}
            sx={scrollTextSx}
          >
            {value}
          </Typography>
        </Box>
        {metrics.canScroll && (
          <Box sx={scrollTrackSx} data-testid={`${testId}-scroll-track`} aria-hidden>
            <Box sx={scrollThumbSx(metrics.thumbLeftPercent, metrics.thumbWidthPercent)} />
          </Box>
        )}
      </Box>
      <IconButton
        color="secondary"
        data-testid={`${testId}-copy-button`}
        sx={{ flexShrink: 0, mt: -0.25 }}
        onClick={e => {
          e.stopPropagation();
          e.preventDefault();
          navigator.clipboard.writeText(copyValue ?? value);
        }}
      >
        <ContentCopy sx={{ fontSize }} />
      </IconButton>
      {badge !== undefined && (
        <Chip label={badge} size="small" data-testid={`${testId}-badge`} sx={{ flexShrink: 0 }} />
      )}
    </Box>
  );
};

export default CopyableIdentifier;
