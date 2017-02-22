/*
 * Copyright 2016 the original author or authors.
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

package org.gradle.caching.http.internal

import org.gradle.integtests.fixtures.AbstractIntegrationSpec
import org.gradle.test.fixtures.server.http.TestUserRealm
import org.gradle.util.ports.ReleasingPortAllocator
import org.junit.Rule
import org.mortbay.jetty.Server
import org.mortbay.jetty.bio.SocketConnector
import org.mortbay.jetty.handler.HandlerCollection
import org.mortbay.jetty.security.BasicAuthenticator
import org.mortbay.jetty.security.Constraint
import org.mortbay.jetty.security.ConstraintMapping
import org.mortbay.jetty.security.SecurityHandler
import org.mortbay.jetty.webapp.WebAppContext
import org.mortbay.servlet.RestFilter

class HttpBuildCacheServiceIntegrationTest extends AbstractIntegrationSpec {

    static final String ORIGINAL_HELLO_WORLD = """
            public class Hello {
                public static void main(String... args) {
                    System.out.println("Hello World!");
                }
            }
        """
    static final String CHANGED_HELLO_WORLD = """
            public class Hello {
                public static void main(String... args) {
                    System.out.println("Hello World with Changes!");
                }
            }
        """

    @Rule
    ReleasingPortAllocator portAllocator = new ReleasingPortAllocator()
    Server server
    HandlerCollection prefixHandlers

    def setup() {
        def cacheDir = file(".gradle/cache-dir")
        cacheDir.mkdirs()

        def port = portAllocator.assignPort()
        println "Using port $port"
        server = new Server()
        def connector = new SocketConnector()
        connector.setPort(port)
        server.setConnectors(connector)

        def allHandlers = new HandlerCollection()

        def webapp = new WebAppContext()
        webapp.contextPath = "/cache"
        webapp.resourceBase = cacheDir.absolutePath
        webapp.addFilter(RestFilter, "/*", 1)

        prefixHandlers = new HandlerCollection()
        allHandlers.addHandler(prefixHandlers)
        allHandlers.addHandler(webapp)
        server.setHandler(allHandlers)
        server.start()

        settingsFile << """
            buildCache {  
                local {
                    enabled = false
                }
                remote(org.gradle.caching.http.HttpBuildCache) {
                    url = "http://localhost:$port/cache/"
                }
            }
        """

        buildFile << """
            apply plugin: "java"
        """

        file("src/main/java/Hello.java") << ORIGINAL_HELLO_WORLD
        file("src/main/resources/resource.properties") << """
            test=true
        """
    }

    def cleanup() {
        server.stop()
    }

    def "no task is re-executed when inputs are unchanged"() {
        when:
        withHttpBuildCache().succeeds  "jar"
        then:
        skippedTasks.empty

        expect:
        withHttpBuildCache().succeeds  "clean"

        when:
        withHttpBuildCache().succeeds  "jar"
        then:
        skippedTasks.containsAll ":compileJava", ":jar"
    }

    def "outputs are correctly loaded from cache"() {
        buildFile << """
            apply plugin: "application"
            mainClassName = "Hello"
        """
        withHttpBuildCache().run  "run"
        withHttpBuildCache().run  "clean"
        expect:
        withHttpBuildCache().succeeds  "run"
    }

    def "tasks get cached when source code changes without changing the compiled output"() {
        when:
        withHttpBuildCache().succeeds  "assemble"
        then:
        skippedTasks.empty

        file("src/main/java/Hello.java") << """
            // Change to source file without compiled result change
        """
        withHttpBuildCache().succeeds  "clean"

        when:
        withHttpBuildCache().succeeds  "assemble"
        then:
        nonSkippedTasks.contains ":compileJava"
        skippedTasks.contains ":jar"
    }

    def "tasks get cached when source code changes back to previous state"() {
        expect:
        withHttpBuildCache().succeeds "jar" assertTaskNotSkipped ":compileJava" assertTaskNotSkipped ":jar"

        when:
        file("src/main/java/Hello.java").text = CHANGED_HELLO_WORLD
        then:
        withHttpBuildCache().succeeds  "jar" assertTaskNotSkipped ":compileJava" assertTaskNotSkipped ":jar"

        when:
        file("src/main/java/Hello.java").text = ORIGINAL_HELLO_WORLD
        then:
        withHttpBuildCache().succeeds  "jar"
        result.assertTaskSkipped ":compileJava"
        result.assertTaskSkipped ":jar"
    }

    def "jar tasks get cached even when output file is changed"() {
        file("settings.gradle") << "rootProject.name = 'test'"
        buildFile << """
            if (file("toggle.txt").exists()) {
                jar {
                    destinationDir = file("\$buildDir/other-jar")
                    baseName = "other-jar"
                }
            }
        """

        expect:
        withHttpBuildCache().succeeds  "assemble"
        skippedTasks.empty
        file("build/libs/test.jar").isFile()

        withHttpBuildCache().succeeds  "clean"
        !file("build/libs/test.jar").isFile()

        file("toggle.txt").touch()

        withHttpBuildCache().succeeds  "assemble"
        skippedTasks.contains ":jar"
        !file("build/libs/test.jar").isFile()
        file("build/other-jar/other-jar.jar").isFile()
    }

    def "clean doesn't get cached"() {
        withHttpBuildCache().run  "assemble"
        withHttpBuildCache().run  "clean"
        withHttpBuildCache().run  "assemble"
        when:
        withHttpBuildCache().succeeds  "clean"
        then:
        nonSkippedTasks.contains ":clean"
    }

    def "cacheable task with cache disabled doesn't get cached"() {
        buildFile << """
            compileJava.outputs.cacheIf { false }
        """

        withHttpBuildCache().run  "compileJava"
        withHttpBuildCache().run  "clean"

        when:
        withHttpBuildCache().succeeds  "compileJava"
        then:
        // :compileJava is not cached, but :jar is still cached as its inputs haven't changed
        nonSkippedTasks.contains ":compileJava"
    }

    def "non-cacheable task with cache enabled gets cached"() {
        file("input.txt") << "data"
        buildFile << """
            class NonCacheableTask extends DefaultTask {
                @InputFile inputFile
                @OutputFile outputFile

                @TaskAction copy() {
                    project.mkdir outputFile.parentFile
                    outputFile.text = inputFile.text
                }
            }
            task customTask(type: NonCacheableTask) {
                inputFile = file("input.txt")
                outputFile = file("\$buildDir/output.txt")
                outputs.cacheIf { true }
            }
            compileJava.dependsOn customTask
        """

        when:
        withHttpBuildCache().run  "jar"
        then:
        nonSkippedTasks.contains ":customTask"

        when:
        withHttpBuildCache().run  "clean"
        withHttpBuildCache().succeeds  "jar"
        then:
        skippedTasks.contains ":customTask"
    }

    def "credentials can be specified"() {
        withBasicAuth("user", "pass")
        settingsFile << """
            buildCache {
                remote.credentials {
                    username = "user"
                    password = "pass"
                }
            }
        """

        when:
        withHttpBuildCache().succeeds  "jar"
        then:
        skippedTasks.empty

        expect:
        withHttpBuildCache().succeeds  "clean"

        when:
        withHttpBuildCache().succeeds  "jar"
        then:
        skippedTasks.containsAll ":compileJava", ":jar"
    }

    def "build does not leak credentials in cache URL"() {
        withBasicAuth("correct-username", "correct-password")
        settingsFile << """
            buildCache {
                remote.credentials {
                    username = "correct-username"
                    password = "correct-password"
                }
            }
        """

        when:
        executer.withArgument("--info")
        withHttpBuildCache().succeeds "assemble"
        then:
        !result.output.contains("correct-username")
        !result.output.contains("correct-password")
    }

    def "incorrect credentials cause build to fail"() {
        withBasicAuth("user", "pass")
        settingsFile << """
            buildCache {
                remote.credentials {
                    username = "incorrect-user"
                    password = "incorrect-pass"
                }
            }
        """

        when:
        withHttpBuildCache().fails "jar"
        then:
        failureDescriptionContains "response status 401: Unauthorized"
        // Make sure we don't log the password
        !output.contains("incorrect-pass")
        !errorOutput.contains("incorrect-pass")
    }

    def withHttpBuildCache() {
        executer.withBuildCacheEnabled()
        this
    }

    def withBasicAuth(String username, String password) {
        def realm = new TestUserRealm()
        realm.username = username
        realm.password = password

        def securityHandler = new SecurityHandler()
        securityHandler.userRealm = realm
        securityHandler.authenticator = new BasicAuthenticator()

        def constraint = new Constraint()
        constraint.authenticate = true
        constraint.roles = ['*'] as String[]
        def constraintMapping = new ConstraintMapping()
        constraintMapping.pathSpec = "/cache/*"
        constraintMapping.constraint = constraint
        securityHandler.constraintMappings = [constraintMapping]

        prefixHandlers.addHandler(securityHandler)
    }
}
