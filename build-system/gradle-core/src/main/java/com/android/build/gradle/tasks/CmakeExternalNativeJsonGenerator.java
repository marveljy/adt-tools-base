/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.build.gradle.tasks;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.android.SdkConstants;
import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.build.gradle.internal.core.Abi;
import com.android.build.gradle.internal.ndk.NdkHandler;
import com.android.builder.core.AndroidBuilder;
import com.android.ide.common.process.ProcessInfoBuilder;
import com.android.repository.api.ConsoleProgressIndicator;
import com.android.repository.api.LocalPackage;
import com.android.repository.api.ProgressIndicator;
import com.android.sdklib.repository.AndroidSdkHandler;
import com.android.utils.FileUtils;
import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import org.gradle.api.GradleException;
import org.gradle.api.InvalidUserDataException;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * CMake JSON generation logic. This is separated from the corresponding CMake task so that
 * JSON can be generated during configuration.
 */
class CmakeExternalNativeJsonGenerator extends ExternalNativeJsonGenerator {

    CmakeExternalNativeJsonGenerator(
            @Nullable File sdkDirectory,
            @NonNull NdkHandler ndkHandler,
            int minSdkVersion,
            @NonNull String variantName,
            @NonNull List<Abi> abis,
            @NonNull AndroidBuilder androidBuilder,
            @NonNull File sdkFolder,
            @NonNull File ndkFolder,
            @NonNull File soFolder,
            @NonNull File objFolder,
            @NonNull File jsonFolder,
            @NonNull File makeFile,
            boolean debuggable,
            @Nullable List<String> buildArguments,
            @Nullable List<String> cFlags,
            @Nullable List<String> cppFlags,
            @NonNull List<File> nativeBuildConfigurationsJsons) {
        super(ndkHandler, minSdkVersion, variantName, abis, androidBuilder, sdkFolder, ndkFolder,
                soFolder, objFolder, jsonFolder, makeFile, debuggable,
                buildArguments, cFlags, cppFlags, nativeBuildConfigurationsJsons);
        checkNotNull(sdkDirectory);
        File cmakeExecutable = getCmakeExecutable();
        if (!cmakeExecutable.exists()) {
            // throw InvalidUserDataException directly for "Failed to find CMake" error. Android
            // Studio doesn't doesn't currently produce Quick Fix UI for SyncIssues.
            throw new InvalidUserDataException(
                    String.format("Failed to find CMake.\n"
                            + "Install from Android Studio under File/Settings/"
                            + "Appearance & Behavior/System Settings/Android SDK/SDK Tools/CMake.\n"
                            + "Expected CMake executable at %s.", cmakeExecutable));
        }
    }

    @Override
    void processBuildOutput(@NonNull String buildOutput, @NonNull String abi,
            int abiPlatformVersion) throws IOException {
        // CMake doesn't need to process build output because it directly writes JSON file
        // to specified location.
    }

    @NonNull
    @Override
    ProcessInfoBuilder getProcessBuilder(@NonNull String abi, int abiPlatformVersion,
            @NonNull File outputJson) {
        checkConfiguration();
        ProcessInfoBuilder builder = new ProcessInfoBuilder();
        // CMake requires a folder. Trim the filename off.
        File cmakeListsFolder = getMakefile().getParentFile();

        builder.setExecutable(getCmakeExecutable());
        builder.addArgs(String.format("-H%s", cmakeListsFolder));
        builder.addArgs(String.format("-B%s", outputJson.getParentFile()));
        builder.addArgs("-GAndroid Gradle - Ninja");
        builder.addArgs(String.format("-DANDROID_ABI=%s", abi));
        builder.addArgs(String.format("-DANDROID_NDK=%s", getNdkFolder()));
        builder.addArgs(
                String.format("-DCMAKE_LIBRARY_OUTPUT_DIRECTORY=%s",
                        new File(getObjFolder(), abi)));
        builder.addArgs(
                String.format("-DCMAKE_BUILD_TYPE=%s", isDebuggable() ? "Debug" : "Release"));
        builder.addArgs(String.format("-DCMAKE_MAKE_PROGRAM=%s",
                getNinjaExecutable().getAbsolutePath()));
        builder.addArgs(String.format("-DCMAKE_TOOLCHAIN_FILE=%s",
                getToolChainFile().getAbsolutePath()));

        builder.addArgs(String.format("-DANDROID_NATIVE_API_LEVEL=%s", abiPlatformVersion));

        if (!getcFlags().isEmpty()) {
            builder.addArgs(String.format("-DCMAKE_C_FLAGS=%s", Joiner.on(" ").join(getcFlags())));
        }

        if (!getCppFlags().isEmpty()) {
            builder.addArgs(String.format("-DCMAKE_CXX_FLAGS=%s",
                    Joiner.on(" ").join(getCppFlags())));
        }

        for (String argument : getBuildArguments()) {
            builder.addArgs(argument);
        }

        return builder;
    }

