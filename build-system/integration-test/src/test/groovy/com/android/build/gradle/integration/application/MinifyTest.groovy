/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.build.gradle.integration.application

import com.android.build.gradle.integration.common.category.DeviceTests
import com.android.build.gradle.integration.common.fixture.GradleTestProject
import com.android.build.gradle.integration.common.truth.TruthHelper
import com.android.build.gradle.integration.common.truth.ZipFileSubject
import com.android.build.gradle.integration.common.utils.ZipHelper
import com.android.builder.model.AndroidProject
import groovy.transform.CompileStatic
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.objectweb.asm.Type
import org.objectweb.asm.tree.FieldNode

import static com.google.common.truth.Truth.assertThat
/**
 * Assemble tests for minify.
 */
@CompileStatic
class MinifyTest {
    @ClassRule
    static public GradleTestProject project = GradleTestProject.builder()
            .fromTestProject("minify")
            .create()

    @BeforeClass
    static void setUp() {
        project.execute("clean", "assembleMinified",
                "assembleMinifiedAndroidTest", "jarDebugClasses")
    }

    @AfterClass
    static void cleanUp() {
        project = null
    }

    @Test
    @Category(DeviceTests.class)
    void connectedCheck() {
        project.executeConnectedCheck()
    }

    @Test
    void 'App APK is minified'() throws Exception {
        File jarFile = project.file(
                "build/" +
                        "$AndroidProject.FD_INTERMEDIATES/" +
                        "transforms/" +
                        "proguard/" +
                        "minified/" +
                        "jars/3/1f/main.jar")

        Set<String> minifiedList = ZipHelper.getZipEntries(jarFile);

        // Ignore JaCoCo stuff.
        minifiedList.removeAll { it =~ /org.jacoco/ }
        minifiedList.removeAll(["about.html", "com/vladium/emma/rt/RT.class"])

        assertThat(minifiedList).containsExactly(
                "com/android/tests/basic/a.class", // Renamed StringProvider.
                "com/android/tests/basic/Main.class",
                "com/android/tests/basic/IndirectlyReferencedClass.class", // Kept by ProGuard rules.
                // No entry for UnusedClass, it gets removed.
        )
    }

    @Test
    void 'Test APK is not minified, but mappings are applied'() throws Exception {
        File jarFile = project.file(
                "build/" +
                        "$AndroidProject.FD_INTERMEDIATES/" +
                        "transforms/" +
                        "proguard/" +
                        "androidTest/" +
                        "minified/" +
                        "jars/3/1f/main.jar")
        Set<String> minifiedList = ZipHelper.getZipEntries(jarFile)

        def testClassFiles = minifiedList.findAll { !it.startsWith("org/hamcrest") }

        assertThat(testClassFiles).containsExactly(
                "com/android/tests/basic/MainTest.class",
                "com/android/tests/basic/UnusedTestClass.class",
                "com/android/tests/basic/UsedTestClass.class",
                "com/android/tests/basic/test/BuildConfig.class",
        )

        FieldNode stringProviderField = ZipHelper.checkClassFile(
                jarFile, "com/android/tests/basic/MainTest.class", "stringProvider");
        assert Type.getType(stringProviderField.desc).className == "com.android.tests.basic.a"
    }

    @Test
    void 'Test classes.jar is present for non Jack enabled variants'() throws Exception {
        ZipFileSubject classes = TruthHelper.assertThatZip(project.file(
                "build/$AndroidProject.FD_INTERMEDIATES/packaged/debug/classes.jar"))

        classes.contains("com/android/tests/basic/Main.class")
        classes.doesNotContain("com/android/tests/basic/MainTest.class")
    }
}
