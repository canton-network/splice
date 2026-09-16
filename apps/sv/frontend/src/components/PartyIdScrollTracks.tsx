// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { useLayoutEffect } from 'react';

import { computeScrollMetrics } from '../hooks/useHorizontalScrollMetrics';

const nodeContainsPartyId = (node: Node): boolean =>
  node instanceof HTMLElement &&
  (node.classList.contains('party-id') || node.querySelector('.party-id') !== null);

const usePartyIdScrollTracks = (): void => {
  useLayoutEffect(() => {
    const cleanups = new Map<HTMLElement, () => void>();
    let scanScheduled = false;

    const detachTrack = (partyIdRoot: HTMLElement) => {
      const cleanup = cleanups.get(partyIdRoot);
      if (!cleanup) return;
      cleanup();
      cleanups.delete(partyIdRoot);
    };

    const attachTrack = (partyIdRoot: HTMLElement) => {
      if (cleanups.has(partyIdRoot)) return;

      const input = partyIdRoot.querySelector<HTMLInputElement>('.MuiInputBase-input.Mui-disabled');
      if (!input) return;

      partyIdRoot.classList.add('identifier-scroll-area');

      const track = document.createElement('div');
      track.className = 'party-id-scroll-track';
      track.setAttribute('aria-hidden', 'true');

      const thumb = document.createElement('div');
      thumb.className = 'party-id-scroll-thumb';
      track.appendChild(thumb);
      partyIdRoot.appendChild(track);

      const update = () => {
        const metrics = computeScrollMetrics(input);
        track.style.display = metrics.canScroll ? 'block' : 'none';
        if (!metrics.canScroll) return;

        thumb.style.transform = `translateX(${metrics.thumbLeftPercent}%) scaleX(${metrics.thumbWidthPercent / 100})`;
      };

      update();

      input.addEventListener('scroll', update, { passive: true });
      const resizeObserver = new ResizeObserver(update);
      resizeObserver.observe(input);
      resizeObserver.observe(partyIdRoot);

      cleanups.set(partyIdRoot, () => {
        input.removeEventListener('scroll', update);
        resizeObserver.disconnect();
        track.remove();
        partyIdRoot.classList.remove('identifier-scroll-area');
      });
    };

    const scan = () => {
      for (const partyIdRoot of cleanups.keys()) {
        if (!document.body.contains(partyIdRoot)) {
          detachTrack(partyIdRoot);
        }
      }
      document.querySelectorAll<HTMLElement>('.party-id').forEach(attachTrack);
    };

    const scheduleScan = () => {
      if (scanScheduled) return;
      scanScheduled = true;
      requestAnimationFrame(() => {
        scanScheduled = false;
        scan();
      });
    };

    scan();

    const mutationObserver = new MutationObserver(mutations => {
      const shouldScan = mutations.some(
        mutation =>
          mutation.removedNodes.length > 0 ||
          Array.from(mutation.addedNodes).some(nodeContainsPartyId)
      );
      if (shouldScan) scheduleScan();
    });

    mutationObserver.observe(document.body, { childList: true, subtree: true });

    return () => {
      mutationObserver.disconnect();
      [...cleanups.keys()].forEach(detachTrack);
    };
  }, []);
};

const PartyIdScrollTracks: React.FC = () => {
  usePartyIdScrollTracks();
  return null;
};

export default PartyIdScrollTracks;
