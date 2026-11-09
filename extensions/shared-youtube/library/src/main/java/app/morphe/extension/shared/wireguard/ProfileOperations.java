// SPDX-License-Identifier: GPL-3.0-only
// See the included NOTICE file for additional GPLv3 Section 7 requirements.

package app.morphe.extension.shared.wireguard;

import android.content.Context;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;

/** Keeps import stages separate so failures never require exposing a document or key. */
final class ProfileOperations {
    private ProfileOperations() {
    }

    interface Source {
        @Nullable
        InputStream open() throws IOException;
    }

    static final class Failure extends Exception {
        final String resourceKey;
        @Nullable
        final String validationReasonKey;

        Failure(String resourceKey, @Nullable String validationReasonKey) {
            // Do not retain the original exception: provider/storage causes can contain secrets.
            super(resourceKey);
            this.resourceKey = resourceKey;
            this.validationReasonKey = validationReasonKey;
        }
    }

    static void importProfile(Context context, Source source) throws Failure {
        String config = read(source);
        validate(config);
        if (Thread.currentThread().isInterrupted()) return;
        try {
            WireGuardConfigStore.save(context, config);
        } catch (GeneralSecurityException | IOException | RuntimeException ex) {
            throw new Failure("morphe_wireguard_store_failed", null);
        }
    }

    static void validateStoredProfile(Context context) throws Failure {
        String config;
        try {
            config = WireGuardConfigStore.load(context);
        } catch (GeneralSecurityException | IOException | RuntimeException ex) {
            throw new Failure("morphe_wireguard_load_failed", null);
        }
        validate(config);
    }

    private static String read(Source source) throws Failure {
        try (InputStream input = source.open()) {
            return ConfigText.read(input);
        } catch (ConfigText.InvalidTextException ex) {
            String key = switch (ex.reason) {
                case TOO_LARGE -> "morphe_wireguard_import_too_large";
                case ENCODING -> "morphe_wireguard_import_encoding_failed";
                case CONTENT -> "morphe_wireguard_import_invalid_text";
            };
            throw new Failure(key, null);
        } catch (IOException | RuntimeException ex) {
            throw new Failure("morphe_wireguard_import_read_failed", null);
        }
    }

    private static void validate(String config) throws Failure {
        try {
            NativeTunnel.validate(config);
        } catch (NativeTunnel.InvalidProfileException ex) {
            // Native codes select known resources; never show arbitrary backend messages.
            throw new Failure("morphe_wireguard_validation_failed", validationReasonKey(ex.getMessage()));
        } catch (LinkageError ex) {
            // Includes the first failed load and later attempts in the same app process.
            throw new Failure("morphe_wireguard_native_failed", null);
        } catch (IllegalArgumentException ex) {
            // Conversion errors and unknown backend errors must not expose arbitrary messages.
            throw new Failure("morphe_wireguard_validation_failed", null);
        }
    }

    @Nullable
    private static String validationReasonKey(@Nullable String code) {
        if (code == null) return null;
        return switch (code) {
            case "invalid_text" -> "morphe_wireguard_error_invalid_text";
            case "invalid_interface" -> "morphe_wireguard_error_invalid_interface";
            case "invalid_peer" -> "morphe_wireguard_error_invalid_peer";
            case "unsupported_section" -> "morphe_wireguard_error_unsupported_section";
            case "invalid_directive" -> "morphe_wireguard_error_invalid_directive";
            case "duplicate_directive" -> "morphe_wireguard_error_duplicate_directive";
            case "invalid_address" -> "morphe_wireguard_error_invalid_address";
            case "duplicate_address" -> "morphe_wireguard_error_duplicate_address";
            case "invalid_dns" -> "morphe_wireguard_error_invalid_dns";
            case "invalid_mtu" -> "morphe_wireguard_error_invalid_mtu";
            case "invalid_endpoint" -> "morphe_wireguard_error_invalid_endpoint";
            case "invalid_routes" -> "morphe_wireguard_error_invalid_routes";
            case "unsupported_directive" -> "morphe_wireguard_error_unsupported_directive";
            case "invalid_value" -> "morphe_wireguard_error_invalid_value";
            case "line_too_long" -> "morphe_wireguard_error_line_too_long";
            case "missing_required" -> "morphe_wireguard_error_missing_required";
            case "ipv6_mtu" -> "morphe_wireguard_error_ipv6_mtu";
            case "dns_required" -> "morphe_wireguard_error_dns_required";
            case "dns_not_routed" -> "morphe_wireguard_error_dns_not_routed";
            default -> null;
        };
    }
}
