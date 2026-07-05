/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.io

import org.dexpace.sdk.core.io.IoProvider

/**
 * ServiceLoader-instantiable shim that delegates every [IoProvider] call to [OkioIoProvider].
 *
 * [OkioIoProvider] is a Kotlin `object` with a private constructor, so [java.util.ServiceLoader]
 * cannot instantiate it directly (ServiceLoader requires a public no-arg constructor). This class
 * provides that constructor while keeping [OkioIoProvider] as the real stateless implementation.
 *
 * Registered in `META-INF/services/org.dexpace.sdk.core.io.IoProvider`.
 *
 * Application code should not use this class directly; either call
 * `Io.installProvider(OkioIoProvider)` explicitly at startup, or rely on the automatic
 * ServiceLoader resolution built into [org.dexpace.sdk.core.io.Io].
 */
internal class OkioIoProviderLoader : IoProvider by OkioIoProvider