    @NonNull
    @Override
    public NativeBuildSystem getNativeBuildSystem() {
        return NativeBuildSystem.CMAKE;
    }

    @NonNull
    @Override
    Map<Abi, File> getStlSharedObjectFiles() {
        // Search for ANDROID_STL build argument. Process in order / later flags take precedent.
        String stl = null;
        File ndkBasePath = null;
        for (String argument : getBuildArguments()) {
            argument = argument.replace(" ", "");
            if (argument.equals("-DANDROID_STL=stlport_shared")) {
                stl = "stlport";
                ndkBasePath = FileUtils.join(getNdkFolder(), "sources", "cxx-stl", "stlport");
            } else if (argument.equals("-DANDROID_STL=gnustl_shared")) {
                stl = "gnustl";
                ndkBasePath = FileUtils.join(getNdkFolder(), "sources", "cxx-stl", "gnu-libstdc++",
                        "4.9");
            } else if (argument.equals("-DANDROID_STL=c++_shared")) {
                stl = "c++";
                ndkBasePath = FileUtils.join(getNdkFolder(), "sources", "cxx-stl", "llvm-libc++");
            }
        }
        Map<Abi, File> result = Maps.newHashMap();
        if (stl == null) {
            return result;
        }
        for (Abi abi : getAbis()) {
            File file = FileUtils.join(ndkBasePath, "libs", abi.getName(),
                    String.format("lib%s_shared.so", stl));
            checkState(file.isFile(), "Expected NDK STL shared object file at %s", file.toString());
            result.put(abi, file);
        }
        return result;
    }

    @NonNull
    private File getToolChainFile() {
        String toolchainFileName = "android.toolchain.cmake";
        File ndkCmakeFolder = new File(new File(getNdkFolder(), "build"), "cmake");
        // Toolchain file should be located at ndk/build/cmake/ for NDK r13+.
        File toolchainFile = new File(ndkCmakeFolder, toolchainFileName);
        if (!toolchainFile.exists()) {
            // Toolchain file for NDK r12 is in the SDK.
            // TODO: remove this when we stop caring about r12.
            toolchainFile = new File(getCmakeFolder(), toolchainFileName);
        }
        return toolchainFile;
    }

    @NonNull
    private File getCmakeFolder() {
        ProgressIndicator progress = new ConsoleProgressIndicator();
        AndroidSdkHandler sdk = AndroidSdkHandler.getInstance(getSdkFolder());
        LocalPackage cmakePackage = sdk.getLatestLocalPackageForPrefix(SdkConstants.FD_CMAKE, true,
                progress);
        if (cmakePackage != null) {
            return cmakePackage.getLocation();
        }
        return new File(getSdkFolder(), SdkConstants.FD_CMAKE);
    }

    @NonNull
    private File getCmakeBinFolder() {
        return new File(getCmakeFolder(), "bin");
    }

    @NonNull
    private File getCmakeExecutable() {
        if (isWindows()) {
            return new File(getCmakeBinFolder(), "cmake.exe");
        }
        return new File(getCmakeBinFolder(), "cmake");
    }

    @NonNull
    private File getNinjaExecutable() {
        if (isWindows()) {
            return new File(getCmakeBinFolder(), "ninja.exe");
        }
        return new File(getCmakeBinFolder(), "ninja");
    }

    /**
     * Check whether the configuration looks good enough to generate JSON files and expect that
     * the result will be valid.
     */
    private void checkConfiguration() {
        List<String> configurationErrors = getConfigurationErrors();
        if (!configurationErrors.isEmpty()) {
            throw new GradleException(Joiner.on("\n").join(configurationErrors));
        }
    }
  
    /**
     * Construct list of errors that can be known at configuration time.
     */
    @NonNull
    private List<String> getConfigurationErrors() {
        List<String> messages = Lists.newArrayList();

        String cmakeListsTxt = "CMakeLists.txt";
        if (getMakefile().isDirectory()) {
            messages.add(
                    String.format("Gradle project cmake.path %s is a folder. "
                                    + "It must be %s",
                            getMakefile(),
                            cmakeListsTxt));
        } else if (getMakefile().isFile()) {
            String filename = getMakefile().getName();
            if (!filename.equals(cmakeListsTxt)) {
                messages.add(String.format(
                        "Gradle project cmake.path specifies %s but it must be %s",
                        filename,
                        cmakeListsTxt));
            }
        } else {
            messages.add(
                    String.format(
                            "Gradle project cmake.path is %s but that file doesn't exist",
                            getMakefile()));
        }
        messages.addAll(getBaseConfigurationErrors());
        return messages;
    }

}
