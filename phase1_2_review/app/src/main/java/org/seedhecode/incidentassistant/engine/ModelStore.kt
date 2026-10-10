package org.seedhecode.incidentassistant.engine

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream

data class ModelPack(val root: File, val manifest: JSONObject) {
    fun spec(role: String): JSONObject = manifest.getJSONObject("models").getJSONObject(role)
    fun file(name: String): File {
        require(name.isNotBlank() && !File(name).isAbsolute)
        val file = File(root, name).canonicalFile
        require(file.path.startsWith(root.canonicalFile.path + File.separator)) {
            "Model path escapes its bundle."
        }
        return file
    }
    fun artifact(role: String) = file(spec(role).getString("file"))
    fun versions() = listOf("asr", "cfc", "laya", "llm").associateWith {
        spec(it).getString("version") + " / " + spec(it).getString("backend")
    }
}

/** Bundles are imported atomically into private storage; never downloaded at runtime. */
class ModelStore(private val context: Context) {
    private val root = File(context.filesDir, "models")
    fun load(): ModelPack? = if (File(root, "manifest.json").isFile) validate(root) else null

    fun import(uri: Uri): ModelPack {
        val staging = File(context.filesDir, "models-import")
        staging.deleteRecursively()
        check(staging.mkdirs())
        try {
            var total = 0L
            var count = 0
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ZipInputStream(stream).use { zip ->
                    var entry = zip.nextEntry
                    val buffer = ByteArray(128 * 1024)
                    while (entry != null) {
                        require(++count <= 128) { "Too many files in model bundle." }
                        val target = ModelPack(staging, JSONObject()).file(entry.name)
                        if (entry.isDirectory) { check(target.isDirectory || target.mkdirs()) }
                        else {
                            check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
                            target.outputStream().use { output ->
                                var read = zip.read(buffer)
                                while (read >= 0) {
                                    if (read > 0) { total += read; require(total <= 6L * 1024 * 1024 * 1024)
                                        output.write(buffer, 0, read) }
                                    read = zip.read(buffer)
                                }
                            }
                        }
                        zip.closeEntry(); entry = zip.nextEntry
                    }
                }
            } ?: error("Cannot open model bundle.")
            validate(staging)
            val backup = File(context.filesDir, "models-previous")
            backup.deleteRecursively()
            if (root.exists()) check(root.renameTo(backup)) { "Cannot archive current model pack." }
            if (!staging.renameTo(root)) {
                backup.renameTo(root)
                error("Cannot activate model pack.")
            }
            backup.deleteRecursively()
            return validate(root)
        } catch (error: Exception) { staging.deleteRecursively(); throw error }
    }

    private fun validate(directory: File): ModelPack {
        val manifestFile = File(directory, "manifest.json")
        require(manifestFile.length() in 1..262144) { "Missing or oversized manifest.json." }
        val pack = ModelPack(directory, JSONObject(manifestFile.readText()))
        require(pack.manifest.getInt("contract_version") == 1)
        val files = pack.manifest.getJSONObject("sha256")
        val names = files.keys().asSequence().toList()
        require(names.isNotEmpty())
        for (name in names) {
            val file = pack.file(name)
            require(file.isFile && file.length() > 0) { "Missing artifact: $name" }
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(128 * 1024)
                var read = input.read(buffer)
                while (read >= 0) { if (read > 0) digest.update(buffer, 0, read); read = input.read(buffer) }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            require(actual == files.getString(name).lowercase()) { "Checksum mismatch: $name" }
        }
        for (role in listOf("asr", "cfc", "laya", "llm")) {
            val spec = pack.spec(role)
            require(spec.getString("version").isNotBlank() && !spec.getString("version").contains("replace-with")) {
                "Provide a real trained artifact version for $role."
            }
            val family = mapOf("asr" to "Whisper", "llm" to "Qwen3", "cfc" to "CfC", "laya" to "Laya").getValue(role)
            require(spec.getString("family") == family) { "Unsupported model family for $role." }
            if (role == "asr") require(spec.getBoolean("multilingual"))
            require(files.has(spec.getString("file"))) { "Missing checksum for $role" }
            val expected = if (role == "asr") "whisper.cpp-cpu" else if (role == "llm") "llama.cpp-cpu" else "onnx-cpu"
            require(spec.getString("backend") == expected) { "Unsupported backend for $role; no silent fallback." }
            if (role == "cfc" || role == "laya") {
                require(spec.getString("quantization") == "int8") { "M1 requires INT8 CfC/Laya exports." }
                require(spec.getString("export_contract") == "word_ids_mask_v1") {
                    "Export must declare its tokenizer and input contract; do not guess preprocessing."
                }
                require(files.has(spec.getString("vocabulary")))
                require(spec.getInt("max_tokens") in 8..2048)
            }
        }
        return pack
    }
}
