// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { ContentCopy } from '@mui/icons-material';
import { Box, IconButton, Link } from '@mui/material';
import { sanitizeUrl } from '@canton-network/splice-common-frontend-utils';
import { useRef } from 'react';

import { useHorizontalScrollMetrics } from '../../hooks/useHorizontalScrollMetrics';
import type { CopyableIdentifierSize } from './CopyableIdentifier';
import {
  scrollContainerSx,
  scrollThumbSx,
  scrollTrackSx,
  URL_COMPACT_MAX_WIDTH_PX,
} from './identifierStyles';

interface CopyableUrlProps {
  url: string;
  size: CopyableIdentifierSize;
  'data-testid': string;
}

const CopyableUrl: React.FC<CopyableUrlProps> = ({ url, size, 'data-testid': testId }) => {
  const sanitizedUrl = sanitizeUrl(url);
  const fontSize = size === 'small' ? '14px' : '16px';
  const scrollRef = useRef<HTMLDivElement>(null);
  const metrics = useHorizontalScrollMetrics(scrollRef, [sanitizedUrl]);

  return (
    <Box
      className="identifier-scroll-area"
      sx={{
        display: 'inline-flex',
        alignItems: 'center',
        color: 'text.light',
        maxWidth: '100%',
        minWidth: 0,
      }}
      data-testid={testId}
    >
      <Box
        sx={{
          flex: '0 1 auto',
          minWidth: 0,
          maxWidth: URL_COMPACT_MAX_WIDTH_PX,
          display: 'flex',
          flexDirection: 'column',
          position: 'relative',
        }}
      >
        <Box
          ref={scrollRef}
          sx={{
            ...scrollContainerSx,
            maxWidth: URL_COMPACT_MAX_WIDTH_PX,
            width: '100%',
          }}
          data-testid={`${testId}-scroll`}
        >
          <Link
            href={sanitizedUrl}
            target="_blank"
            color="inherit"
            underline="hover"
            title={sanitizedUrl}
            sx={{
              fontFamily: 'Source Code Pro, monospace',
              fontSize,
              fontWeight: 'medium',
              display: 'inline-block',
              width: 'max-content',
              maxWidth: '100%',
              whiteSpace: 'nowrap',
            }}
            data-testid={`${testId}-link`}
          >
            {sanitizedUrl}
          </Link>
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
        sx={{ flexShrink: 0 }}
        onClick={() => navigator.clipboard.writeText(sanitizedUrl)}
      >
        <ContentCopy sx={{ fontSize }} />
      </IconButton>
    </Box>
  );
};

export default CopyableUrl;
