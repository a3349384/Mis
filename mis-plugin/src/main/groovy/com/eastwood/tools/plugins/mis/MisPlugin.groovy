package com.eastwood.tools.plugins.mis

import com.android.build.gradle.BaseExtension
import com.eastwood.tools.plugins.mis.core.*
import com.eastwood.tools.plugins.mis.core.extension.Dependencies
import com.eastwood.tools.plugins.mis.core.extension.MisExtension
import com.eastwood.tools.plugins.mis.core.extension.Publication
import org.gradle.BuildListener
import org.gradle.BuildResult
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.DependencySubstitution
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.initialization.Settings
import org.gradle.api.invocation.Gradle
import org.gradle.api.publish.maven.MavenPublication

class MisPlugin implements Plugin<Project> {

    Project project
    File misDir
    boolean isMicroModule
    boolean executePublish
    String publishPublication

    PublicationManager publicationManager
    Map<String, Publication> publicationPublishMap

    List<Publication> misPublicationList

    void apply(Project project) {

        if (!MisUtil.hasAndroidPlugin(project)) {
            throw new GradleException("The android or android-library plugin must be applied to the project.")
        }

        this.project = project
        misDir = new File(project.rootProject.projectDir, '.gradle/mis')
        if (!misDir.exists()) {
            misDir.mkdirs()
        }

        project.gradle.addBuildListener(new BuildListener() {
            @Override
            void buildStarted(Gradle gradle) {

            }

            @Override
            void settingsEvaluated(Settings settings) {

            }

            @Override
            void projectsLoaded(Gradle gradle) {
            }

            @Override
            void projectsEvaluated(Gradle gradle) {
                project.configurations.all {
                    publicationManager.getPublicationMap().values().each {
                        if(it.invalid) {
                            exclude module: "mis-${it.groupId}-${it.artifactId}"
                        }
                    }
                }
            }

            @Override
            void buildFinished(BuildResult buildResult) {
            }
        })

        project.configurations.all {
            resolutionStrategy.dependencySubstitution.all { DependencySubstitution dependency ->
                if (dependency.requested instanceof ModuleComponentSelector) {
                    ModuleComponentSelector requested = (ModuleComponentSelector) dependency.requested
                    if (requested.module.startsWith('mis-')) {
                        String key = requested.module.substring(4)
                        Publication publication = publicationManager.getPublicationByKey(key)
                        if (publication != null) {
                            if (publication.invalid) {
                                exclude module: requested.module
                                return
                            }
                            if (!publication.useLocal) {
                                dependency.useTarget "${publication.groupId}:${publication.artifactId}:${publication.version}"
                                return
                            }
                        }
                    } else {
                        Publication publication = publicationManager.getPublication(requested.group, requested.module)
                        if (publication != null) {
                            if (publication.useLocal) {
                                def selector = FlatDirModuleComponentSelector.newSelector("mis-${requested.group}-${requested.module}")
                                dependency.useTarget selector
                                return
                            }
                        }
                    }
                }
            }
        }

        project.repositories {
            flatDir {
                dirs misDir.absolutePath
            }
        }

        project.gradle.getStartParameter().taskNames.each {
            if (it.startsWith('publishMis')) {
                executePublish = true
                publishPublication = it.substring(it.indexOf('[') + 1, it.lastIndexOf(']'))
            }
        }

        initPublicationManager()

        this.misPublicationList = new ArrayList<>()
        this.publicationPublishMap = new HashMap<>()
        this.isMicroModule = MisUtil.isMicroModule(project)
        MisExtension misExtension = project.extensions.create('mis', MisExtension, project)

        Dependencies.metaClass.misPublication { String value ->
            handleMisPublication(value, true)
        }

        project.dependencies.metaClass.misPublication { Object value ->
            handleMisPublication(value, false)
        }

        project.afterEvaluate {
            MisUtil.addMisSourceSets(project)

            misExtension.publications.each {
                Publication publication = it
                initPublication(publication)

                setPublicationSourceSets(publication)

                if (project.gradle.startParameter.taskNames.isEmpty()) {
                    addPublicationDependencies(publication)
                }

                if (executePublish && publishPublication == publication.artifactId) {
                    publicationPublishMap.put(publication.artifactId, publication)
                } else {
                    if (publication.version != null) {
                        handleMavenJar(project, publication)
                    } else {
                        handleLocalJar(project, publication)
                    }
                    publicationManager.hitPublication(publication)
                }
            }

            if (publicationPublishMap.size() == 0) {
                return
            }

            project.plugins.apply('maven-publish')
            def publishing = project.extensions.getByName('publishing')
            if (misExtension.configure != null) {
                publishing.repositories misExtension.configure
            }

            publicationPublishMap.each {
                createPublishTask(it.value)
            }
        }
    }

    def initPublicationManager() {
        publicationManager = PublicationManager.getInstance()

        if (publicationManager.hasLoadManifest) {
            return
        }
        publicationManager.loadManifest(project.rootProject, executePublish)
    }

