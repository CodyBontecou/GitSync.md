package com.bontecou.syncmd.services.publication

import com.google.gson.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.eclipse.jgit.lib.Repository
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder

// These immutable identities, never tokens, are retained in publication progress.
data class PublicationAccount(val id:Long,val login:String)
data class PublicationRemote(val id:Long,val owner:PublicationAccount,val name:String,val isPrivate:Boolean,val htmlUrl:String,val cloneUrl:String)
data class PublicationRef(val name:String,val oid:String,val type:String)
enum class PublicationApiProblem { AUTHENTICATION, ACCOUNT, FORBIDDEN, RATE_LIMIT, VALIDATION, UNAVAILABLE, RESPONSE, DESTINATION, CREATION_UNKNOWN }
class PublicationApiException(val problem:PublicationApiProblem,val status:Int?=null,val retryAfterSeconds:Long?=null):IOException(when(problem) {
    PublicationApiProblem.AUTHENTICATION -> "GitHub authentication expired or was denied. Sign in again."
    PublicationApiProblem.ACCOUNT -> "Sign in to the GitHub account selected for this publication."
    PublicationApiProblem.FORBIDDEN -> "GitHub denied access. Check account permissions and policy."
    PublicationApiProblem.RATE_LIMIT -> "GitHub rate limited this request. Wait before retrying."
    PublicationApiProblem.VALIDATION -> "GitHub rejected the repository name or creation request. Review the destination."
    PublicationApiProblem.UNAVAILABLE -> "The repository is unavailable or this account cannot access it. Its absence is not confirmed."
    PublicationApiProblem.RESPONSE -> "GitHub returned an unreadable or incomplete response. No destination was confirmed."
    PublicationApiProblem.DESTINATION -> "GitHub returned a different repository, account or visibility. Review the original destination."
    PublicationApiProblem.CREATION_UNKNOWN -> "GitHub may have created the repository. Reconcile the original destination before trying another creation."
})

interface FolderPublicationPublisher {
    suspend fun account(token:String):PublicationAccount
    suspend fun create(account:PublicationAccount,name:String,isPrivate:Boolean,token:String):PublicationRemote
    suspend fun repository(account:PublicationAccount,name:String,token:String):PublicationRemote
    suspend fun references(remote:PublicationRemote,token:String):List<PublicationRef>
    suspend fun branch(remote:PublicationRemote,name:String,token:String):String?
}

