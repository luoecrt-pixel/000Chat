package com.example.openaichat

import android.content.Context
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

class SecureStore(context: Context) {
    private val prefs=context.getSharedPreferences("secure",Context.MODE_PRIVATE)
    private val alias="OpenAIApiChatKey"

    private fun key():SecretKey{
        val ks=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        (ks.getKey(alias,null) as? SecretKey)?.let{return it}
        val gen=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return gen.generateKey()
    }

    private fun setSecret(name:String, value:String){
        if(value.isBlank()){
            prefs.edit().remove(name).apply()
            return
        }
        val c=Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE,key())
        val p=c.iv+c.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(name,Base64.encodeToString(p,Base64.NO_WRAP)).apply()
    }

    private fun getSecret(name:String):String{
        return try{
            val p=Base64.decode(prefs.getString(name,null)?:return "",Base64.NO_WRAP)
            if(p.size<13)return ""
            val iv=p.copyOfRange(0,12)
            val d=p.copyOfRange(12,p.size)
            val c=Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,iv))
            String(c.doFinal(d),Charsets.UTF_8)
        }catch(_:Exception){""}
    }

    fun setApiKey(v:String)=setSecret("key",v)
    fun getApiKey():String=getSecret("key")

    fun setOpenRouterApiKey(v:String)=setSecret("openrouter_key",v)
    fun getOpenRouterApiKey():String=getSecret("openrouter_key")

    fun setGitHubToken(v:String)=setSecret("github_pat",v)
    fun getGitHubToken():String=getSecret("github_pat")
}