    Object handleMisPublication(Object value, boolean fromPublication) {
        if (executePublish && !fromPublication) {
            return []
        }

        String groupId = null, artifactId = null, version = null
        if (value instanceof String) {
            String[] values = value.split(":")
            if (values.length >= 3) {
                groupId = values[0]
                artifactId = values[1]
                version = values[2]
            } else if (values.length == 2) {
                groupId = values[0]
                artifactId = values[1]
                version = null
            }
        } else if (value instanceof Map<String, ?>) {
            groupId = value.groupId
            artifactId = value.artifactId
            version = value.version
        }

        if (version == "") {
            version = null
        }

        if (groupId == null || artifactId == null) {
            throw new IllegalArgumentException("'${value}' is illege argument of misPublication(), the following types/formats are supported:" +
                    "\n  - String or CharSequence values, for example 'org.gradle:gradle-core:1.0'." +
                    "\n  - Maps, for example [groupId: 'org.gradle', artifactId: 'gradle-core', version: '1.0'].")
        }

        Publication publication = new Publication()
        publication.groupId = groupId
        publication.artifactId = artifactId

        def result
        File target = new File(misDir, "mis-${groupId}-${artifactId}.jar")
        if (target.exists()) {
            publication.useLocal = true
            result = ":mis-${groupId}-${artifactId}:"
        } else {
            Publication existPublication = publicationManager.getPublication(groupId, artifactId)
            if (existPublication == null) {
                if (version == null) {
                    result = ":mis-${groupId}-${artifactId}:"
                } else {
                    publication.version = version
                    result = "${groupId}:${artifactId}:${version}"
                }
            } else {
                if (existPublication.invalid) {
                    result = []
                } else if (existPublication.version == null) {
                    if (version == null) {
                        result = ":mis-${groupId}-${artifactId}:"
                    } else {
                        publication.version = version
                        result = "${groupId}:${artifactId}:${version}"
                    }
                } else {
                    publication.version = existPublication.version
                    result = "${groupId}:${artifactId}:${existPublication.version}"
                }
            }
        }

        misPublicationList.add(publication)
        return result
    }

    def handleLocalJar(Project project, Publication publication) {
        File target = new File(misDir, "mis-${publication.groupId}-${publication.artifactId}.jar")
        if (target.exists()) {
            boolean hasModifiedSource = publicationManager.hasModified(publication)
            if (!hasModifiedSource) {
                Publication lastPublication = publicationManager.getPublication(publication.groupId, publication.artifactId)
                if (lastPublication.version != publication.version) {
                    publication.invalid = false
                    publication.useLocal = true
                    publicationManager.addPublication(publication)
                } else {
                    lastPublication.invalid = false
                    lastPublication.useLocal = true
                }

                project.dependencies {
                    implementation project.files(target)
                }
                return
            }
        }

        File releaseJar = JarUtil.packJavaSourceJar(project, publication, true)
        // publication mis dir may be empty
        if (releaseJar == null) {
            publication.invalid = true
            publicationManager.addPublication(publication)
            if (target.exists()) {
                target.delete()
            }
            return
        }

        MisUtil.copyFile(releaseJar, target)
        project.dependencies {
            implementation ":mis-${publication.groupId}-${publication.artifactId}:"
        }
        publication.invalid = false
        publication.useLocal = true
        publicationManager.addPublication(publication)
    }

    def handleMavenJar(Project project, Publication publication) {
        publicationPublishMap.put(publication.artifactId, publication)
        boolean hasModifiedSource = publicationManager.hasModified(publication)
        File target = new File(misDir, "mis-${publication.groupId}-${publication.artifactId}.jar")
        if (target.exists()) {
            if (!hasModifiedSource) {
                Publication lastPublication = publicationManager.getPublication(publication.groupId, publication.artifactId)
                lastPublication.invalid = false
                lastPublication.useLocal = true

                project.dependencies {
                    implementation ":mis-${publication.groupId}-${publication.artifactId}:"
                }
                return
            }
        } else if (!hasModifiedSource) {
            Publication lastPublication = publicationManager.getPublication(publication.groupId, publication.artifactId)
            if (lastPublication.version != publication.version) {
                publicationManager.addPublication(publication)
            } else {
                lastPublication.invalid = false
                lastPublication.useLocal = false
            }

            project.dependencies {
                implementation publication.groupId + ':' + publication.artifactId + ':' + publication.version
            }
            return
        }

        def releaseJar = JarUtil.packJavaSourceJar(project, publication, false)
        // publication mis dir may be empty
        if (releaseJar == null) {
            publication.invalid = true
            publicationManager.addPublication(publication)
            if (target.exists()) {
                target.delete()
            }
            return
        }

        boolean equals = JarUtil.compareMavenJar(project, publication, releaseJar.absolutePath)
        if (equals) {
            // publication mis dir may be revert
            if (target.exists()) {
                target.delete()
            }

            project.dependencies {
                implementation publication.groupId + ':' + publication.artifactId + ':' + publication.version
            }
        } else {
            releaseJar = JarUtil.packJavaSourceJar(project, publication, true)
            MisUtil.copyFile(releaseJar, target)
            project.dependencies {
                implementation ":mis-${publication.groupId}-${publication.artifactId}:"
            }
        }
        publication.invalid = false
        publicationManager.addPublication(publication)
    }

