/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.build.gradle.managed;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;

import org.gradle.model.Managed;

import java.io.File;
import java.util.List;

/**
 * A source file for a native library.
 */
@Managed
public interface NativeSourceFolder {

    /**
     * The source folder.
     */
    @Nullable
    File getSrc();
    void setSrc(File src);

    /**
     * Compiler flags for all C files.
     *
     * Lowercase 'c' because otherwise it would produce CFlags instead of cFlags.
     */
    @Nullable
    String getcFlags();
    void setcFlags(String flags);

    /**
     * Compiler flags for all C++ files.
     */
    @Nullable
    String getCppFlags();
    void setCppFlags(String flags);

    /**
     * The working directory for the compiler.
     */
    @Nullable
    File getWorkingDirectory();
    void setWorkingDirectory(File dir);
}
