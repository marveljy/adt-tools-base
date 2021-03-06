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

package com.android.build.gradle.internal.transforms;

import com.android.SdkConstants;
import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.build.api.transform.DirectoryInput;
import com.android.build.api.transform.Format;
import com.android.build.api.transform.JarInput;
import com.android.build.api.transform.QualifiedContent;
import com.android.build.api.transform.QualifiedContent.ContentType;
import com.android.build.api.transform.QualifiedContent.Scope;
import com.android.build.api.transform.SecondaryFile;
import com.android.build.api.transform.Transform;
import com.android.build.api.transform.TransformException;
import com.android.build.api.transform.TransformInput;
import com.android.build.api.transform.TransformInvocation;
import com.android.build.api.transform.TransformOutputProvider;
import com.android.build.gradle.internal.LoggerWrapper;
import com.android.build.gradle.internal.incremental.InstantRunBuildContext;
import com.android.build.gradle.internal.incremental.InstantRunBuildContext.FileType;
import com.android.build.gradle.internal.pipeline.TransformManager;
import com.android.builder.core.AndroidBuilder;
import com.android.builder.core.DexOptions;
import com.android.builder.internal.utils.FileCache;
import com.android.builder.sdk.TargetInfo;
import com.android.ide.common.blame.Message;
import com.android.ide.common.blame.ParsingProcessOutputHandler;
import com.android.ide.common.blame.parser.DexParser;
import com.android.ide.common.blame.parser.ToolOutputParser;
import com.android.ide.common.internal.WaitableExecutor;
import com.android.ide.common.process.ProcessException;
import com.android.ide.common.process.ProcessOutputHandler;
import com.android.sdklib.BuildToolInfo;
import com.android.utils.FileUtils;
import com.android.utils.ILogger;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.hash.HashCode;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import com.google.common.io.Files;

import org.gradle.api.logging.Logger;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Dexing as a transform.
 *
 * This consumes all the available classes streams and creates a dex file (or more in the case of
 * multi-dex)
 *
 * This handles pre-dexing as well. If there are more than one stream, then only streams with
 * changed files will be re-dexed before a single merge phase is done at the end.
 * If there is a single input, then there's only a single dx phase.
 */
public class DexTransform extends Transform {

    @NonNull
    private final DexOptions dexOptions;

    private final boolean debugMode;

    private final boolean multiDex;

    @Nullable
    private final File mainDexListFile;

    @NonNull
    private final File intermediateFolder;

    @NonNull
    private final AndroidBuilder androidBuilder;

    @NonNull
    private final ILogger logger;

    private final InstantRunBuildContext instantRunBuildContext;

    @NonNull
    private final FileCache buildCache;

    public DexTransform(
            @NonNull DexOptions dexOptions,
            boolean debugMode,
            boolean multiDex,
            @Nullable File mainDexListFile,
            @NonNull File intermediateFolder,
            @NonNull AndroidBuilder androidBuilder,
            @NonNull Logger logger,
            @NonNull InstantRunBuildContext instantRunBuildContext,
            @NonNull FileCache buildCache) {
        this.dexOptions = dexOptions;
        this.debugMode = debugMode;
        this.multiDex = multiDex;
        this.mainDexListFile = mainDexListFile;
        this.intermediateFolder = intermediateFolder;
        this.androidBuilder = androidBuilder;
        this.logger = new LoggerWrapper(logger);
        this.instantRunBuildContext = instantRunBuildContext;
        this.buildCache = buildCache;
    }

    @NonNull
    @Override
    public String getName() {
        return "dex";
    }

    @NonNull
    @Override
    public Set<ContentType> getInputTypes() {
        return TransformManager.CONTENT_CLASS;
    }

    @NonNull
    @Override
    public Set<ContentType> getOutputTypes() {
        return TransformManager.CONTENT_DEX;
    }

    @NonNull
    @Override
    public Set<Scope> getScopes() {
        return TransformManager.SCOPE_FULL_PROJECT;
    }

    @NonNull
    @Override
    public Collection<SecondaryFile> getSecondaryFiles() {
        if (mainDexListFile != null) {
            return ImmutableList.of(SecondaryFile.nonIncremental(mainDexListFile));
        }

        return ImmutableList.of();
    }

