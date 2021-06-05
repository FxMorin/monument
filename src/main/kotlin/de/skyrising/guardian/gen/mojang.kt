package de.skyrising.guardian.gen

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZonedDateTime
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

val JARS_CLIENT_DIR: Path = JARS_DIR.resolve("client")
val JARS_SERVER_DIR: Path = JARS_DIR.resolve("server")
val JARS_MERGED_DIR: Path = JARS_DIR.resolve("merged")

val MOJANG_CACHE_DIR: Path = CACHE_DIR.resolve("mojang")
val LIBS_CACHE_DIR: Path = MOJANG_CACHE_DIR.resolve("libraries")

private fun requestJson(url: URI): CompletableFuture<JsonObject> = supplyAsync {
    println("Fetching $url")
    val conn = url.toURL().openConnection() as HttpURLConnection
    conn.connect()
    GSON.fromJson<JsonObject>(InputStreamReader(conn.inputStream))
}

val mcGameVersionManifest: CompletableFuture<JsonObject> by lazy { requestJson(URI("https://launchermeta.mojang.com/mc/game/version_manifest.json")) }
val mcVersions: CompletableFuture<Map<String, VersionInfo>> by lazy { getVersions() }

fun getVersionInfo(id: String): CompletableFuture<VersionInfo?> = mcVersions.thenApply { it[id] }

data class VersionInfo(val id: String, val type: String, val url: URI, val time: ZonedDateTime, val releaseTime: ZonedDateTime) : Comparable<VersionInfo?> {
    override fun compareTo(other: VersionInfo?) = releaseTime.compareTo(other?.releaseTime)
}

private fun getVersions(): CompletableFuture<Map<String, VersionInfo>> = mcGameVersionManifest.thenApply {
    val set = GSON.fromJson<TreeSet<VersionInfo>>(it.require("versions"))
    val map = LinkedHashMap<String, VersionInfo>()
    for (version in set) map[version.id] = version
    map
}

private fun cachedFile(url: URI): Path? {
    if (url.host != "launchermeta.mojang.com" || !url.path.startsWith("/v1/packages/")) return null
    val path = url.path.substring("/v1/packages/".length)
    val hash = path.substringBefore('/')
    return MOJANG_CACHE_DIR.resolve(hash.substring(0, 2)).resolve(hash.substring(2)).resolve(path.substringAfter('/'))
}

private fun getOrFetch(url: URI): CompletableFuture<Path> {
    val path = cachedFile(url) ?: throw IllegalArgumentException("$url is not cacheable")
    return download(url.toURL(), path).thenApply { path }
}

private val versionManifests = ConcurrentHashMap<String, CompletableFuture<JsonObject>>()

private fun fetchVersionManifest(id: String) = getVersionInfo(id).thenCompose {
    if (it == null) return@thenCompose null
    getOrFetch(it.url).thenApply { path ->
        Files.newBufferedReader(path, StandardCharsets.UTF_8).use { reader ->
            GSON.fromJson<JsonObject>(reader)
        }
    }
}

fun getVersionManifest(id: String) = versionManifests.computeIfAbsent(id, ::fetchVersionManifest)

fun downloadFile(id: String, download: String, file: Path, listener: ((DownloadProgress) -> Unit)? = null): CompletableFuture<Boolean> = getVersionManifest(id).thenCompose {
    startDownload(it["downloads"]?.asJsonObject, download, file, listener)
}

fun downloadLibraries(id: String): CompletableFuture<List<Path>> = getVersionManifest(id).thenCompose {
    val libs = it["libraries"]?.asJsonArray ?: return@thenCompose CompletableFuture.completedFuture(emptyList<Path>())
    val futures = mutableListOf<CompletableFuture<Path>>()
    for (lib in libs) {
        val obj = lib.asJsonObject
        val rules = obj["rules"]?.asJsonArray
        if (rules != null) {
            var enabled = true
            for (rule in rules) {
                val ruleObj = rule.asJsonObject
                val action = ruleObj["action"].asJsonPrimitive.asString == "allow"
                if (ruleObj.has("os")) {
                    if (ruleObj["os"]!!.asJsonObject["name"]!!.asString != "osx") continue
                }
                enabled = action
            }
            if (!enabled) continue
        }
        futures.add(downloadLibrary(obj["downloads"]!!.asJsonObject))
    }
    CompletableFuture.allOf(*futures.toTypedArray()).thenApply {
        futures.map(CompletableFuture<Path>::get).toList()
    }
}

private fun downloadLibrary(obj: JsonObject): CompletableFuture<Path> {
    val artifact = obj["artifact"]!!.asJsonObject
    val path = LIBS_CACHE_DIR.resolve(artifact["path"]!!.asString)
    return download(URL(artifact["url"]!!.asString), path, null).thenApply { path }
}

private fun startDownload(downloads: JsonObject?, download: String, file: Path, listener: ((DownloadProgress) -> Unit)?): CompletableFuture<Boolean> {
    if (downloads == null) return CompletableFuture.completedFuture(false)
    val url = downloads[download]?.asJsonObject?.get("url")?.asString ?: return CompletableFuture.completedFuture(false)
    return download(URL(url), file, listener).thenApply { true }
}

