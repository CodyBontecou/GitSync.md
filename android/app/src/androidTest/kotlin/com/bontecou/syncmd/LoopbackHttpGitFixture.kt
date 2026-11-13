package com.bontecou.syncmd

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.PacketLineOut
import org.eclipse.jgit.transport.RefAdvertiser
import org.eclipse.jgit.transport.UploadPack
import java.io.*
import java.net.*
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream

/** In-emulator smart HTTP server: synthetic credentials and a fixture-owned bare repository only. */
internal class LoopbackHttpGitFixture(private val repository: File, private val lfsObjects: Map<String,ByteArray> = emptyMap()) : Closeable {
    private val listener = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val workers = Executors.newCachedThreadPool { task -> Thread(task, "owned-http-git").apply { isDaemon = true } }
    val lfsDownloads = AtomicInteger()
    val authenticated = AtomicInteger()
    val unexpected = AtomicInteger()
    val url = "http://127.0.0.1:${listener.localPort}/repo.git"
    init {
        workers.execute {
            while (!listener.isClosed) {
                val client = try { listener.accept() } catch (_: IOException) { break }
                workers.execute { client.use { serve(it) } }
            }
        }
    }
    private fun line(input: InputStream): String {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val next = input.read(); if (next < 0 || next == 10) break
            if (next != 13) bytes.write(next)
            check(bytes.size() <= 8192)
        }
        return bytes.toString("ISO-8859-1")
    }
    private fun exact(input: InputStream, length: Int): ByteArray {
        require(length in 0..4_194_304)
        val bytes=ByteArray(length); var offset=0
        while(offset<length) { val n=input.read(bytes,offset,length-offset);check(n>0);offset+=n }
        return bytes
    }
    private fun respond(socket: Socket, status: Int, body: ByteArray = byteArrayOf(), type: String = "text/plain", challenge: Boolean = false) {
        val output=socket.getOutputStream()
        output.write(("HTTP/1.1 $status Fixture\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n"+
            (if(challenge) "WWW-Authenticate: Basic realm=fixture\r\n" else "")+"\r\n").toByteArray(Charsets.ISO_8859_1))
        output.write(body);output.flush()
    }
    private fun body(input: InputStream, headers: Map<String,String>): ByteArray {
        var result = if(headers["transfer-encoding"]?.equals("chunked",true)==true) {
            val chunks=ByteArrayOutputStream()
            while(true) { val n=line(input).substringBefore(';').toInt(16);if(n==0){while(line(input).isNotEmpty()){};break};chunks.write(exact(input,n));check(chunks.size()<=4_194_304);check(line(input).isEmpty()) };chunks.toByteArray()
        } else exact(input,headers["content-length"]?.toInt() ?: 0)
        if(headers["content-encoding"]=="gzip")result=GZIPInputStream(result.inputStream()).use { it.readBytes() }
        return result
    }
    private fun serve(socket: Socket) {
        socket.soTimeout=15_000
        try {
            val input=BufferedInputStream(socket.getInputStream())
            val request=line(input).split(' ');require(request.size==3)
            val target=URI(request[1]);val headers=mutableMapOf<String,String>()
            while(true) { val next=line(input);if(next.isEmpty())break;headers[next.substringBefore(':').lowercase()]=next.substringAfter(':').trim();check(headers.size<100) }
            if (target.path !in listOf("/repo.git/info/refs","/repo.git/git-upload-pack","/repo.git/info/lfs/objects/batch") && !target.path.startsWith("/repo.git/info/lfs/objects/")) { unexpected.incrementAndGet();respond(socket,404);return }
            val expected="Basic "+Base64.getEncoder().encodeToString("fixture-user:fixture-password".toByteArray())
            val auth=headers["authorization"]
            if(auth!=expected) { if(auth!=null)unexpected.incrementAndGet();respond(socket,401,challenge=true);return }
            authenticated.incrementAndGet()
            if(target.path=="/repo.git/info/lfs/objects/batch") {
                val requestJson=com.google.gson.JsonParser.parseString(String(body(input,headers),Charsets.UTF_8)).asJsonObject
                check(requestJson.get("operation").asString=="download")
                val objects=com.google.gson.JsonArray()
                requestJson.getAsJsonArray("objects").forEach { raw ->
                    val descriptor=raw.asJsonObject;val oid=descriptor.get("oid").asString
                    val bytes=lfsObjects.getValue(oid);check(descriptor.get("size").asLong==bytes.size.toLong())
                    objects.add(com.google.gson.JsonObject().apply {
                        addProperty("oid",oid);addProperty("size",bytes.size)
                        add("actions",com.google.gson.JsonObject().apply { add("download",com.google.gson.JsonObject().apply { addProperty("href","$url/info/lfs/objects/$oid") }) })
                    })
                }
                val response=com.google.gson.JsonObject().apply { addProperty("transfer","basic");add("objects",objects) }
                respond(socket,200,response.toString().toByteArray(),"application/vnd.git-lfs+json");return
            }
            if(target.path.startsWith("/repo.git/info/lfs/objects/")) {
                lfsDownloads.incrementAndGet();respond(socket,200,lfsObjects.getValue(target.path.substringAfterLast('/')),"application/octet-stream");return
            }
            val payload=ByteArrayOutputStream()
            Git.open(repository).use { git -> UploadPack(git.repository).use { pack ->
                pack.isBiDirectionalPipe=false
                if(request[0]=="GET" && target.path.endsWith("info/refs")) {
                    val packets=PacketLineOut(payload);packets.writeString("# service=git-upload-pack\n");packets.end()
                    pack.sendAdvertisedRefs(RefAdvertiser.PacketLineOutRefAdvertiser(packets))
                } else {
                    val body = body(input,headers)
                    pack.upload(body.inputStream(),payload,null)
                }
            } }
            respond(socket,200,payload.toByteArray(),if(request[0]=="GET")"application/x-git-upload-pack-advertisement" else "application/x-git-upload-pack-result")
        } catch (_: Exception) { unexpected.incrementAndGet();runCatching { respond(socket,500) } }
    }
    override fun close() { listener.close();workers.shutdownNow();check(workers.awaitTermination(20,TimeUnit.SECONDS)) }
}
