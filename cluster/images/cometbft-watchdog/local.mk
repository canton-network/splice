# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# TODO (#8263): remove this image, CometBFT is no longer used
dir := $(call current_dir)

$(dir)/$(docker-build): $(dir)/restart-watchdog.py $(dir)/target/LICENSE

$(dir)/target/LICENSE: ${SPLICE_ROOT}/LICENSE | $(dir)/target
	cp $< $@
