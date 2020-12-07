/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.providers.media.transcode;

import static androidx.test.InstrumentationRegistry.getContext;

import static com.android.providers.media.transcode.TranscodeTestUtils.assertFileContent;
import static com.android.providers.media.transcode.TranscodeTestUtils.assertTranscode;
import static com.android.providers.media.transcode.TranscodeTestUtils.installAppWithStoragePermissions;
import static com.android.providers.media.transcode.TranscodeTestUtils.open;
import static com.android.providers.media.transcode.TranscodeTestUtils.openFileAs;
import static com.android.providers.media.transcode.TranscodeTestUtils.uninstallApp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.ContentResolver;
import android.media.ApplicationMediaCapabilities;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.system.Os;

import androidx.test.runner.AndroidJUnit4;

import com.android.cts.install.lib.TestApp;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class TranscodeTest {
    private static final File EXTERNAL_STORAGE_DIRECTORY
            = Environment.getExternalStorageDirectory();
    private static final File DIR_CAMERA
            = new File(EXTERNAL_STORAGE_DIRECTORY, Environment.DIRECTORY_DCIM + "/Camera");
    // TODO(b/169546642): Test other directories like /sdcard and /sdcard/foo
    // These are the only transcode unsupported directories we can stage files in given our
    // test app permissions
    private static final File[] DIRS_NO_TRANSCODE = {
        new File(EXTERNAL_STORAGE_DIRECTORY, Environment.DIRECTORY_PICTURES),
        new File(EXTERNAL_STORAGE_DIRECTORY, Environment.DIRECTORY_MOVIES),
        new File(EXTERNAL_STORAGE_DIRECTORY, Environment.DIRECTORY_DOWNLOADS),
        new File(EXTERNAL_STORAGE_DIRECTORY, Environment.DIRECTORY_DCIM),
        new File(EXTERNAL_STORAGE_DIRECTORY, Environment.DIRECTORY_DOCUMENTS),
    };

    static final String NONCE = String.valueOf(System.nanoTime());
    private static final String HEVC_FILE_NAME = "TranscodeTestHEVC_" + NONCE + ".mp4";

    private static final TestApp TEST_APP_HEVC = new TestApp("TestAppHevc",
            "com.android.providers.media.transcode.testapp", 1, false,
            "TranscodeTestAppSupportsHevc.apk");

    private static final TestApp TEST_APP_SLOW_MOTION = new TestApp("TestAppSlowMotion",
            "com.android.providers.media.transcode.testapp", 1, false,
            "TranscodeTestAppSupportsSlowMotion.apk");

    @Before
    public void setUp() throws Exception {
        TranscodeTestUtils.pollForExternalStorageState();
        TranscodeTestUtils.grantPermission(getContext().getPackageName(),
                Manifest.permission.READ_EXTERNAL_STORAGE);
        TranscodeTestUtils.pollForPermission(Manifest.permission.READ_EXTERNAL_STORAGE, true);
        TranscodeTestUtils.enableSeamlessTranscoding();
        TranscodeTestUtils.disableTranscodingForAllUids();
    }

    @After
    public void tearDown() throws Exception {
        TranscodeTestUtils.disableSeamlessTranscoding();
    }

    /**
     * Tests that we return FD of transcoded file for legacy apps
     * @throws Exception
     */
    @Test
    public void testTranscoded_FilePath() throws Exception {
        File modernFile = new File(DIR_CAMERA, HEVC_FILE_NAME);
        try {
            TranscodeTestUtils.stageHEVCVideoFile(modernFile);

            ParcelFileDescriptor pfdOriginal = open(modernFile, false);

            TranscodeTestUtils.enableTranscodingForUid(Os.getuid());
            ParcelFileDescriptor pfdTranscoded = open(modernFile, false);

            assertFileContent(modernFile, modernFile, pfdOriginal, pfdTranscoded, false);
        } finally {
            modernFile.delete();
        }
    }

    /**
     * Tests that we don't transcode files outside DCIM/Camera
     * @throws Exception
     */
    @Test
    public void testNoTranscodeOutsideCamera_FilePath() throws Exception {
        File modernFile = new File(DIR_CAMERA, HEVC_FILE_NAME);
        List<File> noTranscodeFiles = new ArrayList<>();
        for (File file : DIRS_NO_TRANSCODE) {
            noTranscodeFiles.add(new File(file, HEVC_FILE_NAME));
        }
        noTranscodeFiles.add(new File(getContext().getExternalFilesDir(null), HEVC_FILE_NAME));

        try {
            TranscodeTestUtils.stageHEVCVideoFile(modernFile);
            for (File file : noTranscodeFiles) {
                TranscodeTestUtils.stageHEVCVideoFile(file);
            }
            ParcelFileDescriptor pfdOriginal1 = open(modernFile, false);

            TranscodeTestUtils.enableTranscodingForUid(Os.getuid());

            for (File file : noTranscodeFiles) {
                pfdOriginal1.seekTo(0);
                ParcelFileDescriptor pfdOriginal2 = open(file, false);
                assertFileContent(modernFile, file, pfdOriginal1, pfdOriginal2, true);
            }
        } finally {
            modernFile.delete();
            for (File file : noTranscodeFiles) {
                file.delete();
            }
        }
    }

    /**
     * Tests that same transcoded file is used for multiple open() from same app
     * @throws Exception
     */
    @Test
    public void testSameTranscoded_FilePath() throws Exception {
        File modernFile = new File(DIR_CAMERA, HEVC_FILE_NAME);
        try {
            TranscodeTestUtils.stageHEVCVideoFile(modernFile);

            TranscodeTestUtils.enableTranscodingForUid(Os.getuid());
            ParcelFileDescriptor pfdTranscoded1 = open(modernFile, false);
            ParcelFileDescriptor pfdTranscoded2 = open(modernFile, false);

            assertFileContent(modernFile, modernFile, pfdTranscoded1, pfdTranscoded2, true);
        } finally {
            modernFile.delete();
        }
    }

    /**
     * Tests that we return FD of transcoded file for legacy apps
     * @throws Exception
     */
    @Test
    public void testTranscoded_ContentResolver() throws Exception {
        File modernFile = new File(DIR_CAMERA, HEVC_FILE_NAME);
        try {
            Uri uri = TranscodeTestUtils.stageHEVCVideoFile(modernFile);

            ParcelFileDescriptor pfdOriginal = open(uri, false, null /* bundle */);

            TranscodeTestUtils.enableTranscodingForUid(Os.getuid());

            ParcelFileDescriptor pfdTranscoded = open(uri, false, null /* bundle */);

            assertFileContent(modernFile, modernFile, pfdOriginal, pfdTranscoded, false);
        } finally {
            modernFile.delete();
        }
    }

    /**
     * Tests that we don't transcode files outside DCIM/Camera
     * @throws Exception
     */
    @Test
    public void testNoTranscodeOutsideCamera_ConentResolver() throws Exception {
        File modernFile = new File(DIR_CAMERA, HEVC_FILE_NAME);
        List<File> noTranscodeFiles = new ArrayList<>();
        for (File file : DIRS_NO_TRANSCODE) {
            noTranscodeFiles.add(new File(file, HEVC_FILE_NAME));
        }

        try {
            Uri uri = TranscodeTestUtils.stageHEVCVideoFile(modernFile);
            ArrayList<Uri> noTranscodeUris = new ArrayList<>();
            for (File file : noTranscodeFiles) {
                noTranscodeUris.add(TranscodeTestUtils.stageHEVCVideoFile(file));
            }

            ParcelFileDescriptor pfdOriginal1 = open(uri, false, null /* bundle */);

            TranscodeTestUtils.enableTranscodingForUid(Os.getuid());

            for (int i = 0; i < noTranscodeUris.size(); i++) {
                pfdOriginal1.seekTo(0);
                ParcelFileDescriptor pfdOriginal2 =
                        open(noTranscodeUris.get(i), false, null /* bundle */);
                assertFileContent(modernFile, noTranscodeFiles.get(1), pfdOriginal1, pfdOriginal2,
                        true);
            }
        } finally {
            modernFile.delete();
            for (File file : noTranscodeFiles) {
                file.delete();
            }
        }
    }

    /**
     * Tests that same transcoded file is used for multiple open() from same app
     * @throws Exception
     */
    @Test
    public void testSameTranscodedFile_ContentResolver() throws Exception {
        File modernFile = new File(DIR_CAMERA, HEVC_FILE_NAME);
        try {
            Uri uri = TranscodeTestUtils.stageHEVCVideoFile(modernFile);

            TranscodeTestUtils.enableTranscodingForUid(Os.getuid());

            ParcelFileDescriptor pfdTranscoded1 = open(uri, false, null /* bundle */);
            ParcelFileDescriptor pfdTranscoded2 = open(uri, false, null /* bundle */);

            assertFileContent(modernFile, modernFile, pfdTranscoded1, pfdTranscoded2, true);
        } finally {
            modernFile.delete();
        }
    }

    /**
     * Tests that deletes are visible across legacy and modern apps
     * @throws Exception
     */
    @Test
    public void testDeleteTranscodedFile_FilePath() throws Exception {
        File modernFile = new File(DIR_CAMERA, HEVC_FILE_NAME);
        try {
            TranscodeTestUtils.stageHEVCVideoFile(modernFile);

            TranscodeTestUtils.enableTranscodingForUid(Os.getuid());

            assertTrue(modernFile.delete());
            assertFalse(modernFile.exists());

            TranscodeTestUtils.disableTranscodingForAllUids();

            assertFalse(modernFile.exists());
        } finally {
            modernFile.delete();
        }
    }

    /**
     * Tests that renames are visible across legacy and modern apps
     * @throws Exception
     */
    @Test
    public void testRenameTranscodedFile_FilePath() throws Exception {
        File modernFile = new File(DIR_CAMERA, HEVC_FILE_NAME);
        File destFile = new File(DIR_CAMERA, "renamed_" + HEVC_FILE_NAME);
        try {
            TranscodeTestUtils.stageHEVCVideoFile(modernFile);

            TranscodeTestUtils.enableTranscodingForUid(Os.getuid());

            assertTrue(modernFile.renameTo(destFile));
            assertTrue(destFile.exists());
            assertFalse(modernFile.exists());

            TranscodeTestUtils.disableTranscodingForAllUids();

            assertTrue(destFile.exists());
            assertFalse(modernFile.exists());
        } finally {
            modernFile.delete();
            destFile.delete();
        }
    }

    /**
     * Tests that transcode doesn't start until read(2)
     * @throws Exception
     */
    @Test
    public void testLazyTranscodedFile_FilePath() throws Exception {
        File modernFile = new File(DIR_CAMERA, HEVC_FILE_NAME);
        try {
            TranscodeTestUtils.stageHEVCVideoFile(modernFile);

            assertTranscode(modernFile, false);

            TranscodeTestUtils.enableTranscodingForUid(Os.getuid());

            assertTranscode(modernFile, true);
        } finally {
            modernFile.delete();
        }
    }

    /**
     * Tests that transcode cache is reused after file path transcode
     * @throws Exception
     */
    @Test
    public void testTranscodedCacheReuse_FilePath() throws Exception {
        File modernFile = new File(DIR_CAMERA, HEVC_FILE_NAME);
        try {
            TranscodeTestUtils.stageHEVCVideoFile(modernFile);
            TranscodeTestUtils.enableTranscodingForUid(Os.getuid());

            assertTranscode(modernFile, true);
            assertTranscode(modernFile, false);
        } finally {
            modernFile.delete();
        }
    }

    /**
     * Tests that transcode cache is reused after ContentResolver transcode
     * @throws Exception
     */
    @Ignore("b/174655855")
    @Test
    public void testTranscodedCacheReuse_ContentResolver() throws Exception {
        File modernFile = new File(DIR_CAMERA, HEVC_FILE_NAME);
        try {
            Uri uri = TranscodeTestUtils.stageHEVCVideoFile(modernFile);
            TranscodeTestUtils.enableTranscodingForUid(Os.getuid());

            assertTranscode(uri, true);
            assertTranscode(uri, false);
        } finally {
            modernFile.delete();
        }
    }

    /**
     * Tests that transcode cache is reused after ContentResolver transcode
     * and file path opens
     * @throws Exception
     */
    @Ignore("b/174655855")
    @Test
    public void testTranscodedCacheReuse_ContentResolverFilePath() throws Exception {
        File modernFile = new File(DIR_CAMERA, HEVC_FILE_NAME);
        try {
            Uri uri = TranscodeTestUtils.stageHEVCVideoFile(modernFile);
            TranscodeTestUtils.enableTranscodingForUid(Os.getuid());

            assertTranscode(uri, true);
            assertTranscode(modernFile, false);
        } finally {
            modernFile.delete();
        }
    }

    /**
     * Tests that transcode cache is reused after file path transcode
     * and ContentResolver opens
     * @throws Exception
     */
    @Test
    public void testTranscodedCacheReuse_FilePathContentResolver() throws Exception {
        File modernFile = new File(DIR_CAMERA, HEVC_FILE_NAME);
        try {
            Uri uri = TranscodeTestUtils.stageHEVCVideoFile(modernFile);
            TranscodeTestUtils.enableTranscodingForUid(Os.getuid());

            assertTranscode(modernFile, true);
            assertTranscode(uri, false);
        } finally {
            modernFile.delete();
        }
    }

    /**
     * Tests that transcode cache is reused after rename
     * @throws Exception
     */
    @Test
    public void testTranscodedCacheReuseAfterRename_FilePath() throws Exception {
        File modernFile = new File(DIR_CAMERA, HEVC_FILE_NAME);
        File destFile = new File(DIR_CAMERA, "renamed_" + HEVC_FILE_NAME);
        try {
            TranscodeTestUtils.stageHEVCVideoFile(modernFile);
            TranscodeTestUtils.enableTranscodingForUid(Os.getuid());

            assertTranscode(modernFile, true);

            assertTrue(modernFile.renameTo(destFile));

            assertTranscode(destFile, false);
        } finally {
            modernFile.delete();
            destFile.delete();
        }
    }

    @Test
    public void testExtraAcceptOriginalFormatTrue_ContentResolver() throws Exception {
        File modernFile = new File(DIR_CAMERA, HEVC_FILE_NAME);
        try {
            Uri uri = TranscodeTestUtils.stageHEVCVideoFile(modernFile);

            ParcelFileDescriptor pfdOriginal1 = open(uri, false, null /* bundle */);

            TranscodeTestUtils.enableTranscodingForUid(Os.getuid());

            Bundle bundle = new Bundle();
            bundle.putBoolean(MediaStore.EXTRA_ACCEPT_ORIGINAL_MEDIA_FORMAT, true);
            ParcelFileDescriptor pfdOriginal2 = open(uri, false, bundle);

            assertFileContent(modernFile, modernFile, pfdOriginal1, pfdOriginal2, true);
        } finally {
            modernFile.delete();
        }
    }

    @Test
    public void testExtraAcceptOriginalFormatFalse_ContentResolver() throws Exception {
        File modernFile = new File(DIR_CAMERA, HEVC_FILE_NAME);
        try {
            Uri uri = TranscodeTestUtils.stageHEVCVideoFile(modernFile);

            ParcelFileDescriptor pfdOriginal = open(uri, false, null /* bundle */);

            TranscodeTestUtils.enableTranscodingForUid(Os.getuid());

            Bundle bundle = new Bundle();
            bundle.putBoolean(MediaStore.EXTRA_ACCEPT_ORIGINAL_MEDIA_FORMAT, false);
            ParcelFileDescriptor pfdTranscoded = open(uri, false, bundle);

            assertFileContent(modernFile, modernFile, pfdOriginal, pfdTranscoded, false);
        } finally {
            modernFile.delete();
        }
    }

    @Test
    public void testExtraMediaCapabilitiesHevcTrue_ContentResolver() throws Exception {
        File modernFile = new File(DIR_CAMERA, HEVC_FILE_NAME);
        try {
            Uri uri = TranscodeTestUtils.stageHEVCVideoFile(modernFile);

            ParcelFileDescriptor pfdOriginal1 = open(uri, false, null /* bundle */);

            TranscodeTestUtils.enableTranscodingForUid(Os.getuid());

            Bundle bundle = new Bundle();
            ApplicationMediaCapabilities capabilities =
                    new ApplicationMediaCapabilities.Builder()
                    .addSupportedVideoMimeType(MediaFormat.MIMETYPE_VIDEO_HEVC).build();
            bundle.putParcelable(MediaStore.EXTRA_MEDIA_CAPABILITIES, capabilities);
            ParcelFileDescriptor pfdOriginal2 = open(uri, false, bundle);

            assertFileContent(modernFile, modernFile, pfdOriginal1, pfdOriginal2, true);
        } finally {
            modernFile.delete();
        }
    }

    @Test
    public void testExtraMediaCapabilitiesHevcFalse_ContentResolver() throws Exception {
        File modernFile = new File(DIR_CAMERA, HEVC_FILE_NAME);
        try {
            Uri uri = TranscodeTestUtils.stageHEVCVideoFile(modernFile);

            ParcelFileDescriptor pfdOriginal1 = open(uri, false, null /* bundle */);

            TranscodeTestUtils.enableTranscodingForUid(Os.getuid());

            Bundle bundle = new Bundle();
            ApplicationMediaCapabilities capabilities =
                    new ApplicationMediaCapabilities.Builder().build();
            bundle.putParcelable(MediaStore.EXTRA_MEDIA_CAPABILITIES, capabilities);
            ParcelFileDescriptor pfdTranscoded = open(uri, false, bundle);

            assertFileContent(modernFile, modernFile, pfdOriginal1, pfdTranscoded, false);
        } finally {
            modernFile.delete();
        }
    }

    @Test
    public void testExtraAcceptOriginalTrueAndMediaCapabilitiesHevcFalse_ContentResolver()
            throws Exception {
        File modernFile = new File(DIR_CAMERA, HEVC_FILE_NAME);
        try {
            Uri uri = TranscodeTestUtils.stageHEVCVideoFile(modernFile);

            ParcelFileDescriptor pfdOriginal1 = open(uri, false, null /* bundle */);

            TranscodeTestUtils.enableTranscodingForUid(Os.getuid());

            Bundle bundle = new Bundle();
            ApplicationMediaCapabilities capabilities =
                    new ApplicationMediaCapabilities.Builder().build();
            bundle.putParcelable(MediaStore.EXTRA_MEDIA_CAPABILITIES, capabilities);
            bundle.putBoolean(MediaStore.EXTRA_ACCEPT_ORIGINAL_MEDIA_FORMAT, true);
            ParcelFileDescriptor pfdOriginal2 = open(uri, false, bundle);

            assertFileContent(modernFile, modernFile, pfdOriginal1, pfdOriginal2, true);
        } finally {
            modernFile.delete();
        }
    }

    @Test
    public void testMediaCapabilitiesManifestHevc()
            throws Exception {
        File modernFile = new File(DIR_CAMERA, HEVC_FILE_NAME);
        ParcelFileDescriptor pfdOriginal2 = null;
        try {
            installAppWithStoragePermissions(TEST_APP_HEVC);

            Uri uri = TranscodeTestUtils.stageHEVCVideoFile(modernFile);

            ParcelFileDescriptor pfdOriginal1 = open(modernFile, false);

            TranscodeTestUtils.enableTranscodingForPackage(TEST_APP_HEVC.getPackageName());

            pfdOriginal2 = openFileAs(TEST_APP_HEVC, modernFile);

            assertFileContent(modernFile, modernFile, pfdOriginal1, pfdOriginal2, true);
        } finally {
            // Explicitly close PFD otherwise instrumention might crash when test_app is uninstalled
            if (pfdOriginal2 != null) {
                pfdOriginal2.close();
            }
            modernFile.delete();
            uninstallApp(TEST_APP_HEVC);
        }
    }

    @Test
    public void testMediaCapabilitiesManifestSlowMotion()
            throws Exception {
        File modernFile = new File(DIR_CAMERA, HEVC_FILE_NAME);
        ParcelFileDescriptor pfdOriginal2 = null;
        try {
            installAppWithStoragePermissions(TEST_APP_SLOW_MOTION);

            Uri uri = TranscodeTestUtils.stageHEVCVideoFile(modernFile);

            ParcelFileDescriptor pfdOriginal1 = open(modernFile, false);

            TranscodeTestUtils.enableTranscodingForPackage(TEST_APP_SLOW_MOTION.getPackageName());

            pfdOriginal2 = openFileAs(TEST_APP_SLOW_MOTION, modernFile);

            assertFileContent(modernFile, modernFile, pfdOriginal1, pfdOriginal2, false);
        } finally {
            // Explicitly close PFD otherwise instrumention might crash when test_app is uninstalled
            if (pfdOriginal2 != null) {
                pfdOriginal2.close();
            }
            modernFile.delete();
            uninstallApp(TEST_APP_HEVC);
        }
    }

    @Test
    public void testAppCompatNoTranscodeHevc() throws Exception {
        File modernFile = new File(DIR_CAMERA, HEVC_FILE_NAME);
        String packageName = TEST_APP_SLOW_MOTION.getPackageName();
        ParcelFileDescriptor pfdOriginal2 = null;
        try {
            installAppWithStoragePermissions(TEST_APP_SLOW_MOTION);

            Uri uri = TranscodeTestUtils.stageHEVCVideoFile(modernFile);

            ParcelFileDescriptor pfdOriginal1 = open(modernFile, false);

            TranscodeTestUtils.enableTranscodingForPackage(packageName);
            // App compat takes precedence
            TranscodeTestUtils.forceEnableAppCompatHevc(packageName);

            Thread.sleep(2000);

            pfdOriginal2 = openFileAs(TEST_APP_SLOW_MOTION, modernFile);

            assertFileContent(modernFile, modernFile, pfdOriginal1, pfdOriginal2, true);
        } finally {
            // Explicitly close PFD otherwise instrumention might crash when test_app is uninstalled
            if (pfdOriginal2 != null) {
                pfdOriginal2.close();
            }
            modernFile.delete();
            TranscodeTestUtils.resetAppCompat(packageName);
            uninstallApp(TEST_APP_HEVC);
        }
    }

    @Test
    public void testAppCompatTranscodeHevc() throws Exception {
        File modernFile = new File(DIR_CAMERA, HEVC_FILE_NAME);
        String packageName = TEST_APP_SLOW_MOTION.getPackageName();
        ParcelFileDescriptor pfdOriginal2 = null;
        try {
            installAppWithStoragePermissions(TEST_APP_SLOW_MOTION);

            Uri uri = TranscodeTestUtils.stageHEVCVideoFile(modernFile);

            ParcelFileDescriptor pfdOriginal1 = open(modernFile, false);

            // Transcoding is disabled but app compat enables it (disables hevc support)
            TranscodeTestUtils.forceDisableAppCompatHevc(packageName);

            pfdOriginal2 = openFileAs(TEST_APP_SLOW_MOTION, modernFile);

            assertFileContent(modernFile, modernFile, pfdOriginal1, pfdOriginal2, false);
        } finally {
            // Explicitly close PFD otherwise instrumention might crash when test_app is uninstalled
            if (pfdOriginal2 != null) {
                pfdOriginal2.close();
            }
            modernFile.delete();
            TranscodeTestUtils.resetAppCompat(packageName);
            uninstallApp(TEST_APP_HEVC);
        }
    }
}
