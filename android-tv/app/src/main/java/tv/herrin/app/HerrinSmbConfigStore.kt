package tv.herrin.app

import android.content.Context
import org.json.JSONObject

/** URL, domain and credentials form one authenticated encrypted record. Public preferences hold only its revision. */
internal class HerrinSmbConfigStore(private val context:Context) {
    private val prefs=context.getSharedPreferences("herrin",0)
    private val secrets=HerrinPersonalCredentialStore(context,"smb")
    fun account():HerrinSmbAccount?=runCatching {
        val json=JSONObject(secrets.loadPayload()?:return@runCatching null)
        require(json.getString("revision")==prefs.getString("smb_revision",null))
        HerrinSmbAccount(json.getString("url"),json.getString("username"),json.getString("password"),
            json.getString("domain"),json.optBoolean("encryption"))
    }.getOrNull()
    fun configured()=prefs.contains("smb_revision")
    fun publicConfig():JSONObject = account()?.let{JSONObject().put("url",it.url).put("encryption",it.encryption).put("configured",true)}
        ?:JSONObject().put("url","").put("encryption",false).put("configured",false)
    fun save(input:JSONObject)=synchronized(lock) {
        val url=input.getString("url").trim()
        if(url.isBlank()){clear();return@synchronized}
        SmbLocation.parse(url)
        val old=account()?.takeIf{it.url==url}
        fun value(key:String,previous:String?)=input.optString(key).takeIf{it.isNotEmpty()}?:previous.orEmpty()
        val username=value("username",old?.username);val password=value("password",old?.password);val domain=value("domain",old?.domain)
        require(username.length<=256 && domain.length<=256 && password.length<=2048)
        require((username+domain).none{it.code<32})
        val revision=java.util.UUID.randomUUID().toString()
        val payload=JSONObject().put("revision",revision).put("url",url).put("username",username)
            .put("password",password).put("domain",domain).put("encryption",input.optBoolean("encryption",old?.encryption?:false)).toString()
        check(secrets.savePayload(payload))
        check(prefs.edit().putString("smb_revision",revision).commit()) // A mismatch fails closed after an incomplete update.
    }
    fun clear(){prefs.edit().remove("smb_revision").commit();secrets.clear()}
    private companion object{val lock=Any()}
}