    void initPublication(Publication publication) {
        String displayName = project.getDisplayName()
        publication.project = displayName.substring(displayName.indexOf("'") + 1, displayName.lastIndexOf("'"))
        def buildMis = new File(project.projectDir, 'build/mis')
        if (isMicroModule) {
            def result = publication.name.split(":")
            if (result.length >= 1) {
                publication.microModuleName = result[0]
            }
            if (publication.microModuleName == null || publication.microModuleName == "") {
                throw new IllegalArgumentException("Publication name '${publication.name}' is illegal. The correct format is '\${MicroModule Name}:\${SourceSet Name}', e.g. 'base:main' or 'base'.")
            }

            publication.sourceSetName = result.length >= 1 ? "main" : result[1]
            if (publication.sourceSetName == '') {
                publication.sourceSetName = 'main'
            }
            publication.buildDir = new File(buildMis, publication.microModuleName + '/' + publication.sourceSetName)
        } else {
            publication.sourceSetName = publication.name
            publication.buildDir = new File(buildMis, publication.name)
        }
    }

    void setPublicationSourceSets(Publication publication) {
        List<String> paths = new ArrayList<>()
        BaseExtension android = project.extensions.getByName('android')
        def sourceSets = android.sourceSets.getByName(publication.sourceSetName)
        sourceSets.aidl.srcDirs.each {
            if (!it.absolutePath.endsWith("mis")) return

            if (publication.microModuleName != null) {
                if (it.absolutePath.endsWith(publication.microModuleName + "${File.separator}src${File.separator + publication.sourceSetName + File.separator}mis")) {
                    paths.add(it.absolutePath)
                }
            } else {
                paths.add(it.absolutePath)
            }
        }

        publication.sourceSets = new HashMap<>()
        paths.each {
            SourceSet sourceSet = new SourceSet()
            sourceSet.path = it
            sourceSet.lastModifiedSourceFile = new HashMap<>()
            project.fileTree(it).each {
                if (it.name.endsWith('.java') || it.name.endsWith('.kt')) {
                    SourceFile sourceFile = new SourceFile()
                    sourceFile.path = it.path
                    sourceFile.lastModified = it.lastModified()
                    sourceSet.lastModifiedSourceFile.put(sourceFile.path, sourceFile)
                }
            }
            publication.sourceSets.put(sourceSet.path, sourceSet)
        }
    }

    void addPublicationDependencies(Publication publication) {
        if (publication.dependencies == null) return
        project.dependencies {
            if (publication.dependencies.compileOnly != null) {
                publication.dependencies.compileOnly.each {
                    compileOnly it
                }
            }
            if (publication.dependencies.implementation != null) {
                publication.dependencies.implementation.each {
                    implementation it
                }
            }
        }
    }

    void createPublishTask(Publication publication) {
        def taskName = 'compileMis[' + publication.artifactId + ']Source'
        def compileTask = project.getTasks().findByName(taskName)
        if (compileTask == null) {
            compileTask = project.getTasks().create(taskName, CompileMisTask.class)
            compileTask.publication = publication
            compileTask.dependsOn 'clean'
        }

        def publicationName = 'Mis[' + publication.artifactId + "]"
        String publishTaskNamePrefix = "publish" + publicationName + "PublicationTo"
        project.tasks.whenTaskAdded {
            if (it.name.startsWith(publishTaskNamePrefix)) {
                it.dependsOn compileTask
                it.doLast {
                    File groupDir = project.rootProject.file(".gradle/mis/" + publication.groupId)
                    new File(groupDir, publication.artifactId + ".jar").delete()
                }
            }
        }
        createPublishingPublication(publication, publicationName)
    }

    void createPublishingPublication(Publication publication, String publicationName) {
        def publishing = project.extensions.getByName('publishing')
        MavenPublication mavenPublication = publishing.publications.maybeCreate(publicationName, MavenPublication)
        mavenPublication.groupId = publication.groupId
        mavenPublication.artifactId = publication.artifactId
        mavenPublication.version = publication.version
        mavenPublication.pom.packaging = 'jar'

        def outputsDir = new File(publication.buildDir, "outputs")
        mavenPublication.artifact source: new File(outputsDir, "classes.jar")
        mavenPublication.artifact source: new File(outputsDir, "classes-source.jar"), classifier: 'sources'

        if (publication.dependencies != null) {
            mavenPublication.pom.withXml {
                def dependenciesNode = asNode().appendNode('dependencies')
                if (publication.dependencies.implementation != null) {
                    publication.dependencies.implementation.each {
                        def gav = it.split(":")
                        def dependencyNode = dependenciesNode.appendNode('dependency')
                        dependencyNode.appendNode('groupId', gav[0])
                        dependencyNode.appendNode('artifactId', gav[1])
                        dependencyNode.appendNode('version', gav[2])
                        dependencyNode.appendNode('scope', 'implementation')
                    }
                }
            }
        }

    }

}