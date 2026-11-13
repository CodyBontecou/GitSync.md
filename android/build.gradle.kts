import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformParameters
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.CacheableTransform
import org.gradle.api.attributes.Attribute
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.kapt) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.hilt.android) apply false
    alias(libs.plugins.play.publisher) apply false
}

/** JGit 5/6 cannot parse C-quoted attribute paths. Core supplies an API-compatible
 * parser; omit only the upstream parser class to avoid duplicate classes on Android. */
@CacheableTransform
abstract class JgitAttributesJarTransform : TransformAction<TransformParameters.None> {
    @get:InputArtifact
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val inputArtifact: Provider<FileSystemLocation>

    override fun transform(outputs: TransformOutputs) {
        val input = inputArtifact.get().asFile
        if (!input.name.startsWith("org.eclipse.jgit-") || input.name.endsWith("-attributes.jar")) { outputs.file(input); return }
        ZipFile(input).use { jar ->
            check(jar.getEntry("org/eclipse/jgit/attributes/AttributesNode.class") != null) {
                "Review the attributes compatibility adapter before changing JGit"
            }
            val parserBytes = jar.getInputStream(jar.getEntry("org/eclipse/jgit/attributes/AttributesNode.class")).use { it.readBytes() }
            val parserHash = java.security.MessageDigest.getInstance("SHA-256").digest(parserBytes).joinToString("") { "%02x".format(it) }
            check(parserHash == "740ebd34c5d9e530d43fbb447a09b55ab57f6340be0f440cc895f2d23370c74c") {
                "Upstream attributes parser changed: review/remove the quoted-path adapter before upgrading JGit"
            }
            val output = outputs.file(input.nameWithoutExtension + "-attributes.jar")
            ZipOutputStream(output.outputStream()).use { archive ->
                jar.entries().asSequence().forEach { entry ->
                    val signature = entry.name.startsWith("META-INF/") && entry.name.substringAfterLast('.').uppercase() in setOf("SF", "RSA", "DSA", "EC")
                    if (entry.name != "org/eclipse/jgit/attributes/AttributesNode.class" && !signature) {
                        archive.putNextEntry(ZipEntry(entry.name).apply { time = 0 })
                        if (!entry.isDirectory) jar.getInputStream(entry).use { it.copyTo(archive) }
                        archive.closeEntry()
                    }
                }
            }
        }
    }
}

subprojects {
    val quotedAttributes = Attribute.of("md.gitsync.quoted-attributes", Boolean::class.javaObjectType)
    dependencies {
        artifactTypes.configureEach { if (name == "jar") attributes.attribute(quotedAttributes, false) }
        registerTransform(JgitAttributesJarTransform::class) {
            from.attribute(quotedAttributes, false)
            to.attribute(quotedAttributes, true)
        }
    }
    configurations.configureEach { attributes.attribute(quotedAttributes, true) }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}
