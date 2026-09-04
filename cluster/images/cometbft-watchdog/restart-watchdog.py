#!/usr/bin/env python3

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

import json
import math
import os
import random
import re
import signal
import sys
import time
import urllib.error
import urllib.request

# Rate of confirmation requests accepted by the sequencer
SEQUENCER_METRIC = "daml_sequencer_block_events_total"
SEQUENCER_LABELS = {"type": "send-confirmation-request"}
# Rate of confirmation requests processed by the mediator (approved and rejected).
MEDIATOR_METRIC = "daml_mediator_requests_total"
MEDIATOR_LABELS = {}

LABEL_RE = re.compile(r'([a-zA-Z_][a-zA-Z0-9_]*)="((?:[^"\\]|\\.)*)"')
ESCAPES = {"\\": "\\", '"': '"', "n": "\n"}


class ConfigError(Exception):
    pass


class ScrapeError(Exception):
    pass


# minimal json log helper to avoid having to pull in dependencies. field names chosen to match canton logging.
def log(severity, message, **fields):
    entry = {
        "level": severity,
        "@timestamp": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "message": message,
    }
    entry.update(fields)
    print(json.dumps(entry), flush=True)

# unescape helper to deal with escaped labels
def unescape(value):
    out = []
    chars = iter(value)
    for char in chars:
        if char == "\\":
            escaped = next(chars, "")
            out.append(ESCAPES.get(escaped, escaped))
        else:
            out.append(char)
    return "".join(out)


def parse_samples(text, metric, required_labels, source):
    """Parse all entries for `metric` that have `required_labels` and return them as a dict indexed by the labels.
    """
    samples = {}
    labelled_prefix = metric + "{"
    unlabelled_prefix = metric + " "
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith(labelled_prefix):
            # Label values may contain '}', the value after the labels never does.
            labels_end = line.rindex("}")
            labels = {
                match.group(1): unescape(match.group(2))
                for match in LABEL_RE.finditer(line[len(labelled_prefix) : labels_end])
            }
            rest = line[labels_end + 1 :]
        elif line.startswith(unlabelled_prefix):
            labels = {}
            rest = line[len(metric) :]
        else:
            continue
        if any(labels.get(name) != value for name, value in required_labels.items()):
            continue
        # `rest` is the value, optionally followed by a timestamp.
        try:
            value = float(rest.split()[0])
        except (IndexError, ValueError):
            log("WARNING", "Ignoring unparseable sample", source=source, line=line)
            continue
        if math.isnan(value):
            continue
        samples[tuple(sorted(labels.items()))] = value
    return samples


def scrape(url, metric, required_labels, timeout):
    try:
        request = urllib.request.Request(
            f"{url}?name[]={metric}", headers={"Accept": "text/plain"}
        )
        with urllib.request.urlopen(request, timeout=timeout) as response:
            charset = response.headers.get_content_charset() or "utf-8"
            body = response.read().decode(charset, errors="replace")
    except Exception as error:
        raise ScrapeError(f"failed to scrape {url}: {error}") from error
    samples = parse_samples(body, metric, required_labels, url)
    return samples


def counter_increase(previous, current):
    # we already did filter to relevant labels when scraping so here we just sum up everything.
    total_previous = 0.0
    total_current = 0.0
    for key, value in previous.items():
        total_previous += value
    for key, value in current.items():
        total_current += value
    return total_current - total_previous


def env_float(name):
    raw = os.environ.get(name)
    if not raw:
        raise ConfigError(f"{name} must be set")
    try:
        value = float(raw)
    except ValueError as error:
        raise ConfigError(f"{name} must be a number, got {raw!r}") from error
    return value


def env_url(name):
    url = os.environ.get(name, "").strip()
    if not url:
        raise ConfigError(f"{name} must be set")
    return url


class Config:
    def __init__(self):
        self.sequencer_url = env_url("WATCHDOG_SEQUENCER_METRICS_URL")
        self.mediator_url = env_url("WATCHDOG_MEDIATOR_METRICS_URL")
        self.poll_interval = env_float("WATCHDOG_POLL_INTERVAL_SECONDS")
        self.threshold = env_float("WATCHDOG_THRESHOLD")
        self.evaluation_interval = env_float("WATCHDOG_EVALUATION_INTERVAL_SECONDS")
        self.scrape_timeout = env_float("WATCHDOG_SCRAPE_TIMEOUT_SECONDS")
        self.startup_grace = env_float("WATCHDOG_STARTUP_GRACE_SECONDS")
        self.cooldown = env_float("WATCHDOG_COOLDOWN_SECONDS")
        self.marker_file = os.environ.get("WATCHDOG_MARKER_FILE")
        if self.evaluation_interval < self.poll_interval:
            raise ConfigError(
                "WATCHDOG_EVALUATION_INTERVAL_SECONDS must be at least "
                "WATCHDOG_POLL_INTERVAL_SECONDS, otherwise a single poll can trigger a "
                "restart"
            )

    def as_dict(self):
        return {
            "sequencerUrl": self.sequencer_url,
            "mediatorUrl": self.mediator_url,
            "pollIntervalSeconds": self.poll_interval,
            "threshold": self.threshold,
            "evaluationIntervalSeconds": self.evaluation_interval,
            "scrapeTimeoutSeconds": self.scrape_timeout,
            "startupGraceSeconds": self.startup_grace,
            "cooldownSeconds": self.cooldown,
            "markerFile": self.marker_file,
        }


