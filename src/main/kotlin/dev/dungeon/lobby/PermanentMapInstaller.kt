package dev.dungeon.lobby

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** IO only. Imports to a staging directory, verifies every source file, then publishes atomically. */
class PermanentMapInstaller {
    fun install(worlds: Path, markerDirectory: Path, name: String, manifest: MapManifest, archive: () -> InputStream): Path {
        require(name.matches(Regex("[a-zA-Z0-9_-]+")) && !name.startsWith("dungeon_",true))
        val root = worlds.toAbsolutePath().normalize()
        // 26.x stores registered worlds as dimensions under the server's primary level folder.
        val dimensionId = name.lowercase(java.util.Locale.ROOT)
        val target = root.resolve("dimensions").resolve("minecraft").resolve(dimensionId).normalize()
        require(target.startsWith(root) && target != root)
        val markers = markerDirectory.toAbsolutePath().normalize()
        Files.createDirectories(markers)
        val marker = markers.resolve("$dimensionId.permanent")
        val manifestHash = MessageDigest.getInstance("SHA-256").digest(manifest.files.toSortedMap().entries
            .joinToString("\n") { "${it.key}=${it.value}" }.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val markerText = "world=$name\ndata-version=${manifest.dataVersion}\nmanifest-sha256=$manifestHash\n"
        // An existing world belongs to its operator. Never overwrite, merge or delete it.
        if(Files.exists(target,java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            require(!Files.isSymbolicLink(target) && Files.isDirectory(target.resolve("region")) && Files.isRegularFile(marker) && Files.readString(marker) == markerText) {
                "Lobby dimension already exists without Dungeon ownership marker: $target; will not overwrite"
            }
            return target
        }
        Files.createDirectories(target.parent)
        val staging = Files.createTempDirectory(root,".dungeon-lobby-import-")
        try {
            val seen = HashSet<String>()
            ZipInputStream(archive()).use { zip ->
                while(true) {
                    val entry = zip.nextEntry ?: break
                    val file = entry.name
                    val destination = staging.resolve(file).normalize()
                    require(!entry.isDirectory && !file.contains('\\') && destination.startsWith(staging) && destination != staging && seen.add(file)) { "Unsafe map entry: $file" }
                    val expected = requireNotNull(manifest.files[file]) { "Unlisted map file: $file" }
                    Files.createDirectories(destination.parent)
                    val digest = MessageDigest.getInstance("SHA-256")
                    var size = 0L
                    Files.newOutputStream(destination).use { output ->
                        val buffer = ByteArray(8192)
                        while(true) {
                            check(!Thread.currentThread().isInterrupted) { "Lobby import cancelled" }
                            val n = zip.read(buffer);if(n < 0) break
                            size += n;require(size <= 64L*1024*1024) { "Oversized map file: $file" }
                            digest.update(buffer,0,n);output.write(buffer,0,n)
                        }
                    }
                    require(digest.digest().joinToString("") { "%02x".format(it) } == expected) { "Lobby checksum mismatch: $file" }
                }
            }
            require(seen == manifest.files.keys) { "Incomplete lobby map archive" }
            val dimension = staging.resolve("dimensions/minecraft/overworld")
            require(Files.isDirectory(dimension.resolve("region"))) { "Bundled map has no overworld region data" }
            Files.move(dimension,target,StandardCopyOption.ATOMIC_MOVE)
            val pendingMarker = Files.createTempFile(markers,".lobby-ownership-",".tmp")
            try { Files.writeString(pendingMarker,markerText);Files.move(pendingMarker,marker,StandardCopyOption.ATOMIC_MOVE) }
            finally { Files.deleteIfExists(pendingMarker) }
            return target
        } catch(e: Exception) {
            throw IllegalStateException("Lobby import failed: target=$target; existing world untouched: ${e.message}",e)
        } finally {
            // This exact directory was just created by this import; never target an existing world.
            if(Files.exists(staging)) Files.walk(staging).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
        }
    }
}
