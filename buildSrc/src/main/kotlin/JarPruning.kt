import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Removes classes from a built jar that nothing in it can reach, or that no JVM able to run them will ever load. */
object JarPruning {
    private const val VERSIONED = "META-INF/versions/"
    private val DESCRIPTOR_TYPE = Regex("L([^;<>]+)[;<]")

    /**
     * Drops the classes under [prefix] that no class outside it reaches, following every class a constant pool names, its
     * descriptors and signatures included. For a vendored library, which carries far more API than the code calls.
     */
    fun pruneUnreachable(jar: File, prefix: String): String {
        val entries = read(jar)
        val classes = entries.keys.filter { it.endsWith(".class") && !it.startsWith(VERSIONED) }.associateBy { it.removeSuffix(".class") }
        val library = classes.keys.filter { it.startsWith(prefix) }
        check(library.isNotEmpty()) { "no classes under $prefix in ${jar.name}" }
        val reached = HashSet<String>()
        val pending = ArrayDeque(classes.keys.filter { !it.startsWith(prefix) })
        while (pending.isNotEmpty()) {
            val name = pending.removeFirst()
            if (!reached.add(name)) continue
            val bytes = entries[classes[name] ?: continue]?.bytes ?: continue
            for (ref in referencedClasses(bytes)) {
                if (ref in classes && ref !in reached) pending.addLast(ref)
                // A nested class needs the class it is nested in to load.
                val outer = ref.substringBefore('$')
                if (outer != ref && outer in classes && outer !in reached) pending.addLast(outer)
            }
        }
        val drop = library.filter { it !in reached }.map { "$it.class" }.toSet()
        write(jar, entries, drop)
        return "kept ${library.size - drop.size} of ${library.size} classes under $prefix"
    }

    /**
     * Drops the Java 8 copy of each class under [packages] that also has a copy in META-INF/versions/[minVersion], for code
     * that only runs on that version or later. A class something outside [packages] names keeps its Java 8 copy, along with
     * the classes nested in it, since older JVMs still load those.
     *
     * That holds only while the kept classes reach the rest of [packages] on paths an older JVM never runs, as a lambda the
     * caller only invokes once it knows it is on a newer one. A kept class that touches another directly, in a static
     * initialiser or a method an older JVM calls, fails there with NoClassDefFoundError.
     */
    fun dropOldCopies(jar: File, packages: List<String>, minVersion: Int): String {
        val entries = read(jar)
        val versioned = "$VERSIONED$minVersion/"
        fun inPackages(name: String) = packages.any { name.startsWith(it) }
        val reachedFromOutside = HashSet<String>()
        for ((name, entry) in entries) {
            if (!name.endsWith(".class") || name.startsWith(VERSIONED) || inPackages(name)) continue
            referencedClasses(entry.bytes ?: continue).filterTo(reachedFromOutside) { inPackages(it) }
        }
        fun keptForOldJvms(name: String) = reachedFromOutside.any { name == it || name.startsWith("$it$") }
        val drop = entries.keys.filter { name ->
            name.endsWith(".class") && !name.startsWith(VERSIONED) && inPackages(name)
                && "$versioned$name" in entries && !keptForOldJvms(name.removeSuffix(".class"))
        }.toSet()
        write(jar, entries, drop)
        return "dropped ${drop.size} Java 8 copies under ${packages.joinToString()}, kept ${reachedFromOutside.sorted()}"
    }

    /** The classes a class file's constant pool names, with the types in its descriptors and signatures. */
    fun referencedClasses(bytes: ByteArray): Set<String> {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == 0xCAFEBABE.toInt()) { "not a class file" }
        input.readUnsignedShort()
        input.readUnsignedShort()
        val count = input.readUnsignedShort()
        val utf8 = arrayOfNulls<String>(count)
        val classIndices = ArrayList<Int>()
        var i = 1
        while (i < count) {
            when (val tag = input.readUnsignedByte()) {
                1 -> utf8[i] = input.readUTF()
                7 -> classIndices.add(input.readUnsignedShort())
                8, 16, 19, 20 -> input.readUnsignedShort()
                3, 4, 9, 10, 11, 12, 17, 18 -> input.readInt()
                5, 6 -> { input.readLong(); i++ }
                15 -> { input.readUnsignedByte(); input.readUnsignedShort() }
                else -> error("unknown constant pool tag $tag")
            }
            i++
        }
        val out = HashSet<String>()
        for (index in classIndices) {
            val name = utf8[index] ?: continue
            if (name.startsWith("[")) DESCRIPTOR_TYPE.findAll(name).forEach { out.add(it.groupValues[1]) } else out.add(name)
        }
        for (text in utf8) {
            if (text != null && text.indexOf('L') >= 0 && text.indexOf(';') >= 0) DESCRIPTOR_TYPE.findAll(text).forEach { out.add(it.groupValues[1]) }
        }
        return out
    }

    private class Entry(val time: Long, val bytes: ByteArray?)

    private fun read(jar: File): LinkedHashMap<String, Entry> {
        val entries = LinkedHashMap<String, Entry>()
        ZipFile(jar).use { zip ->
            for (entry in zip.entries()) {
                entries[entry.name] = Entry(entry.time, if (entry.isDirectory) null else zip.getInputStream(entry).use { it.readBytes() })
            }
        }
        return entries
    }

    private fun write(jar: File, entries: Map<String, Entry>, drop: Set<String>) {
        if (drop.isEmpty()) return
        val buffer = ByteArrayOutputStream(jar.length().toInt())
        ZipOutputStream(buffer).use { out ->
            for ((name, entry) in entries) {
                if (name in drop) continue
                out.putNextEntry(ZipEntry(name).apply { time = entry.time })
                entry.bytes?.let(out::write)
                out.closeEntry()
            }
        }
        jar.writeBytes(buffer.toByteArray())
    }
}
