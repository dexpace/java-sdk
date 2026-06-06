/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.instrumentation

/**
 * Log levels used by [ClientLogger].
 *
 * Ordered from most-severe to most-verbose, matching the SLF4J convention
 * (`ERROR` > `WARN` > `INFO` > `DEBUG`). The SDK's `VERBOSE` maps to SLF4J `DEBUG`.
 */
public enum class LogLevel { ERROR, WARNING, INFO, VERBOSE }
