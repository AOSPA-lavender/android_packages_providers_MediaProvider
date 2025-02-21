/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.providers.media.photopicker.v2;

import static java.util.Objects.requireNonNull;

import android.content.Context;
import android.content.UriMatcher;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

public class PickerUriResolverV2 {
    public static final String PICKER_INTERNAL_PATH_SEGMENT = "picker_internal";
    public static final String PICKER_V2_PATH_SEGMENT = "v2";
    public static final String BASE_PICKER_PATH =
            PICKER_INTERNAL_PATH_SEGMENT + "/" + PICKER_V2_PATH_SEGMENT + "/";
    public static final String AVAILABLE_PROVIDERS_PATH_SEGMENT = "available_providers";
    public static final String MEDIA_PATH_SEGMENT = "media";
    public static final String ALBUM_PATH_SEGMENT = "album";
    public static final String UPDATE_PATH_SEGMENT = "update";

    static final UriMatcher sUriMatcher = new UriMatcher(UriMatcher.NO_MATCH);
    static final int PICKER_INTERNAL_MEDIA = 1;
    static final int PICKER_INTERNAL_ALBUM = 2;
    static final int PICKER_INTERNAL_ALBUM_CONTENT = 3;
    static final int PICKER_INTERNAL_AVAILABLE_PROVIDERS = 4;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            UriMatcher.NO_MATCH,
            PICKER_INTERNAL_MEDIA,
            PICKER_INTERNAL_ALBUM,
            PICKER_INTERNAL_ALBUM_CONTENT,
            PICKER_INTERNAL_AVAILABLE_PROVIDERS,
    })
    private @interface PickerQuery {}

    static {
        sUriMatcher.addURI(MediaStore.AUTHORITY, BASE_PICKER_PATH + MEDIA_PATH_SEGMENT,
                PICKER_INTERNAL_MEDIA);
        sUriMatcher.addURI(MediaStore.AUTHORITY, BASE_PICKER_PATH + ALBUM_PATH_SEGMENT,
                PICKER_INTERNAL_ALBUM);
        sUriMatcher.addURI(
                MediaStore.AUTHORITY,
                BASE_PICKER_PATH + ALBUM_PATH_SEGMENT + "/*",
                PICKER_INTERNAL_ALBUM_CONTENT
        );
        sUriMatcher.addURI(
                MediaStore.AUTHORITY,
                BASE_PICKER_PATH + AVAILABLE_PROVIDERS_PATH_SEGMENT,
                PICKER_INTERNAL_AVAILABLE_PROVIDERS
        );
    }

    /**
     * Redirect a Picker internal query to the right {@link PickerDataLayerV2} method to serve the
     * request.
     */
    @Nullable
    public static Cursor query(
            @NonNull Context appContext,
            @NonNull Uri uri,
            @Nullable Bundle queryArgs) {
        @PickerQuery
        final int query = sUriMatcher.match(uri);

        switch (query) {
            case PICKER_INTERNAL_MEDIA:
                return PickerDataLayerV2.queryMedia(appContext, requireNonNull(queryArgs));
            case PICKER_INTERNAL_ALBUM:
                return PickerDataLayerV2.queryAlbums(appContext, requireNonNull(queryArgs));
            case PICKER_INTERNAL_ALBUM_CONTENT:
                final String albumId = uri.getLastPathSegment();
                return PickerDataLayerV2.queryAlbumMedia(
                        appContext,
                        requireNonNull(queryArgs),
                        requireNonNull(albumId));
            case PICKER_INTERNAL_AVAILABLE_PROVIDERS:
                return PickerDataLayerV2.queryAvailableProviders(appContext);
            default:
                throw new UnsupportedOperationException("Could not recognize content URI " + uri);
        }
    }
}
