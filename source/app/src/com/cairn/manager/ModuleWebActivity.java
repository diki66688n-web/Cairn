package com.cairn.manager;

import android.app.*;
import android.os.Bundle;
import android.webkit.*;
import android.net.Uri;
import android.widget.*;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Module-local WebUI. Deliberately no arbitrary shell/exec or key API. */
public final class ModuleWebActivity extends Activity {
    @Override protected void attachBaseContext(android.content.Context base){super.attachBaseContext(Language.wrap(base));}
    private static final String ORIGIN="https://cairn-module.invalid";
    private String id,base; private CairnCore core; private long k0,k1;
    private WebView web; private volatile boolean active=true,approved=false;
    private final Object ioLock=new Object();
    @Override public void onCreate(Bundle b){
        super.onCreate(b);id=getIntent().getStringExtra("module_id");
        if(id==null||!id.matches("[A-Za-z][A-Za-z0-9_.-]{0,62}")){finish();return;}
        try{String key=new KeyStoreBox(this).load();long[] k=CairnCore.keyToLongs(key);k0=k[0];k1=k[1];core=new CairnCore(this);}
        catch(Exception e){Toast.makeText(this,Language.text(this,"Cannot access the manager key","无法读取管理器密钥"),1).show();finish();return;}
        base="/data/adb/modules/"+id;
        LinearLayout page=new LinearLayout(this);page.setOrientation(1);page.setBackgroundColor(0xff101319);
        TextView title=new TextView(this);title.setText("‹ 返回     "+id+" · WebUI");title.setTextColor(-1);title.setPadding(24,48,24,20);title.setOnClickListener(v->finish());page.addView(title);
        web=new WebView(this);page.addView(web,new LinearLayout.LayoutParams(-1,0,1));setContentView(page);
        WebSettings s=web.getSettings();s.setJavaScriptEnabled(true);s.setAllowFileAccess(false);s.setAllowContentAccess(false);s.setDomStorageEnabled(false);s.setJavaScriptCanOpenWindowsAutomatically(false);s.setSupportMultipleWindows(false);s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web,false);
        web.setDownloadListener((u,ua,cd,m,n)->{});
        web.setWebChromeClient(new WebChromeClient(){@Override public void onPermissionRequest(PermissionRequest r){r.deny();}});
        web.addJavascriptInterface(new Api(),"Cairn");
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){return !local(r.getUrl());}
            @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest r){
                if(!local(r.getUrl())||!"GET".equals(r.getMethod()))return response(403,"text/plain","Blocked");
                String path=r.getUrl().getPath();if(path==null||path.equals("/"))path="/index.html";
                if(!safe(path.substring(1)))return response(403,"text/plain","Invalid path");
                String type=path.endsWith(".html")?"text/html":path.endsWith(".js")?"application/javascript":path.endsWith(".css")?"text/css":path.endsWith(".json")?"application/json":path.endsWith(".svg")?"image/svg+xml":"text/plain";
                String content=readOwn("webroot"+path);return content==null?response(403,"text/plain","Module unavailable or resource denied"):response(200,type,content);
            }
        });
        new AlertDialog.Builder(this).setTitle(Language.text(this,"Allow this module to use Cairn APIs?","允许模块使用 Cairn 接口？")).setMessage(id+" 将获准查看自身状态和读取自身目录内的文本文件。\n不提供任意命令、挂载、SELinux 修改或密钥访问。\n仅本次界面会话有效。").setNegativeButton(Language.text(this,"View only","仅浏览"),(d,w)->web.loadUrl(ORIGIN+"/index.html")).setPositiveButton(Language.text(this,"Allow","允许"),(d,w)->{approved=true;web.loadUrl(ORIGIN+"/index.html");}).setOnCancelListener(d->finish()).show();
    }
    private static boolean local(Uri u){return "https".equals(u.getScheme())&&"cairn-module.invalid".equals(u.getHost())&&u.getPort()==-1;}
    private static boolean safe(String p){if(p==null||p.length()>512||p.startsWith("/")||p.contains("\\")||p.contains("\n")||p.contains("\r"))return false;for(String s:p.split("/",-1))if(s.equals("..")||s.equals(".")||s.isEmpty()||s.startsWith(".cairn"))return false;return true;}
    private CairnCore.Res run(String cmd){synchronized(ioLock){return core.runRoot(k0,k1,cmd);}}
    private boolean enabled(){if(!active)return false;CairnCore.Res r=run("/data/adb/cnd module list");for(String line:r.text().split("\n"))if(line.equals(id+"\tenabled"))return r.rc==0;return false;}
    private String readOwn(String path){if(!safe(path)||!enabled())return null;
        String q=ModulePage.q(base),p=ModulePage.q(base+"/"+path);
        CairnCore.Res r=run("b=$(readlink -f "+q+") || exit 1\np=$(readlink -f "+p+") || exit 1\ncase \"$p\" in \"$b\"/*) ;; *) exit 1;; esac\n[ -f \"$p\" ] || exit 1\n[ $(stat -c%s \"$p\") -le 65536 ] || exit 1\ncat \"$p\"\n");return r.rc==0?r.text():null;
    }
    private static WebResourceResponse response(int code,String mime,String body){Map<String,String> h=new HashMap<>();h.put("Content-Security-Policy","default-src 'none'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; frame-src 'none'; child-src 'none'; connect-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'; worker-src 'none'");h.put("Cache-Control","no-store");h.put("X-Content-Type-Options","nosniff");return new WebResourceResponse(mime,"UTF-8",code,code==200?"OK":"Forbidden",h,new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));}
    private String result(String value,String error){try{JSONObject j=new JSONObject();j.put("ok",error==null);if(error==null)j.put("data",value);else j.put("error",error);return j.toString();}catch(Exception e){return "{\"ok\":false}";}}
    public final class Api {
        @JavascriptInterface public String version(){return "1";}
        @JavascriptInterface public String status(){if(!approved||!enabled())return result(null,"permission-denied-or-module-disabled");return result(id+":enabled",null);}
        @JavascriptInterface public String readFile(String relativePath){if(!approved)return result(null,"permission-denied");String s=readOwn(relativePath);return s==null?result(null,"read-denied"):result(s,null);}
    }
    @Override protected void onDestroy(){active=false;approved=false;if(web!=null){web.removeJavascriptInterface("Cairn");web.stopLoading();web.destroy();}k0=k1=0;super.onDestroy();}
}
