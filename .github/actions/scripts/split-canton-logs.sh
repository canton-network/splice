#!/usr/bin/env bash

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

###############################################################################
# Split Canton logs into logs before and after shutdown
###############################################################################



set -eou pipefail

LOGFILE="$1"
LOGFILE_BEFORE="$2"
LOGFILE_AFTER="$3"

# We're using this rather specific pattern to avoid skipping over logs.
SHUTDOWN_MESSAGE_PATTERN='"message":"Shutting down\.\.\.","logger_name":"c\.d\..*\.Canton.*App$"'

# Succeed if logfile does not exist
if [[ ! -f "$LOGFILE" ]]
then
  echo "$LOGFILE does not exist."
  echo "Skipping splitting of log file."
  exit 0
fi

# Fail if the shutdown pattern is present more than once
NUM_SHUTDOWN_PATTERNS=$(grep --count --max-count=2 -E "$SHUTDOWN_MESSAGE_PATTERN" "$LOGFILE" || true)
if [[ "2" == "$NUM_SHUTDOWN_PATTERNS" ]]
then
  # This error will be picked up by the sbt output checker
  echo "ERROR - not splitting the log-files, as there are two or more shutdown messages. See:"
  grep "$SHUTDOWN_MESSAGE_PATTERN" "$LOGFILE"
  exit 2
fi

# Find the line number of the shutdown message (first and only match).
# This avoids ever making a second copy of the (potentially large) "before"
# half of the log file. The previous approach wrote both halves out with sed
# while the original still existed (peak disk usage ~2x). Here we only ever
# *write* the small "after" half; the large "before" half is produced by
# renaming the original in place, so it is never allocated twice on disk.
#
# We assume "$LOGFILE_BEFORE" and "$LOGFILE_AFTER" do not exist beforehand.
SHUTDOWN_LINE=$(grep -n -m1 "$SHUTDOWN_MESSAGE_PATTERN" "$LOGFILE" | cut -d: -f1 || true)

if [[ -z "$SHUTDOWN_LINE" ]]
then
  touch "$LOGFILE_AFTER"
  mv "$LOGFILE" "$LOGFILE_BEFORE"
  exit 0
fi

# Write the shutdown log line and everything after it to the "after" file.
# This is typically the small half of the split and the only data we copy.
tail -n +"$SHUTDOWN_LINE" "$LOGFILE" > "$LOGFILE_AFTER"

# Byte offset of the end of the shutdown line. `head` only reads the first
# SHUTDOWN_LINE lines, so this stays cheap even for huge files and does not
# allocate any new disk space.
BEFORE_BYTES=$(head -n "$SHUTDOWN_LINE" "$LOGFILE" | wc -c)

# Truncate the original in place (freeing the tail's disk space) so it contains
# only lines up to and including the shutdown line, then rename it into place.
# The large "before" half is never copied, only renamed.
truncate -s "$BEFORE_BYTES" "$LOGFILE"
mv "$LOGFILE" "$LOGFILE_BEFORE"
