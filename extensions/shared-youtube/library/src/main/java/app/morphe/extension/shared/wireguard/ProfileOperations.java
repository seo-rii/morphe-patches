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
        final String validationReason;

        Failure(String resourceKey, @Nullable String validationReason) {
            // Do not retain the original exception: provider/storage causes can contain secrets.
            super(resourceKey);
            this.resourceKey = resourceKey;
            this.validationReason = validationReason;
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
            // Only this JNI exception contains the native parser's fixed, sanitized diagnostics.
            throw new Failure("morphe_wireguard_validation_failed", ex.getMessage());
        } catch (LinkageError ex) {
            // Includes the first failed load and later attempts in the same app process.
            throw new Failure("morphe_wireguard_native_failed", null);
        } catch (IllegalArgumentException ex) {
            // Conversion errors and unknown backend errors must not expose arbitrary messages.
            throw new Failure("morphe_wireguard_validation_failed", null);
        }
    }
}
