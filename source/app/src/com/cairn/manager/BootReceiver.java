package com.cairn.manager;
import android.content.*;
import android.util.Log;
/** Restore through the manager's existing key channel after credential unlock. */
public final class BootReceiver extends BroadcastReceiver {
 @Override public void onReceive(Context ctx,Intent intent){
  if(!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())&&!Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction()))return;
  final PendingResult pending=goAsync();final Context app=ctx.getApplicationContext();
  new Thread(()->{try{
   String key=new KeyStoreBox(app).load();if(key==null){Log.e("CairnBoot","Whitelist restore: key unavailable");return;}
   long[] k=CairnCore.keyToLongs(key);CairnCore c=new CairnCore(app);CairnCore.Res r=c.startDaemon(k[0],k[1]);
   Log.i("CairnBoot","Whitelist restore rc="+r.rc+" "+r.text());
  }catch(Exception e){Log.e("CairnBoot","Whitelist restore failed",e);}finally{pending.finish();}},"CairnBootSync").start();
 }
}