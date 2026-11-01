package com.fongmi.android.tv.server.cloud;
import com.github.catvod.net.OkHttp;
import android.util.Base64;
import org.json.JSONObject;
import okhttp3.*;
import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.*;
/** UC TV OAuth protocol; credentials remain in the encrypted account store. */
public final class CloudUcTv {
 static final String API="https://open-api-drive.uc.cn",CLIENT="5acf882d27b74502b7040b0c65519aa7",SIGN="l3srvtd7p42l0d0x1u8d7yc8ye9kki4d",DEVICE="07b48aaba8a739356ab8107b5e230ad4";
 static String digest(String alg,String v)throws Exception{return hex(MessageDigest.getInstance(alg).digest(v.getBytes(StandardCharsets.UTF_8)));}
 static JSONObject params()throws Exception{String t=String.valueOf(System.currentTimeMillis());return new JSONObject().put("req_id",digest("MD5",DEVICE+t).substring(0,16)).put("app_ver","1.6.8").put("device_id",DEVICE).put("device_brand","Xiaomi").put("platform","tv").put("device_name","M2004J7AC").put("device_model","M2004J7AC").put("build_device","M2004J7AC").put("build_product","M2004J7AC").put("device_gpu","Adreno (TM) 550").put("activity_rect","{}").put("channel","UCTVOFFICIALWEB");}
 static JSONObject get(String route,JSONObject extra)throws Exception{JSONObject p=params();for(String k:keys(extra))p.put(k,extra.get(k));StringBuilder q=new StringBuilder();for(String k:keys(p)){if(q.length()>0)q.append('&');q.append(URLEncoder.encode(k,"UTF-8")).append('=').append(URLEncoder.encode(p.optString(k),"UTF-8"));}String t=String.valueOf(System.currentTimeMillis());Request r=new Request.Builder().url(API+route+"?"+q).header("User-Agent","Mozilla/5.0 (Linux; U; Android 13; zh-cn; M2004J7AC) AppleWebKit/533.1 Mobile Safari/533.1").header("x-pan-tm",t).header("x-pan-token",digest("SHA-256","GET&"+route+"&"+t+"&"+SIGN)).header("x-pan-client-id",CLIENT).build();return execute(r);}
 static JSONObject execute(Request request)throws Exception{try(Response r=OkHttp.client(15000).newCall(request).execute()){String body=r.body()==null?"":r.body().string();JSONObject result;try{result=new JSONObject(body);}catch(Exception e){throw new IllegalStateException("UC TV 服务返回非JSON（HTTP "+r.code()+"）");}return response(request.url().encodedPath(),r.code(),result);}}
 // OAuth polling returns HTTP 400 while the user has not confirmed the QR.
 // Only this specific polling response is pending; account checks stay strict.
 static JSONObject response(String route,int http,JSONObject result)throws Exception{String message=result.optString("error_info",result.optString("message"));if(http==400&&"/oauth/code".equals(route)&&"用户未确认授权".equals(message.trim()))return new JSONObject().put("status",-1).put("pending",true);if(http<200||http>=300)throw new IllegalStateException("UC TV 请求失败（HTTP "+http+"）："+message);return result;}
 static JSONObject authorize()throws Exception{return get("/oauth/authorize",new JSONObject().put("auth_type","code").put("client_id",CLIENT).put("scope","netdisk").put("qrcode","1").put("qr_width","360").put("qr_height","360"));}
 static JSONObject code(String query)throws Exception{return get("/oauth/code",new JSONObject().put("client_id",CLIENT).put("scope","netdisk").put("query_token",query));}
 static JSONObject exchange(String code)throws Exception{JSONObject p=params().put("code",code);JSONObject r=execute(new Request.Builder().url("https://api.extscreen.com/ucdrive/token").post(RequestBody.create(MediaType.parse("application/json"),p.toString())).build());JSONObject data=r.optJSONObject("data");if(r.optInt("code")!=200||data==null||data.optString("access_token").isBlank())throw new IllegalStateException("UC TV Token兑换失败："+r.optString("message","没有返回授权令牌"));return data;}
 static String validate(JSONObject secret)throws Exception{String token=secret.optString("token",secret.optString("access_token"));if(token.isBlank())throw new IllegalStateException("UC TV Token为空，请扫码登录");JSONObject r=get("/file",new JSONObject().put("access_token",token).put("method","list").put("parent_fid","0").put("limit","1"));if(r.optInt("status",-1)!=0||!r.has("data"))throw new IllegalStateException("UC TV 官方授权校验失败："+r.optString("error_info",r.optString("message","没有返回目录授权"))+"（status="+r.optInt("status",-1)+"）");return "登录有效，已通过UC TV官方授权检查";}
    private static java.util.List<String> keys(JSONObject value) { java.util.List<String> keys = new java.util.ArrayList<>(); value.keys().forEachRemaining(keys::add); return keys; }
    private static String hex(byte[] bytes) { StringBuilder out = new StringBuilder(); for (byte b : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255)); return out.toString(); }
}