class Watchdog:
    def __init__(self, config):
        self.config = config
        self.previous = None
        self.breach_since = None

    def reset(self):
        self.previous = None
        self.breach_since = None

    def request_restart(self, reason):
        marker = self.config.marker_file
        # atomic file write
        temporary = marker + ".tmp"
        with open(temporary, "w", encoding="utf-8") as handle:
            handle.write(reason + "\n")
        os.replace(temporary, marker)

    def poll(self, now):
        """Scrape and return last time and rate measured by sequencer and mediator"""
        config = self.config
        sequencer = scrape(
            config.sequencer_url, SEQUENCER_METRIC, SEQUENCER_LABELS, config.scrape_timeout
        )
        mediator = scrape(
            config.mediator_url, MEDIATOR_METRIC, MEDIATOR_LABELS, config.scrape_timeout
        )
        if not sequencer:
            raise ScrapeError(
                f"no {SEQUENCER_METRIC} samples matching {SEQUENCER_LABELS} were returned"
            )
        if not mediator:
            raise ScrapeError(f"no {MEDIATOR_METRIC} samples were returned")

        previous = self.previous
        self.previous = (now, sequencer, mediator)
        if previous is None:
            return None
        previous_time, previous_sequencer, previous_mediator = previous
        elapsed = now - previous_time
        if elapsed <= 0:
            return None
        sequencer_rate = counter_increase(previous_sequencer, sequencer) / elapsed
        mediator_rate = counter_increase(previous_mediator, mediator) / elapsed
        return previous_time, sequencer_rate, mediator_rate

    def evaluate(self, now, observation):
        """Given the data from poll check if we exceeded the threshold. Returns the reason to restart, or None."""
        interval_start, sequencer_rate, mediator_rate = observation
        # counters reset to 0 after restart so guard against that.
        if sequencer_rate < 0:
            Log(
                "INFO",
                "sequencer rate was negative likely because sequencer restarted, resetting state",
                sequencerRate=round(sequencer_rate, 4)
            )
            self.reset()
            return None
        if mediator_rate < 0:
            Log(
                "INFO",
                "mediator rate was negative likely because mediator restarted, resetting state",
                mediatorRate=round(mediator_rate, 4)
            )
            self.reset()
            return None
        difference = sequencer_rate - mediator_rate
        if difference > self.config.threshold:
            if self.breach_since is None:
                self.breach_since = interval_start
            breach_duration = now - self.breach_since
        else:
            self.breach_since = None
            breach_duration = 0.0

        log(
            "INFO",
            "Evaluated synchronizer progress",
            sequencerRate=round(sequencer_rate, 4),
            mediatorRate=round(mediator_rate, 4),
            difference=round(difference, 4),
            threshold=self.config.threshold,
            breachDurationSeconds=round(breach_duration, 1),
            evaluationIntervalSeconds=self.config.evaluation_interval,
        )

        if self.breach_since is not None and breach_duration >= self.config.evaluation_interval:
            return (
                f"sequencer rate exceeded mediator rate by {difference:.4f}/s "
                f"(threshold {self.config.threshold}/s) for {breach_duration:.0f}s"
            )
        return None


def main():
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))
    try:
        config = Config()
    except ConfigError as error:
        log("CRITICAL", f"Invalid configuration: {error}")
        return 2

    watchdog = Watchdog(config)
    log("INFO", "Starting CometBFT restart watchdog", config=config.as_dict())

    # don't immediately kill cometbft after startup
    quiet_until = time.monotonic() + config.startup_grace
    while True:
        cycle_start = time.monotonic()
        try:
            observation = watchdog.poll(cycle_start)
        except ScrapeError as error:
            # keep cometbft running if we fail to scrape.
            log("WARNING", f"Skipping evaluation: {error}")
            watchdog.reset()
            observation = None

        if observation is not None:
            reason = watchdog.evaluate(cycle_start, observation)
            if reason is not None:
                quiet_remaining = quiet_until - cycle_start
                if quiet_remaining > 0:
                    log(
                        "INFO",
                        f"Not requesting a restart yet, CometBFT is being given time to "
                        f"come up: {reason}",
                        quietRemainingSeconds=round(quiet_remaining, 1),
                    )
                else:
                    log("WARN", f"Requesting a CometBFT restart: {reason}")
                    try:
                        watchdog.request_restart(reason)
                    except OSError as error:
                        log("ERROR", f"Failed to write {config.marker_file}: {error}")
                        return 1
                    watchdog.reset()
                    # don't kill cometbft after we just killed it.
                    quiet_until = time.monotonic() + config.cooldown

        time.sleep(random.uniform(0.5, 1.0) * max(0.0, config.poll_interval - (time.monotonic() - cycle_start)))


if __name__ == "__main__":
    sys.exit(main())
