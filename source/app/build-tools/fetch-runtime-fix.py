from pathlib import Path
import urllib.request,zipfile,io,json
W=Path(__file__).resolve().parent;D=W/'deps';D.mkdir(exist_ok=True)
google='https://dl.google.com/dl/android/maven2';central='https://repo.maven.apache.org/maven2'
items=[('androidx.core','core','1.16.0','aar'),('androidx.core','core-viewtree','1.0.0','aar'),('androidx.lifecycle','lifecycle-runtime-compose-android','2.9.4','aar'),('androidx.savedstate','savedstate-compose-android','1.3.2','aar'),('androidx.savedstate','savedstate-android','1.3.2','aar'),('androidx.compose.runtime','runtime-retain-android','1.12.1','aar'),('androidx.collection','collection-jvm','1.5.0','jar'),('androidx.annotation','annotation-experimental','1.4.1','aar'),('androidx.autofill','autofill','1.0.0','aar'),('androidx.emoji2','emoji2','1.2.0','aar'),('androidx.savedstate','savedstate-ktx','1.3.1','aar'),('androidx.lifecycle','lifecycle-viewmodel-ktx','2.9.4','aar'),('org.jetbrains.kotlinx','kotlinx-coroutines-android','1.9.0','jar'),('androidx.startup','startup-runtime','1.1.1','aar'),('androidx.tracing','tracing','1.2.0','aar'),('org.jspecify','jspecify','1.0.0','jar')]
for g,a,v,ext in items:
 url=(google if g.startswith('androidx.') else central)+'/'+g.replace('.','/')+'/'+a+'/'+v+'/'+a+'-'+v+'.'+ext
 b=urllib.request.urlopen(url,timeout=30).read();p=D/(a+'-'+v+'.'+ext);p.write_bytes(b)
 if ext=='aar':
  z=zipfile.ZipFile(io.BytesIO(b))
  if 'classes.jar' in z.namelist():(D/(a+'-'+v+'.jar')).write_bytes(z.read('classes.jar'))
 print(a,v,flush=True)
(D/'runtime-fix-manifest.json').write_text(json.dumps(items,indent=2))
# New artifacts replace older versions, rather than merge both versions arbitrarily.
p=W/'compile-dex.py';s=p.read_text().replace("'core-runtime.jar'}","'core-runtime.jar','core-1.15.0.jar','savedstate-android-1.3.1.jar','collection-jvm-1.4.2.jar'}");p.write_text(s)
p=W/'package_candidate.py';s=p.read_text().replace("'material3-android-1.4.0.aar')","'material3-android-1.4.0.aar','core-1.15.0.aar','savedstate-android-1.3.1.aar')");p.write_text(s)