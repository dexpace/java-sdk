/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.auth

/**
 * The precedence tier an [AuthDescriptor] occupies in the resolution ladder. Listed from
 * highest precedence to lowest; [AuthDescriptorResolver] consults a present descriptor in
 * this exact order and resolves against the first one that is supplied.
 */
public enum class AuthDescriptorTier {
    /** A descriptor attached to a single call, overriding everything below it. */
    PER_CALL,

    /** The descriptor declared by the operation being invoked. */
    OPERATION,

    /** The client-wide default descriptor, used when nothing more specific is supplied. */
    CLIENT,
}