    @NonNull
    @Override
    public Collection<File> getSecondaryDirectoryOutputs() {
        // we use the intermediate folder only if
        // - there's per-scope dexing
        // - there's no native multi-dex
        if (dexOptions.getPreDexLibraries() && !(multiDex && mainDexListFile == null)) {
            return ImmutableList.of(intermediateFolder);
        }

        return ImmutableList.of();
    }

    @NonNull
    @Override
    public Map<String, Object> getParameterInputs() {
        try {
            // ATTENTION: if you add something here, consider adding the value to DexKey - it needs
            // to be saved if affects how dx is invoked.

            Map<String, Object> params = Maps.newHashMapWithExpectedSize(4);

            params.put("optimize", getOptimize());
            params.put("predex", dexOptions.getPreDexLibraries());
            params.put("jumbo", dexOptions.getJumboMode());
            params.put("multidex", multiDex);
            params.put("multidex-legacy",  multiDex && mainDexListFile != null);
            params.put("java-max-heap-size", dexOptions.getJavaMaxHeapSize());
            params.put(
                    "additional-parameters",
                    Iterables.toString(dexOptions.getAdditionalParameters()));

            TargetInfo targetInfo = androidBuilder.getTargetInfo();
            Preconditions.checkState(targetInfo != null,
                    "androidBuilder.targetInfo required for task '%s'.", getName());
            BuildToolInfo buildTools = targetInfo.getBuildTools();
            params.put("build-tools", buildTools.getRevision().toString());

            return params;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean isIncremental() {
        return true;
    }

    @Override
    public void transform(@NonNull TransformInvocation transformInvocation)
            throws TransformException, IOException, InterruptedException {
        TransformOutputProvider outputProvider = transformInvocation.getOutputProvider();
        boolean isIncremental = transformInvocation.isIncremental();
        Preconditions.checkNotNull(outputProvider,
                "Missing output object for transform " + getName());

        // Gather a full list of all inputs.
        List<JarInput> jarInputs = Lists.newArrayList();
        List<DirectoryInput> directoryInputs = Lists.newArrayList();
        for (TransformInput input : transformInvocation.getInputs()) {
            jarInputs.addAll(input.getJarInputs());
            directoryInputs.addAll(input.getDirectoryInputs());
        }
        logger.info("Task is incremental : %b ", isIncremental);
        logger.info("JarInputs %s", Joiner.on(",").join(jarInputs));
        logger.info("DirInputs %s", Joiner.on(",").join(directoryInputs));

        if (!dexOptions.getKeepRuntimeAnnotatedClasses() && mainDexListFile != null) {
            logger.info("DexOptions.keepRuntimeAnnotatedClasses has no affect in native multidex.");
        }

        ProcessOutputHandler outputHandler = new ParsingProcessOutputHandler(
                new ToolOutputParser(new DexParser(), Message.Kind.ERROR, logger),
                new ToolOutputParser(new DexParser(), logger),
                androidBuilder.getErrorReporter());

        if (!isIncremental) {
            outputProvider.deleteAll();
        }
        try {
            // if only one scope or no per-scope dexing, just do a single pass that
            // runs dx on everything.
            if ((jarInputs.size() + directoryInputs.size()) == 1
                    || !dexOptions.getPreDexLibraries()) {
                File outputDir = outputProvider.getContentLocation("main",
                        getOutputTypes(), getScopes(),
                        Format.DIRECTORY);
                FileUtils.mkdirs(outputDir);

                // first delete the output folder where the final dex file(s) will be.
                FileUtils.cleanOutputDir(outputDir);

                // gather the inputs. This mode is always non incremental, so just
                // gather the top level folders/jars
                final List<File> inputFiles =
                        Stream.concat(
                                jarInputs.stream().map(JarInput::getFile),
                                directoryInputs.stream().map(DirectoryInput::getFile))
                        .collect(Collectors.toList());

                androidBuilder.convertByteCode(
                        inputFiles,
                        outputDir,
                        multiDex,
                        mainDexListFile,
                        dexOptions,
                        getOptimize(),
                        outputHandler);

                for (File file : Files.fileTreeTraverser().breadthFirstTraversal(outputDir)) {
                    if (file.isFile()) {
                        instantRunBuildContext.addChangedFile(FileType.DEX, file);
                    }
                }
            } else {
                // Figure out if we need to do a dx merge.
                // The ony case we don't need it is in native multi-dex mode when doing debug
                // builds. This saves build time at the expense of too many dex files which is fine.
                // FIXME dx cannot receive dex files to merge inside a folder. They have to be in a
                // jar. Need to fix in dx.
                boolean needMerge = !multiDex || mainDexListFile != null;// || !debugMode;

                // where we write the pre-dex depends on whether we do the merge after.
                // If needMerge changed from one build to another, we'll be in non incremental
                // mode, so we don't have to deal with changing folder in incremental mode.
                File perStreamDexFolder = null;
                if (needMerge) {
                    perStreamDexFolder = intermediateFolder;

                    if (!isIncremental) {
                        FileUtils.deletePath(perStreamDexFolder);
                    }
                }

                // dex all the different streams separately, then merge later (maybe)
                // hash to detect duplicate jars (due to isse with library and tests)
                final Set<String> hashs = Sets.newHashSet();
                // input files to output file map
                final Map<File, File> inputFiles = Maps.newHashMap();
                // set of input files that are external libraries
                final Set<File> externalLibs = Sets.newHashSet();
                // stuff to delete. Might be folders.
                final List<File> deletedFiles = Lists.newArrayList();

                // first gather the different inputs to be dexed separately.
                for (DirectoryInput directoryInput : directoryInputs) {
                    File rootFolder = directoryInput.getFile();
                    // The incremental mode only detect file level changes.
                    // It does not handle removed root folders. However the transform
                    // task will add the TransformInput right after it's removed so that it
                    // can be detected by the transform.
                    if (!rootFolder.exists()) {
                        // if the root folder is gone we need to remove the previous
                        // output
                        File preDexedFile = getPreDexFile(
                                outputProvider, needMerge, perStreamDexFolder, directoryInput);
                        if (preDexedFile.exists()) {
                            deletedFiles.add(preDexedFile);
                        }
                    } else if (!isIncremental || !directoryInput.getChangedFiles().isEmpty()) {
                        // add the folder for re-dexing only if we're not in incremental
                        // mode or if it contains changed files.
                        logger.info("Changed file for %s are %s",
                                directoryInput.getFile().getAbsolutePath(),
                                Joiner.on(",").join(directoryInput.getChangedFiles().entrySet()));
                        File preDexFile = getPreDexFile(
                                outputProvider, needMerge, perStreamDexFolder, directoryInput);
                        inputFiles.put(rootFolder, preDexFile);
                        if (isExternalLibrary(directoryInput)) {
                            externalLibs.add(rootFolder);
                        }
                    }
                }

                for (JarInput jarInput : jarInputs) {
                    switch (jarInput.getStatus()) {
                        case NOTCHANGED:
                            if (isIncremental) {
                                break;
                            }
                            // intended fall-through
                        case CHANGED:
                        case ADDED: {
                            File preDexFile = getPreDexFile(
                                    outputProvider, needMerge, perStreamDexFolder, jarInput);
                            inputFiles.put(jarInput.getFile(), preDexFile);
                            if (isExternalLibrary(jarInput)) {
                                externalLibs.add(jarInput.getFile());
                            }
                            break;
                        }
                        case REMOVED: {
                            File preDexedFile = getPreDexFile(
                                    outputProvider, needMerge, perStreamDexFolder, jarInput);
                            if (preDexedFile.exists()) {
                                deletedFiles.add(preDexedFile);
                            }
                            break;
                        }
                    }
                }

                logger.info("inputFiles : %s", Joiner.on(",").join(inputFiles.entrySet()));
                logger.info("externalLibs %s: ", Joiner.on(",").join(externalLibs));

                WaitableExecutor<Void> executor = WaitableExecutor.useGlobalSharedThreadPool();

                for (Map.Entry<File, File> entry : inputFiles.entrySet()) {
                    Callable<Void> action = new PreDexTask(
                            entry.getKey(),
                            entry.getValue(),
                            hashs,
                            outputHandler,
                            externalLibs.contains(entry.getKey()) ? buildCache : FileCache.NO_CACHE);
                    logger.info("Adding PreDexTask for %s : %s", entry.getKey(), action);
                    executor.execute(action);
                }

                for (final File file : deletedFiles) {
                    executor.execute(() -> {
                        FileUtils.deletePath(file);
                        return null;
                    });
                }

                executor.waitForTasksWithQuickFail(false);
                logger.info("Done with all dexing");

                if (needMerge) {
                    File outputDir = outputProvider.getContentLocation("main",
                            TransformManager.CONTENT_DEX, getScopes(),
                            Format.DIRECTORY);
                    FileUtils.mkdirs(outputDir);

                    // first delete the output folder where the final dex file(s) will be.
                    FileUtils.cleanOutputDir(outputDir);
                    FileUtils.mkdirs(outputDir);

                    // find the inputs of the dex merge.
                    // they are the content of the intermediate folder.
                    List<File> outputs = null;
                    if (!multiDex) {
                        // content of the folder is jar files.
                        File[] files = intermediateFolder.listFiles((file, name) -> {
                            return name.endsWith(SdkConstants.DOT_JAR);
                        });
                        if (files != null) {
                            outputs = Arrays.asList(files);
                        }
                    } else {
                        File[] directories = intermediateFolder.listFiles(File::isDirectory);
                        if (directories != null) {
                            outputs = Arrays.asList(directories);
                        }
                    }

                    if (outputs == null) {
                        throw new RuntimeException("No dex files to merge!");
                    }

                    androidBuilder.convertByteCode(
                            outputs,
                            outputDir,
                            multiDex,
                            mainDexListFile,
                            dexOptions,
                            getOptimize(),
                            outputHandler);
                }
            }
        } catch (Exception e) {
            throw new TransformException(e);
        }
    }

    private final class PreDexTask implements Callable<Void> {
        @NonNull
        private final File from;
        @NonNull
        private final File to;
        @NonNull
        private final Set<String> hashs;
        @NonNull
        private final ProcessOutputHandler outputHandler;
        @NonNull
        private final FileCache fileCache;

        private PreDexTask(
                @NonNull File from,
                @NonNull File to,
                @NonNull Set<String> hashs,
                @NonNull ProcessOutputHandler outputHandler,
                @NonNull FileCache fileCache) {
            this.from = from;
            this.to = to;
            this.hashs = hashs;
            this.outputHandler = outputHandler;
            this.fileCache = fileCache;
        }

        @Override
        public Void call() throws Exception {
            logger.info("predex called for %s", from);
            // TODO remove once we can properly add a library as a dependency of its test.
            String hash = getFileHash(from);

            synchronized (hashs) {
                if (hashs.contains(hash)) {
                    logger.info("Hash unknown");
                    return null;
                }

                hashs.add(hash);
            }

            boolean optimize = getOptimize();

            // To use the cache for pre-dexing, we first need to specify all the inputs that affect
            // the outcome of a pre-dex (see DxDexKey for an exhaustive list of these inputs)
            FileCache.Inputs.Builder inputs = new FileCache.Inputs.Builder();

            String inputFilePath = from.getCanonicalPath();
            int explodedAarIndex = inputFilePath.lastIndexOf("exploded-aar");
            if (explodedAarIndex != -1) {
                // If a file is exploded from an aar, we can use the file path relative to the
                // "exploded-aar" directory as the unique ID of the file (i.e., if two files having
                // the same relative file paths under "exploded-aar", their contents must be the
                // same regardless of where the "exploded-aar" directories are located).
                inputs.put(
                        "exploded-aar-file",
                        inputFilePath.substring(explodedAarIndex + "exploded-aar".length() + 1));
            } else {
                inputs.put("file", from);
            }
            inputs.put(
                    "buildToolsRevision",
                    androidBuilder.getTargetInfo().getBuildTools().getRevision().toString());
            inputs.put("jumboMode", dexOptions.getJumboMode())
                    .put("optimize", optimize)
                    .put("multiDex", multiDex);
            List<String> additionalParams = dexOptions.getAdditionalParameters();
            for (int i = 0; i < additionalParams.size(); i++) {
                inputs.put("additionalParameter" + (i + 1), additionalParams.get(i));
            }

            // Run pre-dexing using the cache
            fileCache.getOrCreateFile(
                    to,
                    inputs.build(),
                    (outputFile) -> {
                        if (multiDex) {
                            FileUtils.mkdirs(outputFile);
                        }
                        try {
                            androidBuilder.preDexLibrary(
                                    from,
                                    outputFile,
                                    multiDex,
                                    dexOptions,
                                    optimize,
                                    outputHandler);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } catch (ProcessException e) {
                            throw new RuntimeException(e);
                        }
                        return null;
                    });

            for (File file : Files.fileTreeTraverser().breadthFirstTraversal(to)) {
                if (file.isFile()) {
                    instantRunBuildContext.addChangedFile(FileType.DEX, file);
                }
            }

            return null;
        }
    }

    /**
     * Returns the hash of a file.
     *
     * If the file is a folder, it's a hash of its path. If the file is a file, then
     * it's a hash of the file itself.
     *
     * @param file the file to hash
     */
    @NonNull
    private static String getFileHash(@NonNull File file) throws IOException {
        HashCode hashCode;
        HashFunction hashFunction = Hashing.sha1();
        if (file.isDirectory()) {
            hashCode = hashFunction.hashString(file.getPath(), Charsets.UTF_16LE);
        } else {
            hashCode = Files.hash(file, hashFunction);
        }

        return hashCode.toString();
    }

    @NonNull
    private File getPreDexFile(
            @NonNull TransformOutputProvider output,
            boolean needMerge,
            @Nullable File outFolder,
            @NonNull QualifiedContent qualifiedContent) {
        if (needMerge) {
            Preconditions.checkNotNull(outFolder);
            return new File(outFolder, getFilename(qualifiedContent.getFile()));
        } else {
            return getOutputLocation(output, qualifiedContent, qualifiedContent.getFile());
        }
    }

    @NonNull
    private File getOutputLocation(
            @NonNull TransformOutputProvider output,
            @NonNull QualifiedContent qualifiedContent,
            @NonNull File file) {
        // In InstantRun mode, all files are guaranteed to have a unique name due to the slicer
        // transform. adding sha1 to the name can lead to cleaning issues in device, it's much
        // easier if the slices always have the same names, irrespective of the current variant,
        // last version wins.
        String name = instantRunBuildContext.isInInstantRunMode()
                && (qualifiedContent.getScopes().contains(Scope.PROJECT)
                    || qualifiedContent.getScopes().contains(Scope.SUB_PROJECTS))
                ? getInstantRunFileName(file) : getFilename(file);
        File contentLocation = output.getContentLocation(name,
                TransformManager.CONTENT_DEX, qualifiedContent.getScopes(),
                multiDex ? Format.DIRECTORY : Format.JAR);
        if (multiDex) {
            FileUtils.mkdirs(contentLocation);
        } else {
            FileUtils.mkdirs(contentLocation.getParentFile());
        }
        return contentLocation;
    }

    @NonNull
    private String getInstantRunFileName(@NonNull File inputFile) {
        if (inputFile.isDirectory()) {
            return inputFile.getName();
        } else {
            return inputFile.getName().replace(".", "_");
        }
    }

    @NonNull
    private String getFilename(@NonNull File inputFile) {
        // If multidex is enabled, this name will be used for a folder and classes*.dex files will
        // inside of it.
        String suffix = multiDex ? "" : SdkConstants.DOT_JAR;

        return FileUtils.getDirectoryNameForJar(inputFile) + suffix;
    }

    /**
     * Decides whether to run dx with optimizations.
     *
     * <p>Value from {@link DexOptions} is used if set, otherwise we check if the build is
     * debuggable.
     */
    private boolean getOptimize() {
        return MoreObjects.firstNonNull(dexOptions.getOptimize(), !debugMode);
    }

    /**
     * Determines whether a content is an external library.
     */
    private static boolean isExternalLibrary(@NonNull QualifiedContent content) {
        return content.getScopes().equals(Collections.singleton(Scope.EXTERNAL_LIBRARIES));
    }
}