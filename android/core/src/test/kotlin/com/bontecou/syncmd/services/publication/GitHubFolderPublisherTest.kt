package com.bontecou.syncmd.services.publication

import com.google.common.truth.Truth.assertThat
import com.google.gson.*
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.*
import java.net.*
import java.io.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** Real HTTP I/O on loopback; a trusted connection adapter keeps the logical production API URL. */
class GitHubFolderPublisherTest {
    private data class Reply(val status:Int,val body:String,val headers:Map<String,List<String>> = emptyMap(),val drop:Boolean=false)
    private data class Request(val method:String,val path:String,val rawPath:String,val query:String?,val body:String,val auth:String?)
    private lateinit var server:HttpServer
    private val executor=Executors.newCachedThreadPool()
    private val requests=CopyOnWriteArrayList<Request>()
    private val connections=CopyOnWriteArrayList<FixtureConnection>()
    private lateinit var client:GitHubFolderPublisher
    private var privateRepo=true
    private var handler:((Request)->Reply)?=null
    private val account=PublicationAccount(17,"fixture")
    private val token="fixture-publication-token"
    private fun user(id:Long=17,login:String="fixture")=JsonObject().apply { addProperty("id",id);addProperty("login",login) }
    private fun repo():JsonObject=JsonObject().apply {
        addProperty("id",92);add("owner",user());addProperty("name","reviewed");addProperty("private",privateRepo)
        addProperty("html_url","https://github.com/fixture/reviewed");addProperty("clone_url","https://github.com/fixture/reviewed.git")
    }
    private fun ref(name:String="refs/heads/main",oid:String="a".repeat(40),type:String="commit")=JsonObject().apply {
        addProperty("ref",name);add("object",JsonObject().apply { addProperty("sha",oid);addProperty("type",type) })
    }
    private fun default(request:Request):Reply=when {
        request.path=="/user" ->Reply(200,user().toString())
        request.path=="/user/repos" -> { privateRepo=JsonParser.parseString(request.body).asJsonObject.get("private").asBoolean;Reply(201,repo().toString()) }
        request.path=="/repos/fixture/reviewed" ->Reply(200,repo().toString())
        request.path.endsWith("/git/matching-refs/") ->Reply(200,"[]")
        request.path.contains("/git/ref/heads/") ->Reply(404,"{\"message\":\"Not Found\"}")
        else ->Reply(500,"{}")
    }
    @Before fun setup() {
        server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0);server.executor=executor
        server.createContext("/") { exchange ->
            try {
                val request=Request(exchange.requestMethod,exchange.requestURI.path,exchange.requestURI.rawPath,exchange.requestURI.rawQuery,
                    exchange.requestBody.bufferedReader().use { it.readText() },exchange.requestHeaders.getFirst("Authorization"))
                requests+=request;val response=handler?.invoke(request) ?: default(request)
                if(!response.drop) {
                    response.headers.forEach { (name,values) ->values.forEach { exchange.responseHeaders.add(name,it) } }
                    val bytes=response.body.toByteArray();exchange.sendResponseHeaders(response.status,bytes.size.toLong());exchange.responseBody.write(bytes)
                }
            } catch(_:Exception) { /* Deliberately closed clients are expected for bounded/uncertain replies. */ }
            finally { exchange.close() }
        }
        server.start()
        client=GitHubFolderPublisher { logical ->
            check(logical.protocol=="https" && logical.host=="api.github.com")
            FixtureConnection(logical,URL("http://127.0.0.1:${server.address.port}${logical.file}").openConnection() as HttpURLConnection).also { connections+=it }
        }
    }
    @After fun cleanup() { server.stop(0);executor.shutdownNow() }
    private class FixtureConnection(logical:URL,private val delegate:HttpURLConnection):HttpURLConnection(logical) {
        var streamed=false
        override fun disconnect()=delegate.disconnect()
        override fun usingProxy()=delegate.usingProxy()
        override fun connect()=delegate.connect()
        override fun setConnectTimeout(value:Int) { super.setConnectTimeout(value);delegate.connectTimeout=value }
        override fun setReadTimeout(value:Int) { super.setReadTimeout(value);delegate.readTimeout=value }
        override fun setInstanceFollowRedirects(value:Boolean) { super.setInstanceFollowRedirects(value);delegate.instanceFollowRedirects=value }
        override fun setRequestMethod(value:String) { super.setRequestMethod(value);delegate.requestMethod=value }
        override fun setDoOutput(value:Boolean) { super.setDoOutput(value);delegate.doOutput=value }
        override fun setRequestProperty(name:String,value:String) { super.setRequestProperty(name,value);delegate.setRequestProperty(name,value) }
        override fun setFixedLengthStreamingMode(value:Int) { super.setFixedLengthStreamingMode(value);delegate.setFixedLengthStreamingMode(value);streamed=true }
        override fun getOutputStream():OutputStream=delegate.outputStream
        override fun getInputStream():InputStream=delegate.inputStream
        override fun getErrorStream():InputStream?=delegate.errorStream
        override fun getResponseCode():Int=delegate.responseCode
        override fun getHeaderField(name:String?):String?=delegate.getHeaderField(name)
        override fun getHeaderFields():Map<String,List<String>> = delegate.headerFields
    }
    private fun problem(failure:Throwable?)=(failure as PublicationApiException).problem
    @Test fun `private and public creation send explicit visibility without initializing remote history`() = runBlocking {
        for(visibility in listOf(true,false)) {
            val created=client.create(account,"reviewed",visibility,token)
            assertThat(created.isPrivate).isEqualTo(visibility);assertThat(created.owner.id).isEqualTo(17)
        }
        val posts=requests.filter { it.method=="POST" };assertThat(posts).hasSize(2)
        posts.forEach { request ->
            val body=JsonParser.parseString(request.body).asJsonObject
            assertThat(body.get("auto_init").asBoolean).isFalse();assertThat(body.get("name").asString).isEqualTo("reviewed")
            assertThat(request.auth).isEqualTo("Bearer $token")
        }
        connections.forEach { assertThat(it.instanceFollowRedirects).isFalse();assertThat(it.connectTimeout).isEqualTo(15000);assertThat(it.readTimeout).isEqualTo(30000) }
        assertThat(connections.filter { it.requestMethod=="POST" }.all { it.streamed }).isTrue()
    }
    @Test fun `account identity mismatch stops before repository creation`() = runBlocking {
        assertThat(problem(runCatching { client.create(account.copy(id=18),"reviewed",true,token) }.exceptionOrNull())).isEqualTo(PublicationApiProblem.ACCOUNT)
        assertThat(problem(runCatching { client.create(account.copy(login="other"),"reviewed",true,token) }.exceptionOrNull())).isEqualTo(PublicationApiProblem.ACCOUNT)
        assertThat(requests.none { it.method=="POST" }).isTrue()
    }
    @Test fun `malformed or mismatched creation acknowledgement remains unknown`() = runBlocking {
        val replies=listOf(
            repo().apply { remove("id") }.toString(),repo().apply { addProperty("private",false) }.toString(),
            repo().apply { addProperty("name","other") }.toString(),repo().apply { add("owner",user(18)) }.toString(),
            repo().apply { addProperty("clone_url","https://other.example/fixture/reviewed.git") }.toString(),
            repo().apply { addProperty("clone_url","https://token@github.com/fixture/reviewed.git") }.toString(),
            repo().apply { addProperty("id","92") }.toString(),repo().toString().replace("\"id\":92","\"id\":92,\"id\":93"),"{broken"
        )
        for(body in replies) {
            handler={if(it.method=="POST")Reply(201,body) else default(it)}
            val failure=runCatching { client.create(account,"reviewed",true,token) }.exceptionOrNull()
            assertThat(problem(failure)).isEqualTo(PublicationApiProblem.CREATION_UNKNOWN);assertThat(failure!!.message).doesNotContain(token)
        }
        assertThat(requests.count { it.method=="POST" }).isEqualTo(replies.size)
    }
    @Test fun `a dropped creation reply is never automatically replayed by the HTTP stack`() = runBlocking {
        handler={if(it.method=="POST")Reply(201,"",drop=true) else default(it)}
        assertThat(problem(runCatching { client.create(account,"reviewed",true,token) }.exceptionOrNull())).isEqualTo(PublicationApiProblem.CREATION_UNKNOWN)
        assertThat(requests.count { it.method=="POST" }).isEqualTo(1)
    }
    @Test fun `redirect server error timeout and wrong success status never confirm creation`() = runBlocking {
        for(code in listOf(302,408,499,500,200,202,204)) {
            handler={if(it.method=="POST")Reply(code,repo().toString(),mapOf("Location" to listOf("http://127.0.0.1:${server.address.port}/escaped"))) else default(it)}
            assertThat(problem(runCatching { client.create(account,"reviewed",true,token) }.exceptionOrNull())).isEqualTo(PublicationApiProblem.CREATION_UNKNOWN)
        }
        assertThat(requests.none { it.path=="/escaped" }).isTrue()
    }
    @Test fun `known creation rejections and rate limits expose only static messages`() = runBlocking {
        for(code in listOf(400,401,403,404,409,422,429,451)) {
            handler={if(it.method=="POST")Reply(code,"{\"message\":\"$token\"}",if(code==429)mapOf("Retry-After" to listOf("60")) else emptyMap()) else default(it)}
            val failure=runCatching { client.create(account,"reviewed",true,token) }.exceptionOrNull() as PublicationApiException
            assertThat(failure.problem).isNotEqualTo(PublicationApiProblem.CREATION_UNKNOWN);assertThat(failure.message).doesNotContain(token)
            if(code==429)assertThat(failure.retryAfterSeconds).isEqualTo(60)
        }
    }
    @Test fun `repository 404 is unavailable and branch 404 needs readable references`() = runBlocking {
        handler={if(it.path=="/repos/fixture/reviewed")Reply(404,"{}") else default(it)}
        assertThat(problem(runCatching { client.repository(account,"reviewed",token) }.exceptionOrNull())).isEqualTo(PublicationApiProblem.UNAVAILABLE)
        handler=null;val remote=client.repository(account,"reviewed",token)
        handler={if(it.path.endsWith("/git/matching-refs/"))Reply(403,"{}") else default(it)}
        assertThat(problem(runCatching { client.branch(remote,"main",token) }.exceptionOrNull())).isEqualTo(PublicationApiProblem.FORBIDDEN)
        handler=null;assertThat(client.branch(remote,"main",token)).isNull()
    }
    @Test fun `empty repository conflict is accepted only with exact message and fresh metadata access`() = runBlocking {
        val remote=client.repository(account,"reviewed",token)
        handler={if(it.path.endsWith("/git/matching-refs/"))Reply(409,"{\"message\":\"Git Repository is empty.\"}") else default(it)}
        assertThat(client.references(remote,token)).isEmpty()
        handler={if(it.path.endsWith("/git/matching-refs/"))Reply(409,"{\"message\":\"Conflict\"}") else default(it)}
        assertThat(problem(runCatching { client.references(remote,token) }.exceptionOrNull())).isEqualTo(PublicationApiProblem.RESPONSE)
        var metadata=0
        handler={when { it.path=="/repos/fixture/reviewed" ->if(++metadata==1)default(it) else Reply(403,"{}")
            it.path.endsWith("/git/matching-refs/") ->Reply(409,"{\"message\":\"Git Repository is empty.\"}")
            else ->default(it) }}
        assertThat(problem(runCatching { client.references(remote,token) }.exceptionOrNull())).isEqualTo(PublicationApiProblem.FORBIDDEN)
    }
    @Test fun `complete paginated namespace includes tags and later branches`() = runBlocking {
        val remote=client.repository(account,"reviewed",token)
        handler={if(it.path.endsWith("/git/matching-refs/")) {
            if(it.query==null)Reply(200,"[${ref("refs/tags/tag",type="tag")} ]",mapOf("Link" to listOf("<https://api.github.com/repos/fixture/reviewed/git/matching-refs/?page=2>; rel=next","<https://api.github.com/repos/fixture/reviewed/git/matching-refs/?page=2>; rel=last")))
            else Reply(200,"[${ref()}]")
        } else default(it)}
        assertThat(client.references(remote,token).map { it.name }).containsExactly("refs/tags/tag","refs/heads/main").inOrder()
        assertThat(client.branch(remote,"main",token)).isEqualTo("a".repeat(40))
        Unit
    }
    @Test fun `pagination cannot escape the repository repeat a page or duplicate identities`() = runBlocking {
        val remote=client.repository(account,"reviewed",token)
        for(link in listOf("https://other.example/escape?page=2","https://api.github.com/repos/other/reviewed/git/matching-refs/?page=2","https://api.github.com/repos/fixture/reviewed/git/matching-refs/?page=2&page=3")) {
            handler={if(it.path.endsWith("/git/matching-refs/"))Reply(200,"[]",mapOf("Link" to listOf("<$link>; rel=\"next\""))) else default(it)}
            assertThat(problem(runCatching { client.references(remote,token) }.exceptionOrNull())).isEqualTo(PublicationApiProblem.RESPONSE)
        }
        handler={if(it.path.endsWith("/git/matching-refs/"))Reply(200,"[${ref()}]",mapOf("Link" to listOf("<https://api.github.com/repos/fixture/reviewed/git/matching-refs/?page=2>; rel=next"))) else default(it)}
        assertThat(problem(runCatching { client.references(remote,token) }.exceptionOrNull())).isEqualTo(PublicationApiProblem.RESPONSE)
        assertThat(requests.none { it.path.startsWith("/repos/other") || it.path=="/escape" }).isTrue()
    }
    @Test fun `exact Unicode branch reference is encoded and prefix matches cannot substitute for it`() = runBlocking {
        val remote=client.repository(account,"reviewed",token);val branch="feature/你好"
        handler={if(it.path.contains("/git/ref/heads/"))Reply(200,ref("refs/heads/$branch",oid="A".repeat(40)).toString()) else default(it)}
        assertThat(client.branch(remote,branch,token)).isEqualTo("a".repeat(40))
        assertThat(requests.last().rawPath).contains("%E4%BD%A0")
        handler={if(it.path.contains("/git/ref/heads/"))Reply(200,ref("refs/heads/feature").toString()) else default(it)}
        assertThat(problem(runCatching { client.branch(remote,branch,token) }.exceptionOrNull())).isEqualTo(PublicationApiProblem.RESPONSE)
    }
    @Test fun `bad tokens duplicate identities and oversized replies fail before any ambiguous reuse`() = runBlocking {
        for(value in listOf("", " fixture ","fixture\r\nInjected: bad"))assertThat(problem(runCatching { client.account(value) }.exceptionOrNull())).isEqualTo(PublicationApiProblem.AUTHENTICATION)
        assertThat(requests).isEmpty()
        handler={Reply(200,"{\"id\":17,\"id\":18,\"login\":\"fixture\"}")}
        assertThat(problem(runCatching { client.account(token) }.exceptionOrNull())).isEqualTo(PublicationApiProblem.RESPONSE)
        handler={Reply(200," ".repeat(2*1024*1024+1))}
        assertThat(problem(runCatching { client.account(token) }.exceptionOrNull())).isEqualTo(PublicationApiProblem.RESPONSE)
    }
}