/** Credentials are sent only to the fixed API origin. No server URL is used as a request target. */
class GitHubFolderPublisher(private val connections:(URL)->HttpURLConnection = { it.openConnection() as HttpURLConnection }):FolderPublicationPublisher {
    private class Reply(val status:Int,val bytes:ByteArray,val link:String?,val retryAfter:String?,val remaining:String?)
    override suspend fun account(token:String):PublicationAccount = withContext(Dispatchers.IO) {
        decode { val user=json(request("/user",token));PublicationAccount(positive(user.get("id")),segment(PublicationJson.text(user.get("login")))) }
    }
    override suspend fun create(account:PublicationAccount,name:String,isPrivate:Boolean,token:String):PublicationRemote = withContext(Dispatchers.IO) {
        validAccount(account);segment(name)
        if(!same(account,account(token)))throw PublicationApiException(PublicationApiProblem.ACCOUNT)
        val body=JsonObject().apply { addProperty("name",name);addProperty("private",isPrivate);addProperty("auto_init",false) }.toString().toByteArray(Charsets.UTF_8)
        try {
            val response=request("/user/repos",token,body=body)
            if(response.status!=201)throw PublicationApiException(PublicationApiProblem.CREATION_UNKNOWN,response.status)
            remote(json(response),account,name).also { if(it.isPrivate!=isPrivate)throw PublicationApiException(PublicationApiProblem.DESTINATION) }
        } catch(failure:Exception) {
            if(failure is PublicationApiException && failure.problem !in listOf(PublicationApiProblem.RESPONSE,PublicationApiProblem.DESTINATION,PublicationApiProblem.CREATION_UNKNOWN))throw failure
            throw PublicationApiException(PublicationApiProblem.CREATION_UNKNOWN,(failure as? PublicationApiException)?.status)
        }
    }
    override suspend fun repository(account:PublicationAccount,name:String,token:String):PublicationRemote = withContext(Dispatchers.IO) {
        validAccount(account);segment(name)
        decode { remote(json(request(repoPath(account,name),token)),account,name) }
    }
    override suspend fun references(remote:PublicationRemote,token:String):List<PublicationRef> = withContext(Dispatchers.IO) {
        validRemote(remote)
        if(repository(remote.owner,remote.name,token)!=remote)throw PublicationApiException(PublicationApiProblem.DESTINATION)
        val base=repoPath(remote.owner,remote.name)+"/git/matching-refs/"
        var path=base
        val visited=mutableSetOf<String>();val seen=mutableSetOf<String>();val result=mutableListOf<PublicationRef>()
        while(true) {
            if(!visited.add(path) || visited.size>1000)throw PublicationApiException(PublicationApiProblem.RESPONSE)
            val reply=request(path,token,allowed=setOf(409))
            if(reply.status==409) {
                if(visited.size!=1 || !empty(reply) || reply.link!=null)throw PublicationApiException(PublicationApiProblem.RESPONSE,409)
                if(repository(remote.owner,remote.name,token)!=remote)throw PublicationApiException(PublicationApiProblem.DESTINATION)
                return@withContext emptyList()
            }
            val entries=decode {
                val value=PublicationJson.parse(reply.bytes);require(value.isJsonArray)
                value.asJsonArray.map { reference(it.asJsonObject) }
            }
            entries.forEach { if(!seen.add(it.name) || result.size>=100000)throw PublicationApiException(PublicationApiProblem.RESPONSE);result+=it }
            path=next(reply.link,base) ?: break
        }
        result.toList()
    }
    override suspend fun branch(remote:PublicationRemote,name:String,token:String):String? = withContext(Dispatchers.IO) {
        validRemote(remote)
        require(Repository.isValidRefName("refs/heads/$name")) { "Choose a valid branch" }
        if(repository(remote.owner,remote.name,token)!=remote)throw PublicationApiException(PublicationApiProblem.DESTINATION)
        val response=request(repoPath(remote.owner,remote.name)+"/git/ref/heads/"+name.split('/').joinToString("/") { encode(it) },token,allowed=setOf(404,409))
        if(response.status==200) return@withContext decode { reference(json(response)).also { require(it.name=="refs/heads/$name" && it.type=="commit") }.oid }
        if(response.status==409 && !empty(response))throw PublicationApiException(PublicationApiProblem.RESPONSE,409)
        references(remote,token).singleOrNull { it.name=="refs/heads/$name" }?.also { if(it.type!="commit")throw PublicationApiException(PublicationApiProblem.RESPONSE) }?.oid
    }
    private suspend fun request(path:String,token:String,body:ByteArray?=null,allowed:Set<Int> = emptySet()):Reply {
        if(token.isBlank() || token!=token.trim() || token.length>16384 || token.any { it.isISOControl() || it.isWhitespace() })throw PublicationApiException(PublicationApiProblem.AUTHENTICATION)
        require(path.startsWith('/') && !path.startsWith("//") && path.none { it.isISOControl() || it=='\\' })
        val target=URL("https://api.github.com$path")
        val operation=currentCoroutineContext();operation.ensureActive()
        val connection=try { connections(target) } catch(_:Exception) { throw PublicationApiException(if(body==null) PublicationApiProblem.RESPONSE else PublicationApiProblem.CREATION_UNKNOWN) }
        try {
            // Streaming a fixed-length POST prevents the HTTP stack from replaying it after a lost response.
            connection.instanceFollowRedirects=false;connection.connectTimeout=15000;connection.readTimeout=30000
            connection.requestMethod=if(body==null) "GET" else "POST"
            connection.setRequestProperty("Authorization","Bearer $token")
            connection.setRequestProperty("Accept","application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version","2022-11-28")
            connection.setRequestProperty("User-Agent","GitSync.md")
            if(body!=null) {
                connection.doOutput=true;connection.setRequestProperty("Content-Type","application/json")
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val status=connection.responseCode
            if(connection.url.toExternalForm()!=target.toExternalForm())throw PublicationApiException(PublicationApiProblem.RESPONSE)
            val stream=if(status in 200..299)connection.inputStream else connection.errorStream
            val bytes=stream?.use { input ->
                val output=ByteArrayOutputStream();val buffer=ByteArray(8192)
                while(true) { operation.ensureActive();val count=input.read(buffer);if(count==-1)break;if(output.size()+count>2*1024*1024)throw PublicationApiException(PublicationApiProblem.RESPONSE);output.write(buffer,0,count) }
                output.toByteArray()
            } ?: byteArrayOf()
            val links=connection.headerFields.filterKeys { it?.equals("Link",true)==true }.values.flatten().takeIf { it.isNotEmpty() }?.joinToString(",")
            val reply=Reply(status,bytes,links,connection.getHeaderField("Retry-After"),connection.getHeaderField("X-RateLimit-Remaining"))
            if(status!=(if(body==null)200 else 201) && status !in allowed) {
                if(body!=null && status !in setOf(400,401,403,404,409,422,429,451))throw PublicationApiException(PublicationApiProblem.CREATION_UNKNOWN,status)
                throw error(reply)
            }
            return reply
        } catch(failure:Exception) {
            if(failure is PublicationApiException)throw failure
            if(body!=null)throw PublicationApiException(PublicationApiProblem.CREATION_UNKNOWN)
            if(failure is kotlinx.coroutines.CancellationException)throw failure
            throw PublicationApiException(PublicationApiProblem.RESPONSE)
        } finally { connection.disconnect() }
    }
    private fun error(reply:Reply)=PublicationApiException(when(reply.status) {
        401 -> PublicationApiProblem.AUTHENTICATION
        403 -> if(reply.remaining=="0" || reply.retryAfter!=null) PublicationApiProblem.RATE_LIMIT else PublicationApiProblem.FORBIDDEN
        429 -> PublicationApiProblem.RATE_LIMIT
        400,409,422,451 -> PublicationApiProblem.VALIDATION
        404 -> PublicationApiProblem.UNAVAILABLE
        else -> PublicationApiProblem.RESPONSE
    },reply.status,reply.retryAfter?.toLongOrNull()?.takeIf { it in 0..86400 })
    private fun json(reply:Reply):JsonObject = decode { PublicationJson.parse(reply.bytes).asJsonObject }
    private fun remote(value:JsonObject,expected:PublicationAccount,name:String):PublicationRemote {
        val owner=value.getAsJsonObject("owner")
        val actual=PublicationAccount(positive(owner.get("id")),segment(PublicationJson.text(owner.get("login"))))
        val actualName=segment(PublicationJson.text(value.get("name")))
        if(!same(actual,expected) || !actualName.equals(name,true))throw PublicationApiException(PublicationApiProblem.DESTINATION)
        return PublicationRemote(positive(value.get("id")),actual,actualName,PublicationJson.bool(value.get("private")),PublicationJson.text(value.get("html_url")),PublicationJson.text(value.get("clone_url"))).also(::validRemote)
    }
    private fun reference(value:JsonObject):PublicationRef {
        val name=PublicationJson.text(value.get("ref"));require(Repository.isValidRefName(name) && name.startsWith("refs/") && name.length<=4096)
        val target=value.getAsJsonObject("object");val oid=PublicationJson.text(target.get("sha"));val type=PublicationJson.text(target.get("type"))
        require(oid.matches(Regex("[a-fA-F0-9]{40}|[a-fA-F0-9]{64}")) && type in setOf("commit","tag","tree","blob"))
        return PublicationRef(name,oid.lowercase(),type)
    }
    private fun empty(reply:Reply)=runCatching { PublicationJson.text(json(reply).get("message")).trim().equals("Git Repository is empty.",true) }.getOrDefault(false)
    private fun next(header:String?,base:String):String? {
        if(header==null)return null
        if(header.length>32768)throw PublicationApiException(PublicationApiProblem.RESPONSE)
        val links=decode { header.split(',').map { part ->
            val target=Regex("^\\s*<([^>]+)>").find(part) ?: error("Malformed link")
            val relations=Regex(";\\s*rel\\s*=\\s*(?:\"([^\"]+)\"|([^;\\s]+))",RegexOption.IGNORE_CASE).findAll(part).toList()
            require(relations.size==1)
            val names=(relations.single().groupValues[1].ifEmpty { relations.single().groupValues[2] }).split(' ').filter { it.isNotEmpty() }
            target.groupValues[1] to names
        }.filter { (_,relations) -> relations.any { it.equals("next",true) } } }
        if(links.isEmpty())return null
        if(links.size!=1)throw PublicationApiException(PublicationApiProblem.RESPONSE)
        return decode {
            val target=URI(links.single().first)
            require(target.scheme=="https" && target.host=="api.github.com" && target.port==-1 && target.rawUserInfo==null && target.rawFragment==null && target.rawPath==base)
            val query=target.rawQuery ?: error("Missing page")
            val parts=query.split('&').map { it.split('=',limit=2) }
            require(parts.all { it.size==2 && it[0] in setOf("page","per_page") && it[1].matches(Regex("[1-9][0-9]{0,5}")) } && parts.map { it[0] }.distinct().size==parts.size && parts.any { it[0]=="page" })
            base+"?"+query
        }
    }
    private fun repoPath(account:PublicationAccount,name:String)="/repos/${encode(account.login)}/${encode(name)}"
    private fun encode(value:String)=URLEncoder.encode(value,"UTF-8").replace("+","%20")
    private fun same(a:PublicationAccount,b:PublicationAccount)=a.id==b.id && a.login.equals(b.login,true)
    private fun positive(value:JsonElement?)=PublicationJson.integer(value).also { require(it>0) }
    private fun segment(value:String)=value.also { require(it!="." && it!=".." && it.matches(Regex("[A-Za-z0-9_.-]{1,100}"))) }
    private fun validAccount(account:PublicationAccount) { require(account.id>0);segment(account.login) }
    private fun validRemote(remote:PublicationRemote) {
        validAccount(remote.owner);segment(remote.name);require(remote.id>0)
        val expected="/${remote.owner.login}/${remote.name}"
        for((value,path) in listOf(remote.htmlUrl to expected,remote.cloneUrl to "$expected.git")) {
            val uri=URI(value)
            if(uri.scheme!="https" || uri.host!="github.com" || uri.port!=-1 || uri.rawUserInfo!=null || uri.rawQuery!=null || uri.rawFragment!=null || !uri.rawPath.equals(path,true))throw PublicationApiException(PublicationApiProblem.DESTINATION)
        }
    }
    private inline fun <T> decode(body:()->T):T = try { body() } catch(e:PublicationApiException) { throw e } catch(_:Exception) { throw PublicationApiException(PublicationApiProblem.RESPONSE) }
}