private val inProgress = mutableMapOf<Path, CompletableFuture<Path>>()

fun getJar(id: String, target: MappingTarget): CompletableFuture<Path> {
    if (target == MappingTarget.MERGED) {
        val merged = JARS_MERGED_DIR.resolve("$id.jar")
        val ip = inProgress[merged]
        if (ip != null) return ip
        if (Files.exists(merged)) return CompletableFuture.completedFuture(merged)
        val client = getJar(id, MappingTarget.CLIENT)
        val server = getJar(id, MappingTarget.SERVER)
        return deduplicate(inProgress, merged, CompletableFuture.allOf(client, server).thenCompose {
            mergeJars(id, client.get(), server.get(), merged)
        }.thenApply {
            merged
        })
    }
    val dir = if (target == MappingTarget.CLIENT) JARS_CLIENT_DIR else JARS_SERVER_DIR
    val path = dir.resolve("$id.jar")
    val ip = inProgress[path]
    if (ip != null) return ip
    if (Files.exists(path)) return CompletableFuture.completedFuture(path)
    return deduplicate(inProgress, path, downloadFile(id, target.id, path).thenApply { path })
}

private data class Dependency(val dependency: String, val type: String = "implementation")

fun generateGradleBuild(id: String, dir: Path): CompletableFuture<Unit> = getVersionManifest(id).thenApply { manifest ->
    PrintWriter(Files.newBufferedWriter(dir.resolve("build.gradle"), StandardCharsets.UTF_8)).use { out ->
        val libs = manifest["libraries"]!!.asJsonArray
        val byCondition = mutableMapOf<String, MutableSet<Dependency>>()
        byCondition[""] = mutableSetOf(
            Dependency("net.fabricmc:fabric-loader:+", "compileOnly"),
            Dependency("com.google.code.findbugs:jsr305:3.0.1", "compileOnly")
        )
        for (lib in libs) {
            // TODO: natives? Just getting the java parts is enough to fix syntax highlighting
            //  and the decompiled code probably won't run anyway
            val obj = lib.asJsonObject
            val rules = obj["rules"]?.asJsonArray
            byCondition.computeIfAbsent(rulesToString(rules)) { mutableSetOf() }.add(Dependency(obj["name"].asString))
        }
        out.println("apply plugin: 'java'")
        out.println("apply plugin: 'application'")
        out.println()
        val javaVersion = manifest["javaVersion"]?.asJsonObject?.get("majorVersion")?.asInt ?: 8
        val gradleJavaVersion = if (javaVersion <= 8) "JavaVersion.VERSION_1_$javaVersion" else "JavaVersion.VERSION_$javaVersion"
        out.println("sourceCompatibility = $gradleJavaVersion")
        out.println("targetCompatibility = $gradleJavaVersion")
        out.println()
        out.println("repositories {")
        out.println("    maven {")
        out.println("        name = 'Minecraft Libraries'")
        out.println("        url = 'https://libraries.minecraft.net/'")
        out.println("    }")
        out.println("    maven {")
        out.println("        name = 'Fabric' // For sided annotations")
        out.println("        url = 'https://maven.fabricmc.net/'")
        out.println("    }")
        out.println("    mavenCentral()")
        out.println("}")
        out.println()
        out.println("def os_name = null")
        out.println("def prop = System.properties['os.name'].toLowerCase()")
        out.println("if (prop.contains('win')) os_name = 'windows'")
        out.println("else if (prop.contains('mac')) os_name = 'osx'")
        out.println("else if (prop.contains('linux')) os_name = 'linux'")
        out.println()
        out.println("dependencies {")
        var first = true
        for ((condition, set) in byCondition.entries) {
            if (first) first = false
            else out.println()
            var indent = ""
            if (condition.isNotEmpty()) {
                out.print("    if (")
                out.print(condition)
                out.println(") {")
                indent = "    "
            }
            for (dep in set) {
                out.print("    ")
                out.print(indent)
                out.print(dep.type)
                out.print(" \"")
                out.print(dep.dependency)
                out.println("\"")
            }
            if (condition.isNotEmpty()) {
                out.println("    }")
            }
        }
        out.println("}")
    }
    PrintWriter(Files.newBufferedWriter(dir.resolve("settings.gradle"), StandardCharsets.UTF_8)).use { out ->
        out.println("rootProject.name = 'minecraft'")
    }
}

private fun rulesToString(rules: JsonArray?): String {
    if (rules == null) return ""
    val allows = mutableListOf<String>()
    val disallows = mutableListOf<String>()
    for (rule in rules) {
        val obj = rule.asJsonObject
        val allow = obj["action"].asString == "allow"
        val dest = if (allow) allows else disallows
        if (obj.has("os")) {
            dest.add("os_name ${if (allow) "==" else "!="} \"${obj["os"].asJsonObject["name"].asString}\"")
        }
    }
    val allowStr = allows.joinToString(" || ")
    val disallowStr = disallows.joinToString(" && ")
    val sb = StringBuilder()
    if (allows.size > 1 && disallows.isNotEmpty()) sb.append('(').append(allowStr).append(')')
    else sb.append(allowStr)
    if (allows.isNotEmpty() && disallows.isNotEmpty()) sb.append(" && ")
    sb.append(disallowStr)
    return sb.toString()
}