package com.cairn.manager;
import android.app.*;
import android.content.*;
import android.net.Uri;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/* KSU-layout script/WebUI subset: bounded ZIP extraction, customize.sh installer environment, modules_update staging. SKIPUNZIP and full KSU compatibility are not implemented. No hostile-root-script isolation. */
final class ModulePage {
 final Activity a; final CairnCore core; final java.util.function.Supplier<long[]> keys;
 final LinearLayout root,rows; final TextView status;
 int dp(float n){return (int)(n*a.getResources().getDisplayMetrics().density+0.5f);}
 TextView text(String label,int size,int color){TextView t=new TextView(a);t.setText(label);t.setTextSize(size);t.setTextColor(color);return t;}
 void space(LinearLayout parent,int h){View v=new View(a);parent.addView(v,new LinearLayout.LayoutParams(1,dp(h)));}
 com.cairn.manager.glass.GlassCard button(String label,int tint){return com.cairn.manager.glass.GlassComponents.button(a,label,18f,tint,0xffe6edf3);}
 ModulePage(Activity a,CairnCore c,java.util.function.Supplier<long[]> k){
  this.a=a;core=c;keys=k;root=new LinearLayout(a);root.setOrientation(1);
  ScrollView scroll=new ScrollView(a);scroll.setFillViewport(true);scroll.setVerticalScrollBarEnabled(false);scroll.setClipToPadding(false);scroll.setPadding(0,0,0,dp(120));root.addView(scroll,new LinearLayout.LayoutParams(-1,-1));
  LinearLayout body=new LinearLayout(a);body.setOrientation(1);body.setPadding(dp(20),dp(28),dp(20),dp(20));scroll.addView(body);
  TextView title=text(Language.text(a,"Modules","模块"),32,0xffe6edf3);title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);title.setLetterSpacing(-0.02f);body.addView(title);space(body,6);
  body.addView(text(Language.text(a,"Boot scripts · KSU ZIP format","管理开机脚本 · KSU ZIP 格式"),12,0xff8b95a5));space(body,22);
  com.cairn.manager.glass.GlassCard info=com.cairn.manager.glass.GlassComponents.card(a,24f,0,false);
  LinearLayout inside=new LinearLayout(a);inside.setOrientation(1);inside.setPadding(dp(20),dp(20),dp(20),dp(20));info.addView(inside);
  TextView head=text(Language.text(a,"Lightweight extensions","轻量扩展，保持系统原样"),18,0xff7fd1ff);head.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);inside.addView(head);space(inside,10);
  inside.addView(text(Language.text(a,"Extend with scripts. Reboot to apply updates.\nNo module mounts. No SELinux policy edits.","用脚本拓展功能，安装与更新重启生效。\n不挂载模块，不修改 SELinux 策略。"),13,0xff8b95a5));body.addView(info,new LinearLayout.LayoutParams(-1,-2));space(body,16);
  LinearLayout actions=new LinearLayout(a);actions.setOrientation(0);
  com.cairn.manager.glass.GlassCard install=button(Language.text(a,"＋ Install module","＋ 安装模块"),0x304c8dff);install.setOnClickListener(v->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("*/*");i.addCategory(Intent.CATEGORY_OPENABLE);a.startActivityForResult(i,710);});
  LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(52),1);lp.rightMargin=dp(10);actions.addView(install,lp);
  com.cairn.manager.glass.GlassCard refresh=button(Language.text(a,"Refresh","刷新"),0x14ffffff);refresh.setOnClickListener(v->refresh());actions.addView(refresh,new LinearLayout.LayoutParams(dp(92),dp(52)));body.addView(actions);space(body,24);
  status=text(Language.text(a,"Loading modules…","正在读取模块…"),12,0xff8b95a5);body.addView(status);space(body,12);rows=new LinearLayout(a);rows.setOrientation(1);body.addView(rows);
 }
 static String q(String s){return "'"+s.replace("'","'\\''")+"'";}
 CairnCore.Res run(String cmd){long[] k=keys.get();return core.runRoot(k[0],k[1],cmd);}
 void refresh(){new Thread(()->{CairnCore.Res r=run("/data/adb/cnd module list"); final HashSet<String> webModules=new HashSet<>(); for(String line:r.text().split("\n")){String[] item=line.split("\t");if(item.length!=2||!item[0].matches("[A-Za-z][A-Za-z0-9_.-]{0,62}"))continue; CairnCore.Res check=run("test -f "+q("/data/adb/modules/"+item[0]+"/webroot/index.html"));if(check.rc==0)webModules.add(item[0]);} a.runOnUiThread(()->{
 rows.removeAllViews();int count=0;
 for(String line:r.text().split("\n")){String[] p=line.split("\t");if(p.length!=2||!p[0].matches("[A-Za-z0-9_][A-Za-z0-9_.-]{0,62}"))continue;count++;
 boolean off=p[1].equals("disabled"),pending=p[1].equals("pending");int color=off?0xff8b95a5:pending?0xfffbbf24:0xff4ade80;
 com.cairn.manager.glass.GlassCard card=com.cairn.manager.glass.GlassComponents.card(a,24f,0,false);
 LinearLayout box=new LinearLayout(a);box.setOrientation(1);box.setPadding(dp(20),dp(18),dp(20),dp(18));card.addView(box);
 TextView name=text(p[0],18,0xffe6edf3);name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);box.addView(name);space(box,8);
 box.addView(text("●  "+(off?Language.text(a,"Disabled","已禁用"):pending?Language.text(a,"Reboot required","待重启"):Language.text(a,"Enabled","已启用")),12,color));space(box,10);
 box.addView(text(off?Language.text(a,"This module will be skipped on subsequent boots.","下次启动将跳过此模块。"):pending?Language.text(a,"Boot scripts become eligible after reboot.","重启后允许执行开机脚本。"):Language.text(a,"Eligible for boot-stage execution.","允许在 post-fs-data 阶段执行。"),12,0xff8b95a5));space(box,16);
 com.cairn.manager.glass.GlassCard toggle=button(off?Language.text(a,"Enable · reboot required","恢复启用 · 重启生效"):Language.text(a,"Disable module","禁用模块"),off?0x304c8dff:0x14ffffff);
 toggle.setOnClickListener(v->new AlertDialog.Builder(a).setTitle(off?Language.text(a,"Enable module?","恢复启用？"):Language.text(a,"Disable module?","禁用模块？")).setMessage(off?Language.text(a,"Reboot to apply this change.","恢复后需重启生效。"):Language.text(a,"Prevents future execution; does not undo changes already made during this boot.","阻止后续执行；不会撤销本次开机已经执行的效果。")).setNegativeButton(Language.text(a,"Cancel","取消"),null).setPositiveButton(Language.text(a,"Confirm","确认"),(d,which)->new Thread(()->{CairnCore.Res x=run("/data/adb/cnd module "+(off?"enable ":"disable ")+q(p[0]));a.runOnUiThread(()->{Toast.makeText(a,x.rc==0?Language.text(a,"Done","操作成功"):x.text(),Toast.LENGTH_LONG).show();refresh();});}).start()).show());
 box.addView(toggle,new LinearLayout.LayoutParams(-1,dp(46)));
 if(!off&&!pending&&webModules.contains(p[0])){space(box,10);com.cairn.manager.glass.GlassCard ui=button(Language.text(a,"Open WebUI","打开 WebUI"),0x304c8dff);ui.setOnClickListener(v->new Thread(()->{CairnCore.Res check=run("test -f "+q("/data/adb/modules/"+p[0]+"/webroot/index.html"));a.runOnUiThread(()->{if(check.rc!=0){Toast.makeText(a,Language.text(a,"This module has no WebUI entry point","模块没有 WebUI 入口"),Toast.LENGTH_LONG).show();refresh();return;}Intent intent=new Intent(a,ModuleWebActivity.class);intent.putExtra("module_id",p[0]);a.startActivity(intent);});}).start());box.addView(ui,new LinearLayout.LayoutParams(-1,dp(46)));}
rows.addView(card,new LinearLayout.LayoutParams(-1,-2));space(rows,12);
 }
 status.setText(r.rc==0?Language.text(a,"Installed: ","已安装 ")+count+Language.text(a," modules"," 个模块"):r.text());
 if(count==0&&r.rc==0){com.cairn.manager.glass.GlassCard empty=com.cairn.manager.glass.GlassComponents.card(a,24f,0,false);TextView t=text(Language.text(a,"No modules yet\n\nChoose a ZIP using Install module\nReboot after installation to activate it","暂无模块\n\n点击上方「安装模块」选择 ZIP\n安装完成后，重启手机生效"),15,0xff8b95a5);t.setGravity(Gravity.CENTER);t.setPadding(dp(20),dp(38),dp(20),dp(38));empty.addView(t);rows.addView(empty,new LinearLayout.LayoutParams(-1,-2));}
 });}).start();}
 void install(Uri uri){InstallConsole.start(a);InstallConsole.append("[1/4] Validating and extracting module files");status.setText("Checking ZIP…");new Thread(()->{File temp=new File(a.getFilesDir(),"module-unpack-"+System.nanoTime());temp.mkdirs();try{
  long total=0;int count=0;HashSet<String> names=new HashSet<>();
  try(ZipInputStream z=new ZipInputStream(a.getContentResolver().openInputStream(uri))){ZipEntry e;byte[] buf=new byte[16384];while((e=z.getNextEntry())!=null){String n=e.getName();if(++count>2048||n.startsWith("/")||n.contains("\\")||!names.add(n))throw new IOException("Invalid ZIP path or duplicate entry");for(String part:n.split("/"))if(part.equals("..")||part.startsWith(".cairn")||part.equals(".install-source.zip"))throw new IOException("Invalid path");if(n.contains("\n")||n.contains("\r"))throw new IOException("Invalid path");
   String low=n.toLowerCase(Locale.ROOT);String base=low.substring(low.lastIndexOf('/')+1);
   if(base.equals("sepolicy.rule")||base.equals("post-mount.sh")||base.equals("boot-completed.sh")||base.equals("action.sh")||base.equals("uninstall.sh")||low.startsWith("system/")||low.startsWith("vendor/")||low.startsWith("product/")||low.startsWith("system_ext/")||low.startsWith("odm/"))throw new IOException("Unsupported module feature: "+n);
   if(low.startsWith("meta-inf/"))continue;
   File f=new File(temp,n);if(!f.getCanonicalPath().startsWith(temp.getCanonicalPath()+"/"))throw new IOException("Path traversal rejected");if(e.isDirectory()){f.mkdirs();continue;}f.getParentFile().mkdirs();try(FileOutputStream out=new FileOutputStream(f)){int read;while((read=z.read(buf))!=-1){total+=read;if(total>32*1024*1024)throw new IOException("Module exceeds 32MiB");out.write(buf,0,read);}}
  }}
  InstallConsole.append("- Extracted size: "+String.format(java.util.Locale.US,"%.2f MiB",total/1048576.0));
  Properties props=new Properties();try(InputStream in=new FileInputStream(new File(temp,"module.prop"))){props.load(new InputStreamReader(in,"UTF-8"));}String id=props.getProperty("id","").trim();if(!id.matches("[A-Za-z][A-Za-z0-9_.-]{0,62}")||id.endsWith("."))throw new IOException("Invalid module.prop id");
  InstallConsole.append("- Installing to /data/adb/modules_update/"+id);
  InstallConsole.append("- Module: "+props.getProperty("name",id)+" · "+props.getProperty("version","?"));
  String target="/data/adb/cairn/staging/"+id;
  File helper=new File(a.getFilesDir(),"module-install-"+System.nanoTime()+".sh");
  try(InputStream input=a.getAssets().open("module-install.sh");FileOutputStream output=new FileOutputStream(helper)){byte[] b=new byte[8192];int n;while((n=input.read(b))!=-1)output.write(b,0,n);}
  File archive=new File(temp,".install-source.zip");
  try(InputStream input=a.getContentResolver().openInputStream(uri);FileOutputStream output=new FileOutputStream(archive)){byte[] b=new byte[16384];int n;long limit=0;while((n=input.read(b))!=-1){limit+=n;if(limit>32*1024*1024)throw new IOException("ZIP exceeds size limit");output.write(b,0,n);}}
  InstallConsole.append("[2/4] Creating isolated staging directory: "+id);
  CairnCore.Res copy=run("mkdir -p /data/adb/cairn/staging\nif [ -e "+q(target)+" ] || [ -e "+q("/data/adb/modules_update/"+id)+" ]; then echo 'A pending update exists. Reboot before reinstalling.'; exit 1; fi\ncp -R "+q(temp.getAbsolutePath())+" "+q(target)+"\n");
  InstallConsole.append(copy.text());if(copy.rc!=0)throw new IOException("Cannot create staging directory");
  InstallConsole.append("[3/4] Running module installer · live output · 120s timeout");
  File live=new File(a.getFilesDir(),"install-log-"+System.nanoTime()+".txt");InstallConsole.liveLog=live;
  CairnCore.Res custom=run("umask 022\n/system/bin/timeout -s KILL 120 /data/adb/bin/sh "+q(helper.getAbsolutePath())+" "+q(target)+" "+q(target+"/.install-source.zip")+" > "+q(live.getAbsolutePath())+" 2>&1\n");
  try(FileInputStream log=new FileInputStream(live)){byte[] b=new byte[(int)Math.min(live.length(),131072)];int n=log.read(b);if(n>0)InstallConsole.append(new String(b,0,n,"UTF-8"));}catch(Exception ignored){}InstallConsole.liveLog=null;
  InstallConsole.append(custom.text());helper.delete();
  if(custom.rc!=0){run("rm -rf "+q(target));throw new IOException("customize.sh failed; existing version retained: "+custom.rc);}
  InstallConsole.append("[4/4] Queuing installation in modules_update");
  CairnCore.Res r=run("rm -f "+q(target+"/.install-source.zip")+"\n/data/adb/cnd module install "+q(id)+"\n");
  InstallConsole.append(r.text());if(r.rc!=0)throw new IOException("Cannot queue update: "+r.rc);
  InstallConsole.append("✓ Installation complete. Reboot to activate. Existing version retained until reboot.");
  a.runOnUiThread(()->{status.setText(Language.text(a,"Installed. Reboot to activate.","安装成功，重启后生效"));refresh();});
 }catch(Exception e){InstallConsole.append("✗ Installation failed: "+e.getMessage());a.runOnUiThread(()->status.setText("Installation failed: "+e.getMessage()));}finally{InstallConsole.running=false;remove(temp);}}).start();}
 static void remove(File f){File[] fs=f.listFiles();if(fs!=null)for(File x:fs)remove(x);f.delete();}
}
